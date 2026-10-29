package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Encoder verification: real mixed MP4 + decoded PCM, not a metadata mock. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public final class StudioProfessionalExportTest {
    private final Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final WorkspaceStore store=new WorkspaceStore(app);
    private String uid(){return UUID.randomUUID().toString().replace("-","");}
    private JSONObject invoke(String action,JSONObject args)throws Exception{
        CountDownLatch latch=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();
        AtomicReference<String> answer=new AtomicReference<>();
        VideoEditorEngine.get().command(app,action,args,(success,body)->{ok.set(success);answer.set(body);latch.countDown();});
        assertTrue(action+" timed out",latch.await(45,TimeUnit.SECONDS));
        assertTrue(action+": "+answer.get(),ok.get());
        return new JSONObject(answer.get());
    }
    private void finishExport()throws Exception{
        for(int i=0;i<480;i++){
            JSONObject status=invoke("video_editor_status",new JSONObject());
            String state=status.optString("state");
            if("completed".equals(state))return;
            if("failed".equals(state)||"cancelled".equals(state))
                fail("Studio MP4 export failed: "+status);
            Thread.sleep(500);
        }
        fail("MP4 export did not complete");
    }
    private File fixture(String path)throws Exception{
        File cache=new File(app.getCacheDir(),"chk-pro-"+uid()+".mp4");
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("editor-fixture.mp4");
            OutputStream output=new FileOutputStream(cache)){
            byte[] buffer=new byte[16384];int count;
            while((count=input.read(buffer))>0)output.write(buffer,0,count);
        }
        store.importUri(Uri.fromFile(cache),path);cache.delete();
        return store.file(path);
    }
    private void pcmTone(File output,int seconds)throws Exception{
        final int sampleRate=48000,frames=sampleRate*seconds,bytes=frames*2;
        try(FileOutputStream file=new FileOutputStream(output)){
            ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes("US-ASCII")).putInt(36+bytes)
                .put("WAVEfmt ".getBytes("US-ASCII")).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(sampleRate)
                .putInt(sampleRate*2).putShort((short)2).putShort((short)16)
                .put("data".getBytes("US-ASCII")).putInt(bytes);
            file.write(header.array());
            ByteBuffer b=ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<frames;i++){
                if(b.remaining()<2){file.write(b.array(),0,b.position());b.clear();}
                b.putShort((short)Math.round(Math.sin(i*2*Math.PI*440/sampleRate)*10000));
            }
            if(b.position()>0)file.write(b.array(),0,b.position());
        }
    }
    @Test public void originalVoiceAndFifteenPercentWavSurviveParallelExport()throws Exception{
        String video="studio-voice-"+uid()+".mp4",wav="studio-music-"+uid()+".wav",mp4="studio-mix-"+uid()+".mp4";
        fixture(video);pcmTone(store.file(wav),2);
        JSONObject clip=new JSONObject().put("path",video).put("start_ms",0).put("duration_ms",2000).put("volume",1);
        JSONObject song=new JSONObject().put("path",wav).put("start_ms",0).put("duration_ms",2000)
            .put("offset_ms",0).put("volume",.15);
        JSONObject p=new JSONObject().put("name","Voix et musique 15%").put("output",mp4)
            .put("aspect_ratio","1:1").put("aspect_mode","fit").put("resolution",480)
            .put("mute_original",false).put("audio_layout","parallel")
            .put("clips",new JSONArray().put(clip)).put("audio",new JSONArray().put(song));
        try{
            JSONObject saved=invoke("video_editor_project_save",new JSONObject().put("project",p)).getJSONObject("project");
            JSONObject tracks=invoke("video_editor_audio_tracks",new JSONObject());
            assertTrue("Original audio must remain enabled",tracks.getBoolean("original_audio_enabled"));
            assertEquals(2,tracks.getJSONArray("tracks").length());
            assertEquals(15.0,tracks.getJSONArray("tracks").getJSONObject(1).getDouble("volume_percent"),.01);
            assertEquals(saved.getInt("revision"),tracks.getInt("revision"));
            invoke("video_editor_export",new JSONObject());
            finishExport();
            JSONObject verified=invoke("video_editor_verify_output",new JSONObject());
            assertTrue("Technical MP4 verification "+verified,verified.getBoolean("valid"));
            assertTrue("Voice and WAV must produce an audio stream",verified.getBoolean("has_audio"));
            final double[] rms={0};final long[] n={0};
            AudioDecoder.read(store.file(mp4),0,2,(buffer,rate,channels,timeUs)->{
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                while(buffer.remaining()>=2){double v=buffer.getShort()/32768d;rms[0]+=v*v;n[0]++;}
            },null);
            assertTrue("Decoded MP4 must contain non-silent audio "+n[0],n[0]>48000);
            assertTrue("Decoded audio should not be silent",Math.sqrt(rms[0]/n[0])>.001);
            assertFalse("No forensic proof of isolated original source: don't overclaim",verified.optBoolean("audio_levels_verified",false));
        }finally{store.file(video).delete();store.file(wav).delete();store.file(mp4).delete();}
    }
    @Test public void cropIsAppliedToDecodedExportFrameWithoutChangingDuration()throws Exception{
        String photo="studio-split-"+uid()+".png",mp4="studio-crop-"+uid()+".mp4";
        Bitmap bmp=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);
        for(int y=0;y<320;y++)for(int x=0;x<320;x++)bmp.setPixel(x,y,x<160?Color.RED:Color.GREEN);
        try(FileOutputStream file=new FileOutputStream(store.file(photo))){assertTrue(bmp.compress(Bitmap.CompressFormat.PNG,100,file));}
        bmp.recycle();
        JSONObject crop=StudioCropControl.initial().put("x",.0).put("y",0).put("w",.48).put("h",1.0);
        JSONObject p=new JSONObject().put("output",mp4).put("aspect_ratio","1:1")
            .put("aspect_mode","fit").put("resolution",480)
            .put("mute_original",true)
            .put("clips",new JSONArray().put(new JSONObject().put("path",photo).put("start_ms",0)
                .put("duration_ms",1600).put("crop",crop)))
            .put("audio",new JSONArray());
        try{
            invoke("video_editor_project_save",new JSONObject().put("project",p));
            invoke("video_editor_export",new JSONObject());
            finishExport();
            JSONObject check=invoke("video_editor_verify_output",new JSONObject());
            assertTrue("Crop MP4 invalid "+check,check.optBoolean("valid"));
            assertEquals(1600,check.getLong("expected_duration_ms"));
            assertTrue("Crop must not change duration",Math.abs(check.getLong("duration_ms")-1600)<300);
            MediaMetadataRetriever reader=new MediaMetadataRetriever();
            try{
                reader.setDataSource(store.file(mp4).getAbsolutePath());
                Bitmap frame=reader.getScaledFrameAtTime(800000,MediaMetadataRetriever.OPTION_CLOSEST,320,320);
                assertNotNull("Cannot decode cropped export",frame);
                int center=frame.getPixel(frame.getWidth()/2,frame.getHeight()/2);
                frame.recycle();
                assertTrue("Cropped export should show red side, actual rgb="+Integer.toHexString(center),
                    Color.red(center)>Color.green(center)+40);
            }finally{reader.release();}
        }finally{store.file(photo).delete();store.file(mp4).delete();}
    }
}