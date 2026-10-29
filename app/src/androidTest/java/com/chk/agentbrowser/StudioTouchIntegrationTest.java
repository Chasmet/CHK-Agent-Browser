package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass={androidx.media3.common.util.UnstableApi.class,androidx.media3.common.util.ExperimentalApi.class})
@RunWith(AndroidJUnit4.class)
public final class StudioTouchIntegrationTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context app=inst.getTargetContext();
    private WorkspaceStore store;private VideoEditorActivity activity;private String id,photo,video;private final List<String> files=new ArrayList<>();
    @Before public void setup()throws Exception {
        if(android.os.Build.VERSION.SDK_INT>=29)inst.runOnMainSync(()->HardwareRenderer.setDrawingEnabled(true));
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        store=new WorkspaceStore(app);id="touch-"+UUID.randomUUID();photo=id+".png";video=id+".mp4";files.add(photo);files.add(video);
        Bitmap image=Bitmap.createBitmap(240,320,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(0xff213650);Paint paint=new Paint();paint.setColor(0xffffa640);canvas.drawCircle(120,130,75,paint);paint.setColor(Color.WHITE);paint.setTextSize(30);canvas.drawText("STUDIO",55,260,paint);
        try(OutputStream out=new FileOutputStream(store.file(photo))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        try(InputStream in=inst.getContext().getAssets().open("editor-fixture.mp4");OutputStream out=new FileOutputStream(store.file(video))){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        invoke("video_editor_project_new",new JSONObject());
    }
    @After public void cleanup()throws Exception {
        if(activity!=null)inst.runOnMainSync(activity::finish);
        invoke("video_editor_cancel",new JSONObject());invoke("video_editor_project_new",new JSONObject());
        for(String f:files)store.file(f).delete();
    }
    private JSONObject invoke(String action,JSONObject args)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();AtomicReference<String> value=new AtomicReference<>();
        VideoEditorEngine.get().command(app,action,args,(ok,res)->{success.set(ok);value.set(res);done.countDown();});assertTrue(action,done.await(40,TimeUnit.SECONDS));assertTrue(value.get(),success.get());return new JSONObject(value.get());
    }
    private JSONObject project()throws Exception {
        String output=id+"-out.mp4";files.add(output);JSONArray clips=new JSONArray();
        for(int i=0;i<3;i++)clips.put(new JSONObject().put("path",photo).put("duration_ms",4000).put("text",i==1?"Titre du plan":""));
        return new JSONObject().put("name","Studio tactile").put("output",output).put("resolution",480).put("aspect_ratio","9:16").put("mute_original",true).put("clips",clips)
            .put("audio",new JSONArray().put(new JSONObject().put("path",video).put("duration_ms",2500)));
    }
    private void launch()throws Exception {
        invoke("video_editor_project_save",new JSONObject().put("project",project()));
        activity=(VideoEditorActivity)inst.startActivitySync(new Intent(app,VideoEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(()->field("project")!=null&&activity.getWindow().getDecorView().findViewWithTag("studio_tool_Vitesse")!=null);
        await(()->((androidx.media3.exoplayer.ExoPlayer)field("player")).getPlaybackState()==androidx.media3.common.Player.STATE_READY);
    }
    private Object field(String name)throws Exception {Field f=VideoEditorActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private interface Check {boolean get()throws Exception;}
    private void await(Check check)throws Exception {long until=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<until){AtomicBoolean ok=new AtomicBoolean();inst.runOnMainSync(()->{try{ok.set(check.get());}catch(Exception e){throw new AssertionError(e);}});if(ok.get())return;Thread.sleep(80);}fail("Studio state did not arrive");}
    private void call(String name,Class<?>[] types,Object... args)throws Exception {Method m=VideoEditorActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);inst.runOnMainSync(()->{try{m.invoke(activity,args);}catch(Exception e){throw new AssertionError(e);}});}
    private StudioTimeline timeline()throws Exception {return (StudioTimeline)field("timeline");}
    private void gesture(float x,float y,float endX,float endY)throws Exception {
        StudioTimeline timeline=timeline();long now=SystemClock.uptimeMillis();inst.runOnMainSync(()->{
            for(int i=0;i<4;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==3?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;float t=i/3f;
                MotionEvent e=MotionEvent.obtain(now,now+30*i,action,x+(endX-x)*t,y+(endY-y)*t,0);try{timeline.onTouchEvent(e);}finally{e.recycle();}}
        });
    }
    private void tapTool(String name)throws Exception {inst.runOnMainSync(()->{View v=activity.getWindow().getDecorView().findViewWithTag("studio_tool_"+name);assertNotNull(name,v);if(!v.isShown())activity.getWindow().getDecorView().findViewWithTag("studio_page_Éditer").performClick();v=activity.getWindow().getDecorView().findViewWithTag("studio_tool_"+name);v.performClick();});}
    private void screenshot(String name)throws Exception {
        inst.waitForIdleSync();Thread.sleep(150);Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);
        File f=new File(app.getExternalFilesDir(null),"studio-touch-"+name+".png");try(OutputStream out=new FileOutputStream(f)){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        shell("mkdir -p /data/local/tmp/chk-studio-touch");shell("cp "+f.getAbsolutePath()+" /data/local/tmp/chk-studio-touch/");assertTrue(shell("ls /data/local/tmp/chk-studio-touch").contains(f.getName()));
    }
    private String shell(String command)throws Exception {try(android.os.ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand(command);InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)output.write(b,0,n);return output.toString("UTF-8");}}
    @Test public void scrollAndZoomMoveTheRealPreviewAndAudioTrackCanBeSelected()throws Exception {
        launch();float d=app.getResources().getDisplayMetrics().density;StudioTimeline t=timeline();
        float center=t.getWidth()/2f;gesture(center,15*d,center-110*d,15*d);
        AtomicLong at=new AtomicLong();inst.runOnMainSync(()->at.set(t.playhead()));assertTrue(at.get()>1500);
        await(()->((View)field("previewHint")).getVisibility()==View.GONE);screenshot("01-editor");float old=t.zoomLevel();inst.runOnMainSync(()->activity.getWindow().getDecorView().findViewWithTag("studio_zoom_in").performClick());assertTrue(t.zoomLevel()>old);
        inst.runOnMainSync(()->t.position(1000));gesture(center,125*d,center,125*d);
        assertEquals(0,((Integer)field("selectedAudio")).intValue());assertNotNull(activity.getWindow().getDecorView().findViewWithTag("studio_tool_Volume"));
        screenshot("02-audio-selection");
    }
    @Test public void trimGesturePersistsAndUndoRestoresBothVideoAndSoundtrack()throws Exception {
        launch();StudioTimeline t=timeline();float d=app.getResources().getDisplayMetrics().density;
        inst.runOnMainSync(()->{try{JSONObject p=(JSONObject)field("project");p.put("clips",new JSONArray().put(p.getJSONArray("clips").get(0)));t.setProject(p,0);t.position(0);t.zoomBy(.5f);}catch(Exception e){throw new AssertionError(e);}});
        float edge=t.getWidth()/2f+4*48*d*.5f;gesture(edge-2*d,65*d,edge-50*d,65*d);
        await(()->!((Boolean)field("saving"))&&((JSONObject)field("project")).getJSONArray("clips").getJSONObject(0).getLong("duration_ms")<3500);
        JSONObject saved=invoke("video_editor_project_read",new JSONObject());assertTrue(saved.getJSONArray("clips").getJSONObject(0).getLong("duration_ms")<3500);
        int revision=saved.getInt("revision");JSONObject undo=invoke("video_editor_history",new JSONObject().put("expected_revision",revision));assertEquals(3,undo.getJSONObject("project").getJSONArray("clips").length());
    }
    @Test public void longPressReordersTheActualProject()throws Exception {
        launch();call("seekPreview",new Class<?>[]{long.class},4000L);StudioTimeline t=timeline();float d=app.getResources().getDisplayMetrics().density;float center=t.getWidth()/2f;long now=SystemClock.uptimeMillis();
        inst.runOnMainSync(()->{MotionEvent e=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,center-85*d,65*d,0);t.onTouchEvent(e);e.recycle();});Thread.sleep(550);
        inst.runOnMainSync(()->{long at=SystemClock.uptimeMillis();MotionEvent move=MotionEvent.obtain(now,at,MotionEvent.ACTION_MOVE,center+80*d,65*d,0);MotionEvent up=MotionEvent.obtain(now,at+30,MotionEvent.ACTION_UP,center+80*d,65*d,0);t.onTouchEvent(move);t.onTouchEvent(up);move.recycle();up.recycle();});
        await(()->!((Boolean)field("saving"))&&"Titre du plan".equals(((JSONObject)field("project")).getJSONArray("clips").getJSONObject(0).optString("text")));
        JSONObject saved=invoke("video_editor_project_read",new JSONObject());assertEquals("Titre du plan",saved.getJSONArray("clips").getJSONObject(0).getString("text"));
    }
    @Test public void boundsUseSourceTimeAndPhotoLimitsAndDuplicationIsAdjacent()throws Exception {
        assertEquals(1042,StudioEdits.framePosition(1000,1,24,5000));assertEquals(983,StudioEdits.framePosition(1000,-1,60,5000));assertEquals(0,StudioEdits.framePosition(0,-1,30,5000));
        JSONObject c=new JSONObject().put("path",video).put("start_ms",1000).put("duration_ms",2000).put("speed",2).put("fade_ms",500);
        JSONObject left=StudioEdits.trimmed(c,true,250,5000);assertEquals(1500,left.getLong("start_ms"));assertEquals(1500,left.getLong("duration_ms"));
        assertEquals(500,StudioEdits.trimmed(c,false,-99999,5000).getLong("duration_ms"));assertEquals(166,StudioEdits.trimmed(c,false,-99999,5000).getLong("fade_ms"));assertEquals(4000,StudioEdits.trimmed(c,false,99999,5000).getLong("duration_ms"));
        JSONObject p=new JSONObject().put("path",photo).put("duration_ms",4000);assertEquals(60000,StudioEdits.trimmed(p,false,999999,60000).getLong("duration_ms"));assertEquals(1000,StudioEdits.trimmed(p,true,999999,60000).getLong("duration_ms"));
        JSONObject project=project();project.getJSONArray("clips").getJSONObject(0).put("text","Original");StudioEdits.duplicate(project,0);
        assertEquals("Original",project.getJSONArray("clips").getJSONObject(1).getString("text"));project.getJSONArray("clips").getJSONObject(1).put("text","Copie");assertEquals("Original",project.getJSONArray("clips").getJSONObject(0).getString("text"));
        StudioEdits.clipVolume(project,project.getJSONArray("clips").getJSONObject(1),.5);assertTrue(project.getJSONArray("clips").getJSONObject(0).getBoolean("mute"));assertFalse(project.getJSONArray("clips").getJSONObject(1).getBoolean("mute"));assertTrue(project.getJSONArray("clips").getJSONObject(2).getBoolean("mute"));
        project.put("clips",new JSONArray().put(new JSONObject(p.toString()).put("duration_ms",1000)));StudioEdits.fitAudio(project);assertEquals(1000,project.getJSONArray("audio").getJSONObject(0).getInt("duration_ms"));
    }
    @Test public void batchFromWorkspaceDoesNotCopyMediaAndKeepsOrder()throws Exception {
        launch();call("addWorkspaceBatch",new Class<?>[]{String.class,List.class},"video",Arrays.asList(photo,photo));
        await(()->!((Boolean)field("saving"))&&!((Boolean)field("importing"))&&((JSONObject)field("project")).getJSONArray("clips").length()==5);
        JSONObject p=invoke("video_editor_project_read",new JSONObject());assertEquals(5,p.getJSONArray("clips").length());assertEquals(photo,p.getJSONArray("clips").getJSONObject(4).getString("path"));assertTrue(store.file(photo).exists());
    }
    @Test public void audioBatchAddsIndependentOverlappingTracksWithoutChangingExistingAudio()throws Exception {
        launch();JSONObject before=invoke("video_editor_project_read",new JSONObject());
        call("addWorkspaceBatch",new Class<?>[]{String.class,List.class},"audio",Arrays.asList(video,video,video,video,video));
        await(()->!((Boolean)field("importing"))&&!((Boolean)field("saving")));
        JSONObject after=invoke("video_editor_project_read",new JSONObject());assertTrue(after.getInt("revision")>before.getInt("revision"));assertEquals(6,after.getJSONArray("audio").length());assertEquals(before.getJSONArray("audio").getJSONObject(0).toString(),after.getJSONArray("audio").getJSONObject(0).toString());java.util.HashSet<String> tracks=new java.util.HashSet<>();for(int i=1;i<6;i++){JSONObject item=after.getJSONArray("audio").getJSONObject(i);assertEquals(0,item.getLong("offset_ms"));assertTrue(tracks.add(item.getString("track_id")));assertTrue(item.getLong("duration_ms")<=StudioEdits.total(after));}
    }
    @Test public void volumeProcessorScalesRealPcmAndMcpPersistsTheSameSetting()throws Exception {
        androidx.media3.common.audio.AudioProcessor processor=VideoEditorEngine.volumeProcessors(new JSONObject().put("volume",.25)).get(0);
        processor.configure(new androidx.media3.common.audio.AudioProcessor.AudioFormat(48000,2,androidx.media3.common.C.ENCODING_PCM_16BIT));processor.flush();
        ByteBuffer input=ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());input.putShort((short)12000).putShort((short)-12000).putShort((short)4000).putShort((short)-4000).flip();processor.queueInput(input);
        ByteBuffer out=processor.getOutput().order(ByteOrder.nativeOrder());assertEquals(3000,out.getShort());assertEquals(-3000,out.getShort());assertEquals(1000,out.getShort());assertEquals(-1000,out.getShort());processor.reset();
        JSONObject saved=invoke("video_editor_project_save",new JSONObject().put("project",project())).getJSONObject("project");
        JSONObject updated=invoke("video_editor_timeline",new JSONObject().put("expected_revision",saved.getInt("revision")).put("operations",new JSONArray().put(new JSONObject().put("op","update").put("index",0).put("values",new JSONObject().put("volume",.25))))).getJSONObject("project");assertEquals(.25,updated.getJSONArray("clips").getJSONObject(0).getDouble("volume"),.001);
    }
    @Test public void speedPanelIsScrollableAndCancellationDoesNotChangeProject()throws Exception {
        launch();JSONObject before=invoke("video_editor_project_read",new JSONObject());tapTool("Vitesse");screenshot("03-speed-panel");
        long until=SystemClock.elapsedRealtime()+10000;boolean dismissed=false;
        while(SystemClock.elapsedRealtime()<until){android.view.accessibility.AccessibilityNodeInfo root=inst.getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("Vitesse du plan").isEmpty()&&!root.findAccessibilityNodeInfosByText("Annuler").isEmpty()){root.findAccessibilityNodeInfosByText("Annuler").get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);dismissed=true;break;}Thread.sleep(80);}
        assertTrue("Speed panel and cancel button must be available",dismissed);Thread.sleep(200);

        assertEquals(before.getInt("revision"),invoke("video_editor_project_read",new JSONObject()).getInt("revision"));
    }
    private double rms(String path)throws Exception {
        android.media.MediaExtractor extractor=new android.media.MediaExtractor();android.media.MediaCodec decoder=null;
        try{extractor.setDataSource(store.file(path).getAbsolutePath());int track=-1;android.media.MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).startsWith("audio/")){track=i;format=extractor.getTrackFormat(i);break;}
            assertTrue("Audio track required",track>=0);extractor.selectTrack(track);decoder=android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME));decoder.configure(format,null,null,0);decoder.start();
            boolean inputEnded=false,ended=false;double energy=0;long samples=0;android.media.MediaCodec.BufferInfo info=new android.media.MediaCodec.BufferInfo();long until=SystemClock.elapsedRealtime()+15000;
            while(!ended&&SystemClock.elapsedRealtime()<until){
                if(!inputEnded){int n=decoder.dequeueInputBuffer(1000);if(n>=0){ByteBuffer buffer=decoder.getInputBuffer(n);int count=extractor.readSampleData(buffer,0);long time=extractor.getSampleTime();if(count<0||time>=1000000){decoder.queueInputBuffer(n,0,0,0,android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true;}else{decoder.queueInputBuffer(n,0,count,time,0);extractor.advance();}}}
                int n=decoder.dequeueOutputBuffer(info,1000);if(n>=0){ByteBuffer buffer=decoder.getOutputBuffer(n).order(ByteOrder.nativeOrder());buffer.position(info.offset);buffer.limit(info.offset+info.size);while(buffer.remaining()>=2){double v=buffer.getShort()/32768.0;energy+=v*v;samples++;}ended=(info.flags&android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(n,false);}
            }
            assertTrue("Decoded PCM required",samples>1000);return Math.sqrt(energy/samples);
        }finally{if(decoder!=null){decoder.stop();decoder.release();}extractor.release();}
    }
    @Test public void reducedVolumeCompositionProducesARealMp4()throws Exception {
        JSONObject p=project();p.put("clips",new JSONArray().put(new JSONObject().put("path",video).put("duration_ms",1000).put("volume",.25))).put("audio",new JSONArray()).put("mute_original",false);
        invoke("video_editor_project_save",new JSONObject().put("project",p));invoke("video_editor_export",new JSONObject());
        long until=SystemClock.elapsedRealtime()+120000;JSONObject state;
        do{Thread.sleep(200);state=invoke("video_editor_status",new JSONObject());if(state.getString("state").equals("failed"))fail(state.toString());}while(!state.getString("state").equals("completed")&&SystemClock.elapsedRealtime()<until);
        assertEquals(state.toString(),"completed",state.getString("state"));JSONObject proof=invoke("video_editor_verify_output",new JSONObject());assertTrue(proof.toString(),proof.getBoolean("valid"));assertTrue(proof.getBoolean("has_audio"));
        double source=rms(video),rendered=rms(p.getString("output"));assertTrue("Source must contain sound",source>.001);assertTrue("Actual MP4 amplitude must be attenuated: "+rendered/source,rendered/source>.12&&rendered/source<.40);
    }
}
