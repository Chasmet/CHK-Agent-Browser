package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass={androidx.media3.common.util.UnstableApi.class,androidx.media3.common.util.ExperimentalApi.class})
@RunWith(AndroidJUnit4.class)
public final class StudioLayersIntegrationTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();private final Context app=inst.getTargetContext();
    private WorkspaceStore store;private String id,blue,red,video;private final List<String> files=new ArrayList<>();private VideoEditorActivity activity;
    @Before public void setup()throws Exception {
        if(android.os.Build.VERSION.SDK_INT>=29)inst.runOnMainSync(()->HardwareRenderer.setDrawingEnabled(true));
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();store=new WorkspaceStore(app);id="layers-"+UUID.randomUUID();
        blue=image("blue",0xff0000ff);red=image("red",0xffff0000);video=id+"-video.mp4";files.add(video);
        try(InputStream in=inst.getContext().getAssets().open("editor-fixture.mp4");OutputStream out=new FileOutputStream(store.file(video))){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    }
    @After public void cleanup()throws Exception {if(activity!=null)inst.runOnMainSync(activity::finish);invoke("video_editor_cancel",new JSONObject());invoke("video_editor_project_new",new JSONObject());for(String p:files)store.file(p).delete();}
    private String image(String name,int color)throws Exception {String path=id+"-"+name+".png";files.add(path);Bitmap b=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);b.eraseColor(color);try(OutputStream out=new FileOutputStream(store.file(path))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();return path;}
    private JSONObject invoke(String action,JSONObject args)throws Exception {CountDownLatch done=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> value=new AtomicReference<>();VideoEditorEngine.get().command(app,action,args,(success,text)->{ok.set(success);value.set(text);done.countDown();});assertTrue(action,done.await(40,TimeUnit.SECONDS));assertTrue(value.get(),ok.get());return new JSONObject(value.get());}
    private JSONObject layer(String path,long offset,long duration)throws Exception {return new JSONObject().put("path",path).put("offset_ms",offset).put("start_ms",0).put("duration_ms",duration).put("scale",.5).put("x",0).put("y",0).put("opacity",1);}
    private JSONObject project(String suffix)throws Exception {String output=id+suffix+".mp4";files.add(output);return new JSONObject().put("name","Calques vérifiés").put("output",output).put("resolution",480).put("fps",30).put("aspect_ratio","1:1").put("mute_original",true).put("clips",new JSONArray().put(new JSONObject().put("path",blue).put("duration_ms",3000))).put("audio",new JSONArray());}
    private void export(JSONObject p)throws Exception {invoke("video_editor_export",new JSONObject().put("project",p));long until=SystemClock.elapsedRealtime()+180000;String state="";while(SystemClock.elapsedRealtime()<until){JSONObject s=invoke("video_editor_status",new JSONObject());state=s.getString("state");if(state.equals("completed"))return;if(state.equals("failed"))fail(s.toString());Thread.sleep(200);}fail("Export timeout: "+state);}
    private int pixel(String path,long at,int x,int y)throws Exception {MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(store.file(path).getAbsolutePath());Bitmap b=r.getFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST);assertNotNull(b);int color=b.getPixel(b.getWidth()*x/100,b.getHeight()*y/100);b.recycle();return color;}finally{r.release();}}
    private void blue(int color){assertTrue("Expected blue: "+Integer.toHexString(color),Color.blue(color)>170&&Color.red(color)<70);}
    @Test public void timedPhotoLayerIsVisibleOnlyInsideItsRange()throws Exception {
        JSONObject p=project("-photo").put("layers",new JSONArray().put(layer(red,1000,1000).put("x",.5).put("y",-.5))).put("aspect_ratio","9:16").put("aspect_mode","crop");export(p);String path=p.getString("output");
        blue(pixel(path,500,75,25));int center=pixel(path,1500,75,25);assertTrue(Integer.toHexString(center),Color.red(center)>180&&Color.blue(center)<70);blue(pixel(path,1500,10,10));blue(pixel(path,2500,75,25));
        assertTrue(invoke("video_editor_verify_output",new JSONObject()).getBoolean("valid"));
    }
    @Test public void opacityIsReallyBlendedInMp4()throws Exception {
        JSONObject p=project("-alpha").put("layers",new JSONArray().put(layer(red,0,3000).put("opacity",.5)));export(p);int color=pixel(p.getString("output"),1500,50,50);assertTrue(Integer.toHexString(color),Color.red(color)>80&&Color.red(color)<175&&Color.blue(color)>80&&Color.blue(color)<175);
    }
    @Test public void transparentPngPreservesTheBackground()throws Exception {
        String path=id+"-transparent.png";files.add(path);Bitmap bitmap=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint paint=new Paint();paint.setColor(Color.RED);canvas.drawRect(24,24,136,136,paint);try(OutputStream out=new FileOutputStream(store.file(path))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        JSONObject p=project("-transparent").put("layers",new JSONArray().put(layer(path,0,3000)));export(p);String output=p.getString("output");assertTrue(Color.red(pixel(output,1500,50,50))>180);blue(pixel(output,1500,27,50));
    }
    @Test public void threeLayersKeepTheirStackingOrderAndIndependentTiming()throws Exception {
        String green=image("green",Color.GREEN),yellow=image("yellow",Color.YELLOW);
        JSONObject p=project("-stack").put("layers",new JSONArray().put(layer(red,1000,1000)).put(layer(green,0,3000).put("scale",.75)).put(layer(yellow,0,3000).put("scale",.2).put("x",.8).put("y",.8)));export(p);String path=p.getString("output");
        int before=pixel(path,500,50,50),during=pixel(path,1500,50,50),after=pixel(path,2500,50,50),corner=pixel(path,1500,90,90);
        assertTrue("Green second layer before top layer",Color.green(before)>170&&Color.red(before)<70);assertTrue("First layer stays on top",Color.red(during)>180&&Color.green(during)<70);assertTrue("Green second layer restored after top layer",Color.green(after)>170&&Color.red(after)<70);assertTrue("Third layer keeps its own position",Color.red(corner)>170&&Color.green(corner)>170&&Color.blue(corner)<70);
    }
    @Test public void videoLayerHasSourceFramesAndDisappearsAfterEnd()throws Exception {
        JSONObject p=project("-video-pip").put("layers",new JSONArray().put(layer(video,500,1000)));export(p);String output=p.getString("output");blue(pixel(output,200,50,50));int c=pixel(output,1000,50,50);assertTrue("Video PiP must replace the blue center",Color.red(c)>70||Color.green(c)>70||Color.blue(c)<100);blue(pixel(output,2200,50,50));
    }
    @Test public void invalidLayersNeverOverwriteSourcesAndMcpKeepsTheirHashes()throws Exception {
        JSONObject p=project("-model").put("layers",new JSONArray().put(layer(red,1000,1000)));long length=store.file(video).length();JSONObject invalid=new JSONObject(p.toString()).put("layers",new JSONArray().put(layer(video,0,1000))).put("output",video);
        try{VideoEditorEngine.get().validate(app,invalid);fail("Source overwrite");}catch(IllegalArgumentException expected){}assertEquals(length,store.file(video).length());
        invalid=new JSONObject(p.toString());invalid.getJSONArray("layers").getJSONObject(0).put("offset_ms",2500);try{VideoEditorEngine.get().validate(app,invalid);fail("Layer exceeds end");}catch(IllegalArgumentException expected){}
        JSONObject saved=invoke("video_editor_project_save",new JSONObject().put("project",p)).getJSONObject("project");JSONObject changed=invoke("video_editor_timeline",new JSONObject().put("expected_revision",saved.getInt("revision")).put("operations",new JSONArray().put(new JSONObject().put("op","update").put("track","layers").put("index",0).put("values",new JSONObject().put("opacity",.5))))).getJSONObject("project");assertEquals(.5,changed.getJSONArray("layers").getJSONObject(0).getDouble("opacity"),.001);
        Method fingerprints=StudioAutomation.class.getDeclaredMethod("fingerprints",Context.class,JSONObject.class);fingerprints.setAccessible(true);JSONObject hashes=(JSONObject)fingerprints.invoke(null,app,changed);assertTrue("Layer source must be protected in queue snapshots",hashes.has(red));
    }
    @Test public void renderedPreviewContainsLayersAndDoesNotCreateFinalExport()throws Exception {
        JSONObject p=project("-preview").put("layers",new JSONArray().put(layer(red,0,3000)));CountDownLatch done=new CountDownLatch(1);AtomicReference<File> file=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();StudioRenderPreview[] preview=new StudioRenderPreview[1];
        inst.runOnMainSync(()->{preview[0]=new StudioRenderPreview(app);preview[0].start(p,new StudioRenderPreview.Result(){public void ready(File f){file.set(f);done.countDown();}public void failed(String value){error.set(value);done.countDown();}});});assertTrue(done.await(120,TimeUnit.SECONDS));assertNull(error.get());assertNotNull(file.get());assertFalse(store.file(p.getString("output")).exists());
        MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.get().getAbsolutePath());Bitmap b=r.getFrameAtTime(1500000,MediaMetadataRetriever.OPTION_CLOSEST);assertNotNull(b);assertTrue(Color.red(b.getPixel(b.getWidth()/2,b.getHeight()/2))>180);b.recycle();}finally{r.release();}inst.runOnMainSync(preview[0]::cancel);assertFalse(file.get().exists());assertTrue(store.file(red).exists());
    }
    @Test public void visibleEditorHasLayerControlsAndRenderedMode()throws Exception {
        JSONObject p=project("-ui").put("layers",new JSONArray().put(layer(red,0,3000)));invoke("video_editor_project_save",new JSONObject().put("project",p));activity=(VideoEditorActivity)inst.startActivitySync(new Intent(app,VideoEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long until=SystemClock.elapsedRealtime()+12000;Field projectField=VideoEditorActivity.class.getDeclaredField("project");projectField.setAccessible(true);while(projectField.get(activity)==null&&SystemClock.elapsedRealtime()<until)Thread.sleep(80);
        inst.runOnMainSync(()->{android.view.View page=activity.getWindow().getDecorView().findViewWithTag("studio_page_Calques");assertNotNull(page);page.performClick();assertTrue(activity.getWindow().getDecorView().findViewWithTag("studio_tool_Superposer").isShown());activity.getWindow().getDecorView().findViewWithTag("studio_render_preview").performClick();});
        Field mode=VideoEditorActivity.class.getDeclaredField("renderedMode");mode.setAccessible(true);until=SystemClock.elapsedRealtime()+120000;boolean rendered=false;while(SystemClock.elapsedRealtime()<until){AtomicBoolean state=new AtomicBoolean();inst.runOnMainSync(()->{try{state.set(mode.getBoolean(activity));}catch(Exception e){throw new AssertionError(e);}});if(state.get()){rendered=true;break;}Thread.sleep(200);}assertTrue("Rendered mode must become active",rendered);assertFalse(store.file(p.getString("output")).exists());
        screenshot();
        inst.runOnMainSync(()->{activity.getWindow().getDecorView().findViewWithTag("studio_tools_back").performClick();activity.getWindow().getDecorView().findViewWithTag("studio_page_Éditer").performClick();activity.getWindow().getDecorView().findViewWithTag("studio_tool_Vitesse").performClick();});
        until=SystemClock.elapsedRealtime()+15000;boolean panel=false;while(SystemClock.elapsedRealtime()<until){android.view.accessibility.AccessibilityNodeInfo root=inst.getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("Vitesse du plan").isEmpty()&&!root.findAccessibilityNodeInfosByText("Annuler").isEmpty()){panel=true;root.findAccessibilityNodeInfosByText("Annuler").get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);break;}Thread.sleep(100);}assertTrue("Editing must return to source before the speed panel",panel);
    }
    private void screenshot()throws Exception {inst.waitForIdleSync();Thread.sleep(500);Bitmap b=inst.getUiAutomation().takeScreenshot();File f=new File(app.getExternalFilesDir(null),"studio-layers.png");try(OutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();for(String command:new String[]{"mkdir -p /data/local/tmp/chk-studio-layers","cp "+f.getAbsolutePath()+" /data/local/tmp/chk-studio-layers/"})try(android.os.ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand(command);InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}}
}
