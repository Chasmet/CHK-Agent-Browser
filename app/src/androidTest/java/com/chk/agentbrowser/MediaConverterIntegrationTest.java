package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MediaConverterIntegrationTest {
    private static void le(DataOutputStream o,int value)throws IOException{o.writeInt(Integer.reverseBytes(value));}
    private static void sh(DataOutputStream o,int value)throws IOException{o.writeShort(Short.reverseBytes((short)value));}
    @Test public void commandsUseLocalPathsAndLame320()throws Exception{
        String[] args=MediaConverter.command(new File("/tmp/Mon morceau.wav"),new File("/tmp/copie.mp3"),"mp3",new JSONObject(),1000000);
        List<String> a=Arrays.asList(args);
        assertTrue(a.contains("libmp3lame"));assertTrue(a.contains("320k"));assertTrue(a.contains("-n"));assertTrue(a.contains("file"));
        assertTrue(a.contains("/tmp/Mon morceau.wav"));
        try{MediaConverter.command(new File("a.wav"),new File("b.exe"),"exe",new JSONObject(),1000000);fail("Format interdit");}catch(IOException expected){}
    }
    @Test public void videoRemuxTranscodeAndAudioExtraction()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeTrue(MediaConverter.deviceSupported());
        WorkspaceStore store=new WorkspaceStore(app);
        String name="media-video-test-"+UUID.randomUUID(),input=name+".mp4",output=name+".mkv",sound=name+".mp3";
        File source=store.file(input),video=store.file(output),audio=store.file(sound);
        try{
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("editor-fixture.mp4");
                OutputStream out=new FileOutputStream(source)){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            String hash=WorkspaceTransfer.stat(store,input,true).getString("sha256");
            AgentClient.ResultCallback active=(ok,result)->{};
            JSONObject result=UniversalFiles.convert(app,new JSONObject().put("path",input).put("destination",output).put("format","mkv").put("max_dimension",320),active);
            assertTrue(result.getBoolean("verified"));assertTrue(video.length()>1000);
            assertEquals("video",result.getJSONArray("streams").getJSONObject(0).getString("codec_type"));
            JSONObject extract=UniversalFiles.convert(app,new JSONObject().put("path",input).put("destination",sound).put("format","mp3"),active);
            assertTrue(extract.getBoolean("verified"));assertTrue(audio.length()>1000);assertEquals("mp3",extract.getJSONArray("streams").getJSONObject(0).getString("codec_name"));
            assertEquals(hash,WorkspaceTransfer.stat(store,input,true).getString("sha256"));
        }finally{source.delete();video.delete();audio.delete();}
    }
    @Test public void realWavToMp3AndFlacPreservesOriginal()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Supported CI ABI must actually run the codec; older/unsupported devices retain their existing tools.
        org.junit.Assume.assumeTrue(MediaConverter.deviceSupported());
        WorkspaceStore store=new WorkspaceStore(app);
        String name="media-converter-test-"+UUID.randomUUID(),source=name+".wav",mp3=name+".mp3",flac=name+".flac";
        File wav=store.file(source),out=store.file(mp3),lossless=store.file(flac);
        try{
            int samples=44100,bytes=samples*4;
            try(DataOutputStream o=new DataOutputStream(new FileOutputStream(wav))){
                o.writeBytes("RIFF");le(o,36+bytes);o.writeBytes("WAVEfmt ");le(o,16);sh(o,1);sh(o,2);le(o,44100);le(o,176400);sh(o,4);sh(o,16);o.writeBytes("data");le(o,bytes);
                for(int i=0;i<samples;i++){int v=(int)(Math.sin(i*2*Math.PI*440/44100)*8000);sh(o,v);sh(o,v);}
            }
            String hash=WorkspaceTransfer.stat(store,source,true).getString("sha256");
            AgentClient.ResultCallback active=(ok,result)->{};
            JSONObject m=UniversalFiles.convert(app,new JSONObject().put("path",source).put("destination",mp3).put("format","mp3"),active);
            assertTrue(m.getBoolean("verified"));assertTrue(out.length()>1000);assertTrue(out.length()<wav.length());assertEquals("mp3",m.getJSONArray("streams").getJSONObject(0).getString("codec_name"));
            JSONObject f=UniversalFiles.convert(app,new JSONObject().put("path",source).put("destination",flac).put("format","flac"),active);
            assertTrue(f.getBoolean("verified"));assertEquals(hash,WorkspaceTransfer.stat(store,source,true).getString("sha256"));
            String mp3hash=WorkspaceTransfer.stat(store,mp3,true).getString("sha256");
            try{UniversalFiles.convert(app,new JSONObject().put("path",source).put("destination",mp3).put("format","mp3"),active);fail("Écrasement refusé");}catch(IOException expected){}
            assertEquals(mp3hash,WorkspaceTransfer.stat(store,mp3,true).getString("sha256"));
        }finally{wav.delete();out.delete();lossless.delete();}
    }
}
