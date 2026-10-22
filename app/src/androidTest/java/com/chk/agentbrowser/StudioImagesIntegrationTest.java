package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public final class StudioImagesIntegrationTest {
    private Context app;private WorkspaceStore store;private String prefix;private final ArrayList<String> files=new ArrayList<>();
    @Before public void setup()throws Exception{
        if(android.os.Build.VERSION.SDK_INT>=29)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->HardwareRenderer.setDrawingEnabled(true));
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();store=new WorkspaceStore(app);prefix="studio-image-"+UUID.randomUUID();
    }
    @After public void cleanup()throws Exception{invoke("video_editor_queue_pause",new JSONObject());invoke("video_editor_cancel",new JSONObject());invoke("video_editor_project_new",new JSONObject());for(String path:files)store.file(path).delete();}
    private JSONObject invoke(String action,JSONObject args)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> value=new AtomicReference<>();
        VideoEditorEngine.get().command(app,action,args,(success,res)->{ok.set(success);value.set(res);done.countDown();});
        assertTrue("Timeout "+action,done.await(30,TimeUnit.SECONDS));assertTrue(action+": "+value.get(),ok.get());return new JSONObject(value.get());
    }
    private String image(String extension,Bitmap.CompressFormat format)throws Exception{
        String path=prefix+"."+extension;files.add(path);Bitmap b=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(b);canvas.drawColor(Color.rgb(120,150,190));Paint p=new Paint();p.setColor(Color.WHITE);canvas.drawRect(20,20,90,90,p);
        try(OutputStream out=new FileOutputStream(store.file(path))){assertTrue(b.compress(format,95,out));}finally{b.recycle();}return path;
    }
    private JSONObject project(String path,String suffix)throws Exception{
        String output=prefix+suffix+".mp4";files.add(output);
        return new JSONObject().put("name","Image native "+suffix).put("output",output).put("aspect_ratio","4:3").put("resolution",480).put("fps",24).put("mute_original",true)
            .put("clips",new JSONArray().put(new JSONObject().put("path",path).put("duration_ms",1000).put("zoom_end",1.1))).put("audio",new JSONArray());
    }
    private void awaitRender()throws Exception{for(int i=0;i<240;i++){JSONObject status=invoke("video_editor_status",new JSONObject());String state=status.getString("state");if(state.equals("completed"))return;if(state.equals("failed"))fail(status.toString());Thread.sleep(500);}fail("MP4 export timed out");}
    @Test public void pngJpegWebpProduceDecodedMp4Frames()throws Exception{
        String[] extensions={"png","jpg","webp"};Bitmap.CompressFormat[] formats={Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.WEBP};
        for(int i=0;i<extensions.length;i++){
            String path=image(extensions[i],formats[i]);JSONObject metadata=MediaInspection.info(app,path);assertTrue(metadata.getBoolean("image"));assertEquals(160,metadata.getInt("width"));
            JSONObject p=project(path,"-"+extensions[i]);invoke("video_editor_export",new JSONObject().put("project",p));awaitRender();
            JSONObject verified=invoke("video_editor_verify_output",new JSONObject().put("project",p));assertTrue(verified.toString(),verified.getBoolean("valid"));assertEquals(1,verified.getInt("verified_video_segments"));
        }
    }
    @Test public void timelineIsAtomicRevisionGuardedAndHistorySurvivesReload()throws Exception{
        String path=image("png",Bitmap.CompressFormat.PNG);JSONObject p=project(path,"-timeline");
        JSONObject imported=invoke("video_editor_project_import",new JSONObject().put("project",p)).getJSONObject("project");
        int rev=imported.getInt("revision");JSONObject edited=invoke("video_editor_timeline",new JSONObject().put("expected_revision",rev).put("operations",new JSONArray()
            .put(new JSONObject().put("op","update").put("index",0).put("values",new JSONObject().put("duration_ms",3000))) )).getJSONObject("project");
        assertEquals(3000,edited.getJSONArray("clips").getJSONObject(0).getInt("duration_ms"));
        JSONObject restored=invoke("video_editor_history",new JSONObject().put("expected_revision",edited.getInt("revision"))).getJSONObject("project");assertEquals(1000,restored.getJSONArray("clips").getJSONObject(0).getInt("duration_ms"));
        JSONObject redone=invoke("video_editor_history",new JSONObject().put("expected_revision",restored.getInt("revision")).put("redo",true)).getJSONObject("project");assertEquals(3000,redone.getJSONArray("clips").getJSONObject(0).getInt("duration_ms"));
        JSONObject invalid=new JSONObject(redone.toString());invalid.getJSONArray("clips").getJSONObject(0).put("duration_ms",61000);
        try{VideoEditorEngine.get().validate(app,invalid);fail("A photo must stay below 60 seconds");}catch(IllegalArgumentException expected){}
        assertEquals(redone.getInt("revision"),invoke("video_editor_project_read",new JSONObject()).getInt("revision"));
    }
    @Test public void photoSequenceKeepsMusicAndFilters()throws Exception{
        String path=image("png",Bitmap.CompressFormat.PNG),music=prefix+"-music.mp4";files.add(music);
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("editor-fixture.mp4");OutputStream out=new FileOutputStream(store.file(music))){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        JSONObject p=project(path,"-music");JSONArray cuts=new JSONArray();for(int i=0;i<3;i++)cuts.put(new JSONObject().put("path",path).put("duration_ms",1000).put("filter",i==1?"noir":"cinema").put("fade_ms",100));
        p.put("clips",cuts).put("audio",new JSONArray().put(new JSONObject().put("path",music).put("duration_ms",3000)));
        invoke("video_editor_export",new JSONObject().put("project",p));awaitRender();JSONObject verified=invoke("video_editor_verify_output",new JSONObject().put("project",p));assertTrue(verified.toString(),verified.getBoolean("valid"));assertTrue(verified.getBoolean("has_audio"));assertEquals(3,verified.getInt("verified_video_segments"));
    }
    @Test public void importsOneHundredPhotosAndRejectsInvalidJsonBeforeSwitching()throws Exception{
        String path=image("png",Bitmap.CompressFormat.PNG);JSONObject p=project(path,"-hundred");JSONArray clips=new JSONArray();for(int i=0;i<100;i++)clips.put(new JSONObject().put("path",path).put("duration_ms",1000));p.put("clips",clips);
        JSONObject saved=invoke("video_editor_project_import",new JSONObject().put("project",p)).getJSONObject("project");assertEquals(100,saved.getJSONArray("clips").length());
        JSONObject invalid=new JSONObject(p.toString());invalid.getJSONArray("clips").getJSONObject(0).put("path","missing-photo.png");
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean accepted=new AtomicBoolean(true);VideoEditorEngine.get().command(app,"video_editor_project_import",new JSONObject().put("project",invalid),(ok,value)->{accepted.set(ok);done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));assertFalse(accepted.get());assertEquals(saved.getString("project_id"),invoke("video_editor_project_read",new JSONObject()).getString("project_id"));
        String json=prefix+".json";files.add(json);try(OutputStream out=new FileOutputStream(store.file(json))){out.write(p.toString().getBytes("UTF-8"));}assertEquals(100,invoke("video_editor_project_import",new JSONObject().put("path",json)).getJSONObject("project").getJSONArray("clips").length());
    }
    @Test public void queueExportsFourSnapshotsWithoutSwitchingVisibleProject()throws Exception{
        String path=image("png",Bitmap.CompressFormat.PNG);JSONObject visible=invoke("video_editor_project_read",new JSONObject());
        JSONArray specs=new JSONArray();for(int i=0;i<4;i++)specs.put(new JSONObject().put("project",project(path,"-queue-"+i)));
        JSONObject request=new JSONObject().put("request_id",prefix).put("projects",specs);JSONObject first=invoke("video_editor_queue_add",request);int count=first.getJSONArray("jobs").length();
        assertEquals(count,invoke("video_editor_queue_add",request).getJSONArray("jobs").length());
        int completed=0;for(int i=0;i<480;i++){JSONArray jobs=invoke("video_editor_queue_status",new JSONObject()).getJSONArray("jobs");completed=0;for(int j=0;j<jobs.length();j++){JSONObject job=jobs.getJSONObject(j);if(!prefix.equals(job.optString("request_id")))continue;assertFalse(job.toString(),job.optString("state").equals("failed"));if(job.optString("state").equals("completed")){assertTrue(job.getJSONObject("verification").getBoolean("valid"));completed++;}}if(completed==4)break;Thread.sleep(500);}
        assertEquals(4,completed);JSONObject after=invoke("video_editor_project_read",new JSONObject());assertEquals(visible.getString("project_id"),after.getString("project_id"));assertEquals(visible.getInt("revision"),after.getInt("revision"));
    }
}
