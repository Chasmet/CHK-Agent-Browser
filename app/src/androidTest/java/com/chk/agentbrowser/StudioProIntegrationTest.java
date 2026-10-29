package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real Local3 dispatch, MediaCodec speech decode, Transformer export and persisted spatial edits. */
@RunWith(AndroidJUnit4.class)
public final class StudioProIntegrationTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context app=inst.getTargetContext();
    WorkspaceStore store;String prefix,video,music,fx;JSONObject project;boolean oldEnabled,oldAuto,oldPreview,oldLocal;final List<String> files=new ArrayList<>();
    static final class Reply {boolean ok;String text;JSONObject json()throws Exception{assertTrue(text,ok);return new JSONObject(text);}}
    @Before public void setup()throws Exception{
        if(android.os.Build.VERSION.SDK_INT>=29)inst.runOnMainSync(()->HardwareRenderer.setDrawingEnabled(true));
        SharedPreferences p=app.getSharedPreferences("browser_mcp_v1",0),local=app.getSharedPreferences("chk_local3_v1",0);oldEnabled=p.getBoolean("enabled",false);oldAuto=p.getBoolean("autonomous_mode",false);oldPreview=p.getBoolean("preview_allowed",false);oldLocal=local.getBoolean("enabled",false);
        p.edit().putBoolean("enabled",false).putBoolean("autonomous_mode",true).putBoolean("preview_allowed",true).commit();local.edit().putBoolean("enabled",true).commit();app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        store=new WorkspaceStore(app);prefix="pro-"+UUID.randomUUID();video=asset("voice-video.mp4");music=asset("music.wav");fx=asset("fx.wav");proof("ATTRIBUTION.txt",store.file(asset("ATTRIBUTION.txt")));String output=prefix+"-mix.mp4";files.add(output);
        JSONObject p0=new JSONObject().put("name","Public speech fixture").put("output",output).put("resolution",480).put("fps",30).put("aspect_ratio","9:16").put("aspect_mode","fit").put("mute_original",false).put("audio_mix_version",2)
            .put("clips",new JSONArray().put(new JSONObject().put("path",video).put("start_ms",0).put("duration_ms",8000).put("volume",1)))
            .put("audio",new JSONArray().put(new JSONObject().put("path",music).put("track_id","music").put("start_ms",0).put("offset_ms",0).put("duration_ms",8000).put("volume",.15)).put(new JSONObject().put("path",fx).put("track_id","fx").put("start_ms",0).put("offset_ms",0).put("duration_ms",8000).put("volume",.35)))
            .put("audio_tracks",new JSONObject().put("original",new JSONObject().put("role","voice")).put("music",new JSONObject().put("role","music")).put("fx",new JSONObject().put("role","fx")));
        project=call("video_editor_project_create",new JSONObject().put("project",p0)).json().getJSONObject("project");
    }
    @After public void cleanup()throws Exception{
        call("video_editor_cancel",new JSONObject());call("video_editor_project_create",new JSONObject());for(String path:files)store.file(path).delete();
        app.getSharedPreferences("chk_local3_v1",0).edit().putBoolean("enabled",oldLocal).commit();app.getSharedPreferences("browser_mcp_v1",0).edit().putBoolean("enabled",oldEnabled).putBoolean("autonomous_mode",oldAuto).putBoolean("preview_allowed",oldPreview).commit();
    }
    private String asset(String name)throws Exception{String path=prefix+"-"+name;files.add(path);try(InputStream in=inst.getContext().getAssets().open("pro-studio/"+name);OutputStream out=new FileOutputStream(store.file(path))){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return path;}
    private Reply call(String action,JSONObject args)throws Exception{Reply result=new Reply();CountDownLatch done=new CountDownLatch(1);if(action.startsWith("studio_")){args=new JSONObject(args.toString()).put("tool","studio").put("operation",action);action="desktop";}AgentClient.get(app).executeLocal3(action,args,(ok,text)->{result.ok=ok;result.text=text;done.countDown();});assertTrue("Local3 command timeout: "+action,done.await(180,TimeUnit.SECONDS));return result;}
    private JSONObject read()throws Exception{return project=call("video_editor_project_read",new JSONObject()).json();}
    private JSONObject request()throws Exception{return new JSONObject().put("project_id",project.getString("project_id")).put("expected_revision",project.getInt("revision")).put("request_id","pro_"+UUID.randomUUID().toString().replace("-",""));}
    private String clip()throws Exception{return project.getJSONArray("clips").getJSONObject(0).getString("clip_id");}
    private JSONObject change(String action,JSONObject args)throws Exception{JSONObject out=call(action,args).json();read();return out;}
    private JSONObject export(String request)throws Exception{
        JSONObject args=request().put("request_id",request);JSONObject first=call("studio_audio_mix",args).json();assertTrue(first.has("jobs"));call("studio_audio_mix",args).json();long until=SystemClock.elapsedRealtime()+180000;JSONObject job=null;
        while(SystemClock.elapsedRealtime()<until){JSONArray jobs=call("studio_audio_mix",new JSONObject().put("mode","status")).json().getJSONArray("jobs");int matches=0;for(int i=0;i<jobs.length();i++)if(request.equals(jobs.getJSONObject(i).optString("request_id"))){job=jobs.getJSONObject(i);matches++;}assertEquals("No duplicate export job",1,matches);if(job!=null&&job.optString("state").equals("completed")){read();return job;}if(job!=null&&job.optString("state").equals("failed"))fail(job.toString());Thread.sleep(200);}throw new AssertionError("Export did not finish: "+job);
    }
    private void proof(String name,File source)throws Exception{File folder=new File(app.getExternalFilesDir(null),"studio-pro");assertTrue(folder.isDirectory()||folder.mkdirs());try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(new File(folder,name))){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
    private void proof(String name,JSONObject data)throws Exception{File folder=new File(app.getExternalFilesDir(null),"studio-pro");assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name))){out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));}}

    @Test public void realSpeechAndWavMusicAreMixedThroughLocal3WithDuckingAndAutomation()throws Exception{
        JSONObject tracks=call("studio_audio_tracks",new JSONObject().put("project_id",project.getString("project_id"))).json();assertEquals(3,tracks.getJSONArray("clips").length());
        JSONObject analysis=call("studio_audio_analyze",new JSONObject().put("project_id",project.getString("project_id")).put("clip_id",clip()).put("recognize_voice",true).put("language","en")).json();assertTrue("Real speech must be recognized",analysis.getJSONArray("voice_regions").length()>0);
        JSONArray regions=analysis.getJSONArray("voice_regions");for(int i=0;i<regions.length();i++){assertTrue(regions.getJSONObject(i).getLong("start_ms")>=800);assertTrue(regions.getJSONObject(i).getLong("end_ms")<=7300);}
        change("studio_audio_ducking",request().put("enabled",true).put("voice_regions",regions).put("reduction_db",-12).put("threshold_db",-60).put("attack_ms",80).put("release_ms",250));
        String musicId=project.getJSONArray("audio").getJSONObject(0).getString("clip_id");
        change("studio_audio_keyframes",request().put("clip_id",musicId).put("mode","replace").put("keyframes",new JSONArray().put(new JSONObject().put("time_ms",0).put("volume",.15)).put(new JSONObject().put("time_ms",4000).put("volume",.15)).put(new JSONObject().put("time_ms",6000).put("volume",.08)).put(new JSONObject().put("time_ms",8000).put("volume",.08))));
        JSONObject job=export("audio_"+prefix.replace("-",""));assertTrue(job.getJSONObject("verification").getBoolean("has_audio"));JSONObject verify=call("studio_audio_verify",new JSONObject().put("project_id",project.getString("project_id")).put("start_ms",2000)).json();assertTrue(verify.toString(),verify.getBoolean("verified"));assertEquals(0,verify.getJSONObject("audio").getLong("clipped_samples"));
        assertEquals(1,project.getJSONArray("clips").getJSONObject(0).getDouble("volume"),0);assertFalse(project.getBoolean("mute_original"));
        proof("audio-mix.mp4",store.file(project.getString("output")));proof("audio-project.json",project);proof("audio-verification.json",verify);proof("reference-mix.wav",StudioPcmMix.render(app,project,null,null));proof("voice-source.mp4",store.file(video));proof("music.wav",store.file(music));proof("fx.wav",store.file(fx));
    }
    @Test public void duplicateRequestsConflictsMuteSoloAndSilenceRemainTargeted()throws Exception{
        String musicId=project.getJSONArray("audio").getJSONObject(0).getString("clip_id"),voice=clip();JSONObject args=request().put("clip_id",musicId).put("percent",15.25);JSONObject first=change("studio_audio_set_volume",args);int revision=project.getInt("revision");JSONObject repeated=call("studio_audio_set_volume",args).json();assertTrue(repeated.getBoolean("request_replayed"));assertEquals(revision,read().getInt("revision"));assertFalse(call("studio_audio_set_volume",new JSONObject(args.toString()).put("percent",50)).ok);
        assertFalse(call("studio_audio_set_volume",request().put("expected_revision",revision-1).put("clip_id",voice).put("percent",0)).ok);assertEquals(1,read().getJSONArray("clips").getJSONObject(0).optDouble("volume",1),0);
        change("studio_audio_set_volume",request().put("track_id","music").put("percent",200).put("solo",true));assertFalse(StudioMix.audible(project,StudioMix.sources(project).get(0)));assertTrue(StudioMix.audible(project,StudioMix.sources(project).get(1)));
        change("studio_audio_set_volume",request().put("track_id","music").put("percent",0).put("solo",false));assertTrue(StudioMix.audible(project,StudioMix.sources(project).get(0)));assertEquals(Double.NEGATIVE_INFINITY,StudioMix.db(0),0);assertEquals(2,StudioMix.linear(new JSONObject().put("db",20*Math.log10(2))),1e-10);
        JSONObject untouched=project.getJSONArray("clips").getJSONObject(0);assertFalse(untouched.optBoolean("mute"));assertEquals(2,project.getJSONArray("audio").length());
    }
    @Test public void cropCommandsPersistAllRatiosAndRenderTheActualGeometryWithoutChangingDuration()throws Exception{
        String id=clip();for(String ratio:new String[]{"9:16","16:9","1:1","4:5","free"}){
            change("studio_crop_set",request().put("clip_id",id).put("reference","display_oriented_source").put("rectangle",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)).put("ratio",ratio));JSONObject c=project.getJSONArray("clips").getJSONObject(0).getJSONObject("crop");if(!ratio.equals("free"))assertEquals(VideoEditorEngine.ratio(ratio),c.getDouble("width")*180/(c.getDouble("height")*320),.0001);
        }
        change("studio_crop_set",request().put("clip_id",id).put("reference","display_oriented_source").put("rectangle",new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5)).put("zoom",1.1).put("pan_x",.1).put("pan_y",-.1));
        change("studio_crop_move",request().put("clip_id",id).put("dx",.02).put("dy",.02));change("studio_crop_resize",request().put("clip_id",id).put("width",.6).put("height",.6));change("studio_crop_rotate",request().put("clip_id",id).put("degrees",90));change("studio_crop_flip",request().put("clip_id",id).put("horizontal",true).put("vertical",false));change("studio_crop_apply",request().put("clip_id",id));
        String saved=project.getJSONArray("clips").getJSONObject(0).getJSONObject("crop").toString();JSONObject reopened=call("video_editor_project_open",new JSONObject().put("id",project.getString("project_id"))).json().getJSONObject("project");project=reopened;assertEquals(saved,project.getJSONArray("clips").getJSONObject(0).getJSONObject("crop").toString());assertEquals(8000,project.getJSONArray("clips").getJSONObject(0).getLong("duration_ms"));
        JSONObject preview=call("studio_crop_preview",new JSONObject().put("project_id",project.getString("project_id")).put("clip_id",id).put("time_ms",4000)).json();assertTrue(store.file(preview.getString("preview_path")).length()>100);files.add(preview.getString("preview_path"));
        export("crop_"+prefix.replace("-",""));proof("crop.mp4",store.file(project.getString("output")));proof("crop-preview.png",store.file(preview.getString("preview_path")));proof("crop-project.json",project);proof("crop-preview.json",preview);
    }
    @Test public void fourCornerHandlesMoveAndTheSourceLimitsHold()throws Exception{
        JSONObject crop=StudioCrop.reset();AtomicReference<StudioCropSheet.CropView> view=new AtomicReference<>();Bitmap image=Bitmap.createBitmap(180,320,Bitmap.Config.ARGB_8888);image.eraseColor(Color.RED);
        inst.runOnMainSync(()->{StudioCropSheet.CropView v=new StudioCropSheet.CropView(app,image,crop,180,320);view.set(v);v.layout(0,0,360,640);Bitmap canvas=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888);v.draw(new Canvas(canvas));canvas.recycle();});
        for(int handle=0;handle<4;handle++){final int corner=handle;inst.runOnMainSync(()->{try{crop.put("x",.1).put("y",.1).put("width",.8).put("height",.8);StudioCropSheet.CropView v=view.get();float x=v.bounds.left+(float)(corner%2==0?.1:.9)*v.bounds.width(),y=v.bounds.top+(float)(corner<2?.1:.9)*v.bounds.height();long t=SystemClock.uptimeMillis();for(int event=0;event<3;event++){MotionEvent e=MotionEvent.obtain(t,t+event*20,event==0?MotionEvent.ACTION_DOWN:event==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,x+(event==0?0:corner%2==0?20:-20),y+(event==0?0:corner<2?20:-20),0);v.onTouchEvent(e);e.recycle();}}catch(Exception e){throw new RuntimeException(e);}});assertTrue(crop.getDouble("width")<.8);assertTrue(crop.getDouble("height")<.8);StudioCrop.validate(new JSONObject().put("crop",crop));}image.recycle();
    }
    @Test public void highGainsAreLimitedInTheActualPcmAndSourcesStayIntact()throws Exception{
        String sourceHash=WorkspaceTransfer.hash(store.file(music));for(StudioMix.Source s:StudioMix.sources(project))s.clip.put("volume",2);File mixed=StudioPcmMix.render(app,project,null,null);JSONObject levels=StudioAudioAnalysis.levels(mixed,0,8000,null);assertEquals(0,levels.getLong("clipped_samples"));assertTrue(levels.getDouble("peak_dbfs")<=-.95);assertEquals(sourceHash,WorkspaceTransfer.hash(store.file(music)));JSONObject receipt=StudioAutomation.read(new File(mixed+".json"));assertTrue(receipt.getLong("limited_frames")>0);proof("limiter.wav",mixed);proof("limiter.json",receipt);
    }
}
