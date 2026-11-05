package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProductionIntegrationTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private JSONObject create()throws Exception {
        return ProductionProject.create(app,new JSONObject().put("name","Production test")
            .put("request_id","production-test-"+UUID.randomUUID()));
    }
    private void cleanup(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)cleanup(child);file.delete();}
    private JSONObject production(JSONObject args)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> value=new AtomicReference<>();
        instrumentation.runOnMainSync(()->ProductionMcp.execute(app,null,args,(success,text)->{ok.set(success);value.set(text);done.countDown();}));
        assertTrue("Production callback",done.await(60,TimeUnit.SECONDS));assertTrue(value.get(),ok.get());return new JSONObject(value.get());
    }
    private JSONObject record(WebView web,String operation,JSONObject args)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> value=new AtomicReference<>();
        AgentClient.ResultCallback callback=(success,text)->{ok.set(success);value.set(text);done.countDown();};
        instrumentation.runOnMainSync(()->{
            if(operation.equals("start"))BrowserRecording.start(app,web,args,callback);
            else BrowserRecording.stop(app,args,callback);
        });
        assertTrue("Recording callback",done.await(15,TimeUnit.SECONDS));assertTrue(value.get(),ok.get());return new JSONObject(value.get());
    }
    @Test public void projectFoldersAndIdempotentAssetsAreDurable()throws Exception {
        JSONObject created=create();String root=created.getString("project_path");WorkspaceStore store=new WorkspaceStore(app);
        try{
            for(String folder:new String[]{"Captures","Documents","Medias","Narrations","Montage","Exports","Publications"})assertTrue(store.file(root+"/"+folder).isDirectory());
            store.writeText(root+"/Documents/source.txt","Archive vérifiée",false);
            JSONObject first=ProductionProject.asset(app,root,root+"/Documents/source.txt","document",null);
            assertEquals(first.getString("sha256"),ProductionProject.asset(app,root,root+"/Documents/source.txt","document",null).getString("sha256"));
            assertEquals(1,ProductionProject.read(app,root).getJSONArray("assets").length());
            assertEquals(0,ProductionProject.status(app,new JSONObject().put("project_path",root)).getInt("missing_assets"));
        }finally{cleanup(store.file(root));}
    }
    @Test public void recordingContainsTheActualPageBeforeAndAfterAChange()throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=26);
        JSONObject created=create();String root=created.getString("project_path");WorkspaceStore store=new WorkspaceStore(app);
        JSONObject args=new JSONObject().put("project_path",root).put("request_id","page-recording").put("max_duration_ms",12000);
        boolean preview=AgentClient.get(app).isPreviewAllowed();AgentClient.get(app).setPreviewAllowed(true);
        AtomicReference<WebView> reference=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{
            if(android.os.Build.VERSION.SDK_INT>=29)android.graphics.HardwareRenderer.setDrawingEnabled(true);
            WebView web=new WebView(app);web.getSettings().setJavaScriptEnabled(true);
            web.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(640,1073741824));
            web.layout(0,0,360,640);reference.set(web);
        });
        WebView web=reference.get();
        try{
            WebFixture.load(instrumentation,web,"https://recording.test/",
                "<html><body style='margin:0;background:#f00000'><div id='ready'>Page réelle</div></body></html>","!!document.querySelector('#ready')");
            String tab=BrowserTabIds.id(web);assertEquals("recording",record(web,"start",args).getString("state"));
            Thread.sleep(2200);
            CountDownLatch changed=new CountDownLatch(1);
            instrumentation.runOnMainSync(()->web.evaluateJavascript("document.body.style.background='#0000f0'",value->changed.countDown()));
            assertTrue(changed.await(5,TimeUnit.SECONDS));Thread.sleep(2200);
            record(web,"stop",args);
            JSONObject status=null;
            for(int i=0;i<60;i++){
                status=BrowserRecording.status(app,args);
                if(status.optString("state").equals("completed")||status.optString("state").equals("failed"))break;
                Thread.sleep(200);
            }
            assertNotNull(status);assertEquals(status.toString(),"completed",status.getString("state"));assertTrue(status.getBoolean("valid"));
            assertEquals(tab,status.getString("tab_id"));assertFalse(status.getBoolean("has_audio"));
            MediaMetadataRetriever probe=new MediaMetadataRetriever();
            try{
                probe.setDataSource(store.file(status.getString("output")).getAbsolutePath());
                Bitmap first=probe.getFrameAtTime(1000000,MediaMetadataRetriever.OPTION_CLOSEST);
                Bitmap second=probe.getFrameAtTime(3300000,MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull(first);assertNotNull(second);
                int red=first.getPixel(first.getWidth()/2,first.getHeight()/2),blue=second.getPixel(second.getWidth()/2,second.getHeight()/2);
                assertTrue("First frame must show the red page",Color.red(red)>150&&Color.blue(red)<80);
                assertTrue("Later frame must show the changed blue page",Color.blue(blue)>150&&Color.red(blue)<80);
                first.recycle();second.recycle();
            }finally{probe.release();}
            instrumentation.runOnMainSync(()->{assertNull(web.getParent());assertEquals(360,web.getWidth());assertEquals(640,web.getHeight());});
        }finally{
            if(BrowserRecording.active())record(web,"stop",args);
            instrumentation.runOnMainSync(()->{BrowserWebState.close(web);web.destroy();});
            AgentClient.get(app).setPreviewAllowed(preview);cleanup(store.file(root));
        }
    }
    @Test public void interruptedMutationIsVerifiedAndNeverReplayedByRecovery()throws Exception {
        JSONObject write=new JSONObject().put("id","write").put("action","files_write_text")
            .put("args",new JSONObject().put("path","example.txt").put("text","test")).put("state","effect_running");
        JSONObject state=new JSONObject().put("steps",new JSONArray().put(write));
        assertFalse(AgentTasks.recoverState(state));assertEquals("uncertain",write.getString("state"));
        write.put("verify",new JSONObject().put("action","files_stat").put("args",new JSONObject().put("path","example.txt"))
            .put("expect",new JSONObject().put("exists",true)));
        assertTrue(AgentTasks.recoverState(state));assertEquals("effect_done",write.getString("state"));
        JSONObject read=new JSONObject().put("id","read").put("action","files_stat").put("args",new JSONObject().put("path","example.txt"))
            .put("state","effect_running");state.put("steps",new JSONArray().put(read));
        assertTrue(AgentTasks.recoverState(state));assertEquals("pending",read.getString("state"));
    }
    @Test public void automaticMontageIsARealEditable1080pStudioProject()throws Exception {
        JSONObject created=create();String root=created.getString("project_path");WorkspaceStore store=new WorkspaceStore(app);
        JSONObject before=VideoEditorEngine.get().load(app);
        try{
            if(android.os.Build.VERSION.SDK_INT>=29)instrumentation.runOnMainSync(()->android.graphics.HardwareRenderer.setDrawingEnabled(true));
            String path=root+"/Medias/archive.png";Bitmap bitmap=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888);
            new Canvas(bitmap).drawColor(Color.rgb(40,80,160));
            try(FileOutputStream out=new FileOutputStream(store.file(path))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
            ProductionProject.asset(app,root,path,"image",null);
            JSONObject request=new JSONObject().put("project_path",root).put("request_id","montage")
                .put("title","Archives").put("credits","Sources publiques");
            JSONObject result=ProductionMontage.assemble(app,request);
            assertEquals(1080,result.getInt("resolution"));assertEquals(3,result.getInt("clips"));
            JSONObject project=AgentFiles.read(store.file(result.getString("montage_path")));
            VideoEditorEngine.get().validate(app,project);assertEquals(1080,project.getInt("resolution"));
            assertEquals(result.getString("studio_project_id"),ProductionMontage.assemble(app,request).getString("studio_project_id"));
            assertTrue(project.getString("output").startsWith(root+"/Exports/"));
            production(new JSONObject().put("operation","export").put("project_path",root).put("request_id","export"));
            boolean completed=false;
            for(int i=0;i<240;i++){
                JSONObject status=production(new JSONObject().put("operation","export_status").put("project_path",root));
                JSONArray jobs=status.getJSONObject("queue").getJSONArray("jobs");
                for(int j=0;j<jobs.length();j++){
                    JSONObject job=jobs.getJSONObject(j);
                    if(!project.getString("output").equals(job.optString("output")))continue;
                    assertFalse(job.toString(),job.optString("state").equals("failed"));
                    completed=job.optString("state").equals("completed");
                }
                if(completed)break;Thread.sleep(500);
            }
            assertTrue("The queued production must render a real MP4",completed);
            JSONObject checked=production(new JSONObject().put("operation","verify").put("project_path",root));
            assertTrue(checked.toString(),checked.getBoolean("valid"));
        }finally{
            CountDownLatch done=new CountDownLatch(1);
            VideoEditorEngine.get().command(app,"video_editor_project_open",new JSONObject().put("id",before.getString("project_id")),(ok,text)->done.countDown());
            done.await(10,TimeUnit.SECONDS);cleanup(store.file(root));
        }
    }
    @Test public void narrationTextAndMusicDuckingHaveBoundedTimings()throws Exception {
        JSONArray segments=ProductionNarration.segments(new JSONObject().put("text",
            "Les archives sont consultées depuis le navigateur. Une voix française décrit les images et conserve les pauses. Cette phrase termine le passage."));
        assertTrue(segments.length()>1);
        for(int i=0;i<segments.length();i++)assertTrue(segments.getJSONObject(i).getString("text").length()<=110);
        JSONArray voices=new JSONArray().put(new JSONObject().put("start_ms",1000).put("end_ms",2000));
        JSONArray keys=ProductionMontage.ducking(voices,4000);
        JSONObject track=StudioAudio.track("music","Musique").put("gain_keyframes",keys);
        assertEquals(1,StudioAudio.gain(track,0,4000),.01);
        assertEquals(.22,StudioAudio.gain(track,1500,4000),.01);
        assertEquals(1,StudioAudio.gain(track,3000,4000),.01);
    }
}
