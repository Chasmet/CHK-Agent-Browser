package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.*;
import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class StudioMultitrackIntegrationTest {
    Context app;WorkspaceStore store;String video,music;boolean previews;
    @Before public void setup()throws Exception{
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();store=new WorkspaceStore(app);previews=AgentClient.get(app).isPreviewAllowed();AgentClient.get(app).setPreviewAllowed(true);
        if(android.os.Build.VERSION.SDK_INT>=29)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->HardwareRenderer.setDrawingEnabled(true));
        video="mix-fixture-"+UUID.randomUUID()+".mp4";music="mix-music-"+UUID.randomUUID()+".wav";
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("editor-fixture.mp4");OutputStream out=new FileOutputStream(store.file(video))){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        try(OutputStream out=new FileOutputStream(store.file(music))){int bytes=32000*3;out.write("RIFF".getBytes("US-ASCII"));little(out,bytes+36,4);out.write("WAVEfmt ".getBytes("US-ASCII"));little(out,16,4);little(out,1,2);little(out,1,2);little(out,16000,4);little(out,32000,4);little(out,2,2);little(out,16,2);out.write("data".getBytes("US-ASCII"));little(out,bytes,4);for(int n=0;n<48000;n++)little(out,Math.round(20000*Math.sin(2*Math.PI*880*n/16000)),2);}
    }
    @After public void cleanup()throws Exception{AgentClient.get(app).setPreviewAllowed(previews);store.file(video).delete();store.file(music).delete();}
    static void little(OutputStream out,long n,int bytes)throws Exception{for(int i=0;i<bytes;i++)out.write((int)(n>>>(8*i))&255);}
    JSONObject engine(String action,JSONObject args)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();AtomicBoolean ok=new AtomicBoolean();
        VideoEditorEngine.get().command(app,action,args,(success,text)->{ok.set(success);value.set(text);done.countDown();});assertTrue("Timeout "+action,done.await(120,TimeUnit.SECONDS));assertTrue(value.get(),ok.get());return new JSONObject(value.get());
    }
    JSONObject p(String output)throws Exception{return new JSONObject().put("name","Regression mix").put("output",output).put("resolution",480).put("aspect_ratio","source").put("aspect_mode","fit").put("mute_original",false)
        .put("clips",new JSONArray().put(new JSONObject().put("path",video).put("duration_ms",3000).put("volume",1))).put("audio",new JSONArray().put(new JSONObject().put("path",music).put("duration_ms",3000).put("volume",.15)));}
    JSONObject create(JSONObject p)throws Exception{return engine("video_editor_project_import",new JSONObject().put("project",p)).getJSONObject("project");}
    JSONObject args(JSONObject p,String op)throws Exception{return new JSONObject().put("project_id",p.getString("project_id")).put("operation",op).put("expected_revision",p.getInt("revision")).put("request_id",UUID.randomUUID().toString());}
    @Test public void atomicReceiptStableIdsAndConflict()throws Exception{
        JSONObject p=create(p("receipt-"+UUID.randomUUID()+".mp4"));String id=p.getJSONArray("audio").getJSONObject(0).getString("studio_id");
        JSONObject a=args(p,"audio_set_volume").put("clip_id",id).put("percent",15);JSONObject r=StudioMcp.dispatch(app,a);
        assertEquals(15,r.getDouble("volume_percent"),.001);assertTrue(StudioMcp.dispatch(app,a).getBoolean("replayed"));
        JSONObject actual=VideoEditorEngine.get().load(app);assertEquals(r.getInt("revision"),actual.getInt("revision"));assertEquals(id,actual.getJSONArray("audio").getJSONObject(0).getString("studio_id"));
        try{StudioMcp.dispatch(app,new JSONObject(a.toString()).put("percent",20));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("REQUEST_ID_REUSED"));}
        try{StudioMcp.dispatch(app,new JSONObject(a.toString()).put("request_id",UUID.randomUUID().toString()));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("REVISION_CONFLICT"));}
        assertEquals(1,actual.getJSONArray("clips").getJSONObject(0).optDouble("volume",1),.001);assertFalse(actual.optBoolean("mute_original"));
    }
    @Test public void realMixedPcmAndMp4RetainBothFrequencies()throws Exception{
        JSONObject p=create(p("studio-mixed-proof.mp4"));StudioAudio.enable(p);p.getJSONArray("audio").getJSONObject(0).put("audio_fade_in_ms",100).put("audio_fade_out_ms",100);
        p.put("revision",p.getInt("revision")+1);StudioProjectLibrary.save(app,p);
        StudioAudioMixer.Render render=StudioAudioMixer.render(app,p,null,n->{});assertEquals(2,render.proof.getJSONArray("contributions").length());
        assertTrue(tone(render.wav,440)>0.02);assertTrue(tone(render.wav,880)>0.02);
        engine("video_editor_export",new JSONObject().put("replace",true));awaitExport();
        assertTrue(tone(store.file("studio-mixed-proof.mp4"),440)>0.02);assertTrue(tone(store.file("studio-mixed-proof.mp4"),880)>0.02);
        JSONObject measured=StudioAudioAnalysis.measure(app,"studio-mixed-proof.mp4",0,3000,null);assertFalse(measured.toString(),measured.getBoolean("sample_clipping_detected"));
        proof("studio-mixed-proof.mp4",store.file("studio-mixed-proof.mp4"));proof("studio-mixed-proof.wav",render.wav);
        saveProof("studio-mixed-proof.json",new JSONObject().put("mix",render.proof).put("decoded",measured));
    }
    @Test public void limiterAndKeyframesAffectDecodedSamples()throws Exception{
        JSONObject p=create(p("unused-"+UUID.randomUUID()+".mp4"));StudioAudio.enable(p);p.put("mute_original",true);
        JSONObject c=p.getJSONArray("audio").getJSONObject(0);c.put("volume",2).put("gain_keyframes",new JSONArray().put(new JSONObject().put("id","a").put("time_ms",0).put("gain",0)).put(new JSONObject().put("id","b").put("time_ms",1000).put("gain",0)).put(new JSONObject().put("id","c").put("time_ms",1200).put("gain",2)));
        StudioAudioMixer.Render rendered=StudioAudioMixer.render(app,p,null,n->{});
        assertTrue(rendered.proof.getLong("limiter_affected_frames")>0);assertTrue(rendered.proof.getDouble("peak_before_dbfs")>0);assertTrue(rendered.proof.getDouble("peak_after_dbfs")<=-0.999);
        final double[] early={0},late={0};AudioDecoder.read(rendered.wav,0,3,(data,rate,channels,time)->{int n=0;while(data.remaining()>=channels*2){double at=time/1000d+n++*1000d/rate;for(int ch=0;ch<channels;ch++){double v=data.getShort()/32768d;if(at<800)early[0]+=v*v;if(at>1500)late[0]+=v*v;}}},null);
        assertEquals(0,early[0],.001);assertTrue(late[0]>1000);
    }
    @Test public void duckingTransitionsAreBoundedAndTrackSoloIsIndependent()throws Exception{
        JSONArray regions=new JSONArray().put(new JSONObject().put("start_ms",1000).put("end_ms",2000).put("rms_dbfs",-20));JSONObject duck=new JSONObject().put("enabled",true).put("reduction_db",-20).put("threshold_dbfs",-40).put("attack_ms",200).put("release_ms",400);
        assertEquals(1,StudioAudioMixer.duckGain(regions,duck,700),.0001);assertEquals(.55,StudioAudioMixer.duckGain(regions,duck,900),.0001);assertEquals(.1,StudioAudioMixer.duckGain(regions,duck,1500),.0001);assertEquals(.55,StudioAudioMixer.duckGain(regions,duck,2200),.0001);assertEquals(1,StudioAudioMixer.duckGain(regions,duck,2500),.0001);
        JSONObject p=create(p("solo-"+UUID.randomUUID()+".mp4"));StudioAudio.enable(p);StudioAudio.track(p,"original").put("solo",true);JSONArray s=StudioAudio.sources(p);assertTrue(StudioAudio.audible(p,s.getJSONObject(0)));assertFalse(StudioAudio.audible(p,s.getJSONObject(1)));
        StudioAudio.track(p,"original").put("mute",true);assertFalse(StudioAudio.audible(p,s.getJSONObject(0)));assertEquals(.15,p.getJSONArray("audio").getJSONObject(0).getDouble("volume"),.0001);
    }
    @Test public void cropChangesOutputGeometryAndMatchesRenderedPreview()throws Exception{
        String image="quadrants-"+UUID.randomUUID()+".png";Bitmap bitmap=Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint paint=new Paint();int[] colors={Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW};for(int i=0;i<4;i++){paint.setColor(colors[i]);int x=i%2*150,y=i/2*300;canvas.drawRect(x,y,x+150,y+300,paint);}try(FileOutputStream out=new FileOutputStream(store.file(image))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        JSONObject p=new JSONObject().put("name","Crop regression").put("output","studio-vertical-source.mp4").put("resolution",480).put("aspect_ratio","9:16").put("aspect_mode","fit").put("clips",new JSONArray().put(new JSONObject().put("path",image).put("duration_ms",2000))).put("audio",new JSONArray());
        try{
            p=create(p);engine("video_editor_export",new JSONObject().put("replace",true));awaitExport();
            JSONObject c=new JSONObject().put("path","studio-vertical-source.mp4").put("duration_ms",2000).put("crop",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",.5).put("degrees",90).put("flip_horizontal",true).put("flip_vertical",false));
            p.put("clips",new JSONArray().put(c)).put("aspect_ratio","source").put("output","studio-crop-proof.mp4");p=create(p);
            CountDownLatch latch=new CountDownLatch(1);AtomicReference<File> previewFile=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();StudioRenderPreview[] preview={null};JSONObject snapshot=new JSONObject(p.toString());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{preview[0]=new StudioRenderPreview(app);preview[0].start(snapshot,new StudioRenderPreview.Result(){public void ready(File f){previewFile.set(f);latch.countDown();}public void failed(String e){error.set(e);latch.countDown();}});});
            assertTrue(latch.await(120,TimeUnit.SECONDS));assertNull(error.get());proof("studio-crop-preview.mp4",previewFile.get());InstrumentationRegistry.getInstrumentation().runOnMainSync(()->preview[0].cancel());
            engine("video_editor_export",new JSONObject().put("replace",true));awaitExport();assertEquals(2000,StudioEdits.total(VideoEditorEngine.get().load(app)));proof("studio-crop-proof.mp4",store.file("studio-crop-proof.mp4"));proof("studio-vertical-source.mp4",store.file("studio-vertical-source.mp4"));
            JSONObject metadata=MediaInspection.info(app,"studio-crop-proof.mp4");assertEquals(480,metadata.getInt("width"));assertTrue(metadata.getInt("height")>500&&metadata.getInt("height")<560);
        }finally{store.file(image).delete();}
    }
    void awaitExport()throws Exception{long end=System.currentTimeMillis()+180000;while(System.currentTimeMillis()<end){String state=VideoEditorEngine.get().status().optString("state");if(!state.equals("running")&&!state.equals("preparing")){assertEquals(VideoEditorEngine.get().status().toString(),"completed",state);return;}Thread.sleep(200);}fail("Export timeout");}
    double tone(File f,int frequency)throws Exception{
        final double[] real={0},imag={0};final long[] count={0};AudioDecoder.read(f,300,1,(data,rate,channels,time)->{long n=0;while(data.remaining()>=channels*2){double sample=0;for(int c=0;c<channels;c++)sample+=data.getShort()/32768d/channels;double t=time/1000000d+n++/(double)rate;real[0]+=sample*Math.cos(2*Math.PI*frequency*t);imag[0]+=sample*Math.sin(2*Math.PI*frequency*t);count[0]++;}},null);return 2*Math.hypot(real[0],imag[0])/count[0];
    }
    void proof(String name,File source)throws Exception{File dir=new File(app.getFilesDir(),"studio-proof");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("proof directory");try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(new File(dir,name))){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}}
    void saveProof(String name,JSONObject json)throws Exception{File tmp=new File(app.getCacheDir(),name);try(OutputStream out=new FileOutputStream(tmp)){out.write(json.toString(2).getBytes("UTF-8"));}proof(name,tmp);tmp.delete();}
}
