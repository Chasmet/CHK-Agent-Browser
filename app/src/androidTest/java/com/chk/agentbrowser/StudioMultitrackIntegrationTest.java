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
    @Test public void receiptsSurviveUndoAndWholeProjectSaveButNotProjectDuplication()throws Exception{
        JSONObject p=create(p("receipt-history-"+UUID.randomUUID()+".mp4"));String original=p.getString("project_id"),id=p.getJSONArray("audio").getJSONObject(0).getString("studio_id");
        JSONObject a=args(p,"audio_set_volume").put("clip_id",id).put("percent",25);StudioMcp.dispatch(app,a);
        JSONObject undone=StudioProjectLibrary.history(app,false);assertEquals(15,undone.getJSONArray("audio").getJSONObject(0).getDouble("volume")*100,.001);
        assertTrue(StudioMcp.dispatch(app,a).getBoolean("replayed"));assertEquals(undone.getInt("revision"),VideoEditorEngine.get().load(app).getInt("revision"));
        JSONObject legacy=new JSONObject(undone.toString());legacy.remove("studio_receipts");legacy.put("revision",undone.getInt("revision")+1);StudioProjectLibrary.save(app,legacy);assertTrue(StudioMcp.dispatch(app,a).getBoolean("replayed"));
        JSONObject copy=StudioProjectLibrary.duplicate(app,original);assertNotEquals(original,copy.getString("project_id"));assertFalse(copy.has("studio_receipts"));
        JSONObject changed=new JSONObject(a.toString()).put("project_id",copy.getString("project_id")).put("expected_revision",copy.getInt("revision")).put("percent",35);
        assertEquals(35,StudioMcp.dispatch(app,changed).getDouble("volume_percent"),.001);
        StudioProjectLibrary.open(app,original);assertTrue(StudioMcp.dispatch(app,a).getBoolean("replayed"));
    }
    @Test public void timelineUsesStableClipIdsAcrossReordering()throws Exception{
        JSONObject p=p("stable-timeline-"+UUID.randomUUID()+".mp4").put("audio",new JSONArray()).put("clips",new JSONArray().put(new JSONObject().put("path",video).put("duration_ms",1000)).put(new JSONObject().put("path",video).put("start_ms",1000).put("duration_ms",1000)));
        p=create(p);String first=p.getJSONArray("clips").getJSONObject(0).getString("studio_id"),second=p.getJSONArray("clips").getJSONObject(1).getString("studio_id");
        JSONObject result=engine("video_editor_timeline",new JSONObject().put("expected_revision",p.getInt("revision")).put("operations",new JSONArray().put(new JSONObject().put("op","move").put("track","clips").put("clip_id",first).put("to",1)).put(new JSONObject().put("op","update").put("track","clips").put("clip_id",first).put("values",new JSONObject().put("volume",.25))))).getJSONObject("project");
        assertEquals(first,result.getJSONArray("clips").getJSONObject(1).getString("studio_id"));assertEquals(second,result.getJSONArray("clips").getJSONObject(0).getString("studio_id"));assertEquals(.25,result.getJSONArray("clips").getJSONObject(1).getDouble("volume"),.0001);assertEquals(1,result.getJSONArray("clips").getJSONObject(0).optDouble("volume",1),.0001);
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
            JSONObject metadata=MediaInspection.info(app,"studio-crop-proof.mp4");assertEquals(480,metadata.getInt("width"));assertTrue(metadata.toString(),metadata.getInt("height")>500&&metadata.getInt("height")<560);
        }finally{store.file(image).delete();}
    }
    @Test public void sourceAspectUsesZeroWhenClipRotationIsAbsent()throws Exception{
        JSONObject p=p("aspect-"+UUID.randomUUID()+".mp4");JSONObject info=MediaInspection.info(app,video);
        double expected=info.getDouble("width")/info.getDouble("height");if(Math.abs(info.optInt("rotation"))%180==90)expected=1/expected;
        assertEquals(expected,VideoEditorEngine.get().outputAspect(app,p),.0001);
        JSONObject c=p.getJSONArray("clips").getJSONObject(0);c.put("crop",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",.5).put("degrees",90));
        assertEquals(.5/expected,VideoEditorEngine.get().outputAspect(app,p),.0001);
    }
    @Test public void realHumanVoiceSurvivesMusicAndVoskDucking()throws Exception{
        String speech="human-"+UUID.randomUUID()+".wav";
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("vosk-human-speech.wav");OutputStream out=new FileOutputStream(store.file(speech))){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        try{
            JSONObject source=p("studio-human-source.mp4").put("mute_original",true).put("clips",new JSONArray().put(new JSONObject().put("path",video).put("duration_ms",3000)).put(new JSONObject().put("path",video).put("duration_ms",3000)).put(new JSONObject().put("path",video).put("duration_ms",2000)))
                .put("audio",new JSONArray().put(new JSONObject().put("path",speech).put("duration_ms",8000)));
            create(source);engine("video_editor_export",new JSONObject().put("replace",true));awaitExport();
            proof("studio-human-source.mp4",store.file("studio-human-source.mp4"));
            saveProof("studio-human-source-info.json",new JSONObject().put("metadata",MediaInspection.info(app,"studio-human-source.mp4")).put("decoded",StudioAudioAnalysis.measure(app,"studio-human-source.mp4",0,8000,null)));
            JSONObject p=new JSONObject(source.toString()).put("name","Human speech and ducking").put("speech_language","en").put("output","studio-human-mixed.mp4").put("mute_original",false)
                .put("clips",new JSONArray().put(new JSONObject().put("path","studio-human-source.mp4").put("duration_ms",8000).put("volume",1)))
                .put("audio",new JSONArray().put(new JSONObject().put("path",music).put("duration_ms",3000).put("volume",.15)).put(new JSONObject().put("path",music).put("duration_ms",3000).put("volume",.15)).put(new JSONObject().put("path",music).put("duration_ms",2000).put("volume",.15)));
            p=create(p);StudioAudio.enable(p);StudioAudio.ids(p);StudioProjectLibrary.save(app,p);
            StudioAudioMixer.Render baseline=StudioAudioMixer.render(app,p,null,n->{});proof("studio-human-baseline.wav",baseline.wav);
            StudioAudio.track(p,"audio").put("ducking",new JSONObject().put("enabled",true).put("voice_track_id","original").put("reduction_db",-18).put("threshold_dbfs",-55).put("attack_ms",60).put("release_ms",100));
            p.put("revision",p.getInt("revision")+1);StudioProjectLibrary.save(app,p);
            StudioAudioMixer.Render rendered=StudioAudioMixer.render(app,p,null,n->{});JSONArray voice=rendered.proof.getJSONObject("voice_regions").getJSONArray("original");assertTrue("Actual Vosk recognition must find spoken words",voice.length()>3);
            assertEquals("vosk_words_and_pcm_threshold",rendered.proof.getString("speech_engine"));assertEquals(4,rendered.proof.getJSONArray("contributions").length());
            proof("studio-human-mixed.wav",rendered.wav);saveProof("studio-human-proof.json",rendered.proof);proof("studio-human-source.mp4",store.file("studio-human-source.mp4"));
            engine("video_editor_export",new JSONObject().put("replace",true));awaitExport();proof("studio-human-mixed.mp4",store.file("studio-human-mixed.mp4"));
            JSONObject humanAnalysis=StudioAudioAnalysis.measure(app,"studio-human-mixed.mp4",0,8000,null);assertFalse(humanAnalysis.getBoolean("sample_clipping_detected"));assertTrue(humanAnalysis.getBoolean("lufs_measured"));saveProof("studio-human-native-analysis.json",humanAnalysis);
        }finally{store.file(speech).delete();}
    }
    @Test public void loudnessReportsKnownSineAndDigitalSilence()throws Exception{
        StudioLoudness mono=new StudioLoudness(48000,1),stereo=new StudioLoudness(48000,2),silence=new StudioLoudness(48000,1);
        for(int i=0;i<48000*3;i++){double v=.1*Math.sin(2*Math.PI*1000*i/48000);mono.frame(mono.channel(0,v));stereo.frame(stereo.channel(0,v)+stereo.channel(1,v));silence.frame(silence.channel(0,0));}
        assertEquals(-23.0,((Number)mono.integrated()).doubleValue(),.2);assertEquals(3.0103,((Number)stereo.integrated()).doubleValue()-((Number)mono.integrated()).doubleValue(),.01);assertEquals("-Infinity",silence.integrated());
    }
    void awaitExport()throws Exception{long end=System.currentTimeMillis()+180000;while(System.currentTimeMillis()<end){String state=VideoEditorEngine.get().status().optString("state");if(!state.equals("running")&&!state.equals("preparing")){assertEquals(VideoEditorEngine.get().status().toString(),"completed",state);return;}Thread.sleep(200);}fail("Export timeout");}
    double tone(File f,int frequency)throws Exception{
        final double[] real={0},imag={0};final long[] count={0};AudioDecoder.read(f,300,1,(data,rate,channels,time)->{long n=0;while(data.remaining()>=channels*2){double sample=0;for(int c=0;c<channels;c++)sample+=data.getShort()/32768d/channels;double t=time/1000000d+n++/(double)rate;real[0]+=sample*Math.cos(2*Math.PI*frequency*t);imag[0]+=sample*Math.sin(2*Math.PI*frequency*t);count[0]++;}},null);return 2*Math.hypot(real[0],imag[0])/count[0];
    }
    void proof(String name,File source)throws Exception{File dir=new File(app.getFilesDir(),"studio-proof");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("proof directory");try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(new File(dir,name))){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}}
    void saveProof(String name,JSONObject json)throws Exception{File tmp=new File(app.getCacheDir(),name);try(OutputStream out=new FileOutputStream(tmp)){out.write(json.toString(2).getBytes("UTF-8"));}proof(name,tmp);tmp.delete();}
}
