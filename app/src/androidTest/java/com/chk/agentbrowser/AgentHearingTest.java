package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

/** Known PCM fixture: silence, stereo tone and near-full-scale plateau. No Internet/model required. */
@RunWith(AndroidJUnit4.class)
public final class AgentHearingTest {
    private Context app;private SharedPreferences prefs;private boolean oldPreview;private String path;private File source;
    @Before public void setup()throws Exception{
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();prefs=app.getSharedPreferences("browser_mcp_v1",0);oldPreview=prefs.getBoolean("preview_allowed",false);prefs.edit().putBoolean("preview_allowed",true).commit();
        path="hearing-"+java.util.UUID.randomUUID()+".wav";source=new WorkspaceStore(app).file(path);
        ByteBuffer data=ByteBuffer.allocate(16000*3*4+44).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes("US-ASCII")).putInt(data.capacity()-8).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)2).putInt(16000).putInt(64000).putShort((short)4).putShort((short)16).put("data".getBytes("US-ASCII")).putInt(data.capacity()-44);
        for(int i=0;i<48000;i++){double t=i/16000d;short sample=t<.5||t>=2?0:t>=1.5?32767:(short)Math.round(16384*Math.sin(2*Math.PI*440*t));data.putShort(sample).putShort((short)0);}
        try(FileOutputStream out=new FileOutputStream(source)){out.write(data.array());}
    }
    @After public void cleanup(){source.delete();prefs.edit().putBoolean("preview_allowed",oldPreview).commit();}
    @Test public void measuredStereoLevelsSilencesAndClippingComeFromDecodedSamples()throws Exception{
        long modified=source.lastModified();JSONObject result=AgentHearing.process(app,new JSONObject().put("path",path).put("max_seconds",3),false,null);
        assertEquals(2,result.getInt("channels"));assertEquals(48000,result.getLong("frames"));assertEquals(3,result.getDouble("analyzed_seconds"),.001);
        assertTrue(result.getDouble("peak_dbfs")>-.01);assertTrue(result.getBoolean("clipping_suspected"));
        assertTrue(result.getDouble("silence_fraction")>.45);assertTrue(result.getDouble("silence_fraction")<.6);
        assertEquals(-120,result.getJSONArray("channel_levels").getJSONObject(1).getDouble("rms_dbfs"),0);
        assertTrue(result.getJSONArray("silences").length()>=2);assertFalse(result.has("audio_base64"));assertFalse(result.getBoolean("audio_uploaded"));assertEquals(modified,source.lastModified());
    }
    @Test public void requestedExcerptIsARealSeekedWavWithVerifiedSizeAndDigest()throws Exception{
        JSONObject result=AgentHearing.process(app,new JSONObject().put("path",path).put("start_ms",500).put("max_seconds",1),true,null);
        byte[] wav=android.util.Base64.decode(result.getString("audio_base64"),0);assertEquals(32044,wav.length);ByteBuffer header=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1,header.getShort(22));assertEquals(16000,header.getInt(24));assertEquals(32000,header.getInt(40));
        byte[] expected=java.security.MessageDigest.getInstance("SHA-256").digest(wav);StringBuilder sha=new StringBuilder();for(byte b:expected)sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));assertEquals(sha.toString(),result.getString("sha256"));
        double sum=0;while(header.position()<44)header.get();while(header.remaining()>=2){double value=header.getShort()/32768d;sum+=value*value;}
        assertEquals(-15.05,20*Math.log10(Math.sqrt(sum/16000)),.15);assertEquals(500,result.getLong("source_start_ms"));assertEquals(1500,result.getLong("source_end_ms"));
        assertTrue(result.getBoolean("audio_uploaded"));assertFalse(result.getBoolean("source_file_uploaded"));assertEquals("requested_excerpt_only",result.getString("audio_export_scope"));
        assertFalse(new JSONObject(Local3Client.compactResult(app,result.toString())).optBoolean("result_in_file"));
    }
    @Test public void previewConsentBoundsAndWorkspaceContainmentAreEnforced()throws Exception{
        prefs.edit().putBoolean("preview_allowed",false).commit();rejected(new JSONObject().put("path",path).put("max_seconds",1));prefs.edit().putBoolean("preview_allowed",true).commit();
        rejected(new JSONObject().put("path","../outside.wav"));rejected(new JSONObject().put("path",path).put("max_seconds",6));
        rejected(new JSONObject().put("path",path).put("start_ms",4000).put("max_seconds",1));
    }
    private void rejected(JSONObject p)throws Exception{try{AgentHearing.process(app,p,true,null);fail("Invalid excerpt must fail");}catch(IOException expected){assertNotNull(expected.getMessage());}}
    @Test public void cancellationDoesNotProduceAnAudioResult()throws Exception{
        try{AgentHearing.process(app,new JSONObject().put("path",path),false,new AgentClient.ResultCallback(){public boolean isActive(){return false;}public void finish(boolean ok,String text){fail();}});fail("Cancellation must stop PCM analysis");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("interrompue"));}
    }
    @Test public void studioPcm24AndFloat32WavAreDecodedWithoutAnAudioRawCodec()throws Exception{
        for(int bits:new int[]{24,32}){
            int bytes=bits/8;ByteBuffer data=ByteBuffer.allocate(16000*bytes+44).order(ByteOrder.LITTLE_ENDIAN);
            data.put("RIFF".getBytes("US-ASCII")).putInt(data.capacity()-8).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)(bits==32?3:1)).putShort((short)1).putInt(16000).putInt(16000*bytes).putShort((short)bytes).putShort((short)bits).put("data".getBytes("US-ASCII")).putInt(data.capacity()-44);
            for(int i=0;i<16000;i++)if(bits==32)data.putFloat(.25f);else data.put((byte)0).put((byte)0).put((byte)32);
            try(FileOutputStream out=new FileOutputStream(source)){out.write(data.array());}
            JSONObject result=AgentHearing.process(app,new JSONObject().put("path",path).put("max_seconds",1),false,null);
            assertEquals(-12.041,result.getDouble("rms_dbfs"),.01);assertEquals(16000,result.getLong("frames"));
        }
    }
    @Test public void compressedAacAudioIsDecodedFromARealMp4()throws Exception{
        File video=new WorkspaceStore(app).file("silent-"+java.util.UUID.randomUUID()+".mp4");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("editor-fixture.mp4");FileOutputStream out=new FileOutputStream(video)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        try{AudioDecoder.Info decoded=AudioDecoder.read(video,0,1,(data,rate,channels,time)->assertTrue(data.remaining()>0),null);assertTrue(decoded.frames>0);assertTrue(decoded.rate>=8000);assertTrue(decoded.lastUs<=1000000L);}
        finally{video.delete();}
    }
}
