package com.chk.agentbrowser;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real picker gestures, category parity, large directories and a genuine WebView selection. */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceImportPickerTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrument.getTargetContext();
    private final String id="picker-"+UUID.randomUUID();
    private WorkspaceStore store;
    private MainActivity activity;
    private WorkspaceMediaChooser picker;
    private String fixture;
    private final AtomicReference<Uri[]> result=new AtomicReference<>();
    private final AtomicInteger callbacks=new AtomicInteger(),androidCalls=new AtomicInteger();

    private void setup()throws Exception {
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        store=new WorkspaceStore(app);
        String base=WorkspaceCategoryView.destination(store,"image","");
        fixture=base+"/"+id;store.mkdir(fixture);
    }
    private void launch()throws Exception {
        activity=(MainActivity)instrument.startActivitySync(new Intent(app,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    private void cleanup(){
        instrument.runOnMainSync(()->{if(picker!=null)picker.cancel();if(activity!=null)activity.finish();});
        if(store!=null&&fixture!=null)try{remove(store.file(fixture));}catch(Exception ignored){}
    }
    private void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    private String png(String name)throws Exception {
        String path=fixture+"/"+name;
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xffcc8822);
        try(FileOutputStream out=new FileOutputStream(store.file(path))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        return path;
    }
    private WebChromeClient.FileChooserParams params(String[] types,boolean multi){return new WebChromeClient.FileChooserParams(){
        public int getMode(){return multi?MODE_OPEN_MULTIPLE:MODE_OPEN;}
        public String[] getAcceptTypes(){return types;}
        public boolean isCaptureEnabled(){return false;}
        public CharSequence getTitle(){return "Importer";}
        public String getFilenameHint(){return "";}
        public Intent createIntent(){return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*");}
    };}
    private void show(String[] types,boolean multi)throws Exception {
        launch();instrument.runOnMainSync(()->{try{picker=WorkspaceMediaChooser.show(activity,params(types,multi),
            uris->{result.set(uris);callbacks.incrementAndGet();},()->androidCalls.incrementAndGet());}catch(Exception e){throw new AssertionError(e);}});
        waitText("import_status","dossier(s)");
    }
    private Dialog dialog()throws Exception {Field f=WorkspaceMediaChooser.class.getDeclaredField("dialog");f.setAccessible(true);return (Dialog)f.get(picker);}
    private View find(String tag)throws Exception {return dialog().getWindow().getDecorView().findViewWithTag(tag);}
    private void click(String tag)throws Exception {
        long deadline=System.currentTimeMillis()+12000;
        while(System.currentTimeMillis()<deadline){AtomicReference<View> found=new AtomicReference<>();instrument.runOnMainSync(()->{try{found.set(find(tag));}catch(Exception e){throw new AssertionError(e);}});
            if(found.get()!=null){instrument.runOnMainSync(()->found.get().performClick());return;}Thread.sleep(60);}
        fail("View not found: "+tag);
    }
    private void waitText(String tag,String text)throws Exception {
        long deadline=System.currentTimeMillis()+15000;AtomicReference<String> actual=new AtomicReference<>("");
        while(System.currentTimeMillis()<deadline){instrument.runOnMainSync(()->{try{View v=find(tag);if(v instanceof android.widget.TextView)actual.set(((android.widget.TextView)v).getText().toString());}catch(Exception e){throw new AssertionError(e);}});
            if(actual.get().contains(text))return;Thread.sleep(70);}
        fail("Expected "+text+" in "+actual.get());
    }
    private void search(String text)throws Exception {instrument.runOnMainSync(()->{try{((EditText)find("import_search")).setText(text);}catch(Exception e){throw new AssertionError(e);}});}
    private void screenshot(String name)throws Exception {
        instrument.waitForIdleSync();android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();
        assertNotNull(image);File dir=new File(app.getExternalFilesDir(null),"picker-screens");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    private void waitCallbacks(int value)throws Exception {long deadline=System.currentTimeMillis()+10000;while(callbacks.get()!=value&&System.currentTimeMillis()<deadline)Thread.sleep(50);assertEquals(value,callbacks.get());}
    private boolean contains(JSONArray rows,String path){for(int i=0;i<rows.length();i++)if(path.equals(rows.optJSONObject(i).optString("path")))return true;return false;}

    private void tapFileInput(WebView web)throws Exception {
            CountDownLatch positionDone=new CountDownLatch(1);AtomicReference<String> position=new AtomicReference<>();
            instrument.runOnMainSync(()->web.evaluateJavascript("JSON.stringify((()=>{const r=document.querySelector('#media').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth,height:innerHeight};})())",raw->{position.set(raw);positionDone.countDown();}));
            assertTrue(positionDone.await(5,TimeUnit.SECONDS));JSONObject target=new JSONObject(new JSONArray("["+position.get()+"]").getString(0));
            instrument.runOnMainSync(()->{try{
                float x=(float)(target.getDouble("x")*web.getWidth()/target.getDouble("width"));
                float y=(float)(target.getDouble("y")*web.getHeight()/target.getDouble("height"));
                long now=android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
                android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_UP,x,y,0);
                down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try{web.dispatchTouchEvent(down);web.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
            }catch(Exception e){throw new AssertionError(e);}});
    }
    @Test public void categoryMatchesFilesAndDoesNotLoseEntriesAfterFirstPage()throws Exception {
        setup();try{
            for(int i=0;i<76;i++)png(String.format(java.util.Locale.ROOT,"photo_%03d.png",i));
            store.writeText(fixture+"/META_A_COPIER.txt","métadonnées",false);
            List<String> types=WorkspaceImportIndex.types(new String[]{"image/png"});
            JSONObject home=WorkspaceImportIndex.query(app,"image","","","name",types,true);
            assertTrue(contains(home.getJSONArray("items"),fixture));
            JSONObject files=WorkspaceCategoryView.query(app,"","image","","name",0,5000);
            assertTrue(contains(files.getJSONArray("items"),fixture));
            JSONArray all=WorkspaceImportIndex.query(app,"image",fixture,"","name",types,true).getJSONArray("items");
            assertEquals(77,all.length());assertTrue(contains(all,fixture+"/photo_075.png"));
            assertEquals(76,WorkspaceImportIndex.query(app,"image",fixture,"","name",types,false).getJSONArray("items").length());
            assertEquals(1,WorkspaceImportIndex.query(app,"image",fixture,"","name",types,false).getInt("hidden"));
            JSONObject found=WorkspaceImportIndex.query(app,"all","","photo_075","name",types,true);
            assertTrue(contains(found.getJSONArray("items"),fixture+"/photo_075.png"));
            assertEquals("Images/"+id,WorkspaceImportIndex.visiblePath(fixture));
            assertEquals(50,WorkspaceCategoryView.query(app,fixture,"image","","name",0).getJSONArray("items").length());
        }finally{cleanup();}
    }
    @Test public void acceptTokensHandleCommasEmptyEntriesMimeAndExtensions()throws Exception {
        setup();try{
            List<String> types=WorkspaceImportIndex.types(new String[]{" image/png, .JPG , ","",null});
            assertTrue(WorkspaceImportIndex.accepts(store,"Photo.PNG",types));
            assertTrue(WorkspaceImportIndex.accepts(store,"Photo.JPG",types));
            assertFalse(WorkspaceImportIndex.accepts(store,"Film.mp4",types));
            assertEquals("image",WorkspaceImportIndex.initialCategory(store,types));
            assertTrue(WorkspaceImportIndex.accepts(store,"film.MP4",WorkspaceImportIndex.types(new String[]{"image/*,video/*"})));
            assertTrue(WorkspaceImportIndex.accepts(store,"unknown.bin",WorkspaceImportIndex.types(new String[]{"",null})));
            assertFalse(WorkspaceImportIndex.accepts(store,"secret.txt",WorkspaceImportIndex.types(new String[]{",image/png,"})));
        }finally{cleanup();}
    }
    @Test public void multipleSelectionSurvivesSearchAndOnlyConfirmsExplicitly()throws Exception {
        setup();try{
            String first=png("Premier.png"),second=png("Second.png");
            show(new String[]{"image/*"},true);click("import_file:"+fixture);
            screenshot("01-folders-and-grid");
            click("import_file:"+first);assertEquals(0,callbacks.get());
            search("Second");click("import_file:"+second);waitText("import_selection","2 sélectionné(s)");
            screenshot("02-selection-and-search");
            click("import_view");screenshot("03-list-selection");click("import_confirm");waitCallbacks(1);
            assertEquals(2,result.get().length);
            assertEquals(store.uri(first),result.get()[0]);assertEquals(store.uri(second),result.get()[1]);
            for(Uri uri:result.get())try(java.io.InputStream input=app.getContentResolver().openInputStream(uri)){assertEquals(137,input.read());}
            instrument.runOnMainSync(picker::cancel);assertEquals(1,callbacks.get());
        }finally{cleanup();}
    }
    @Test public void deletedSelectionReportsFailureWithoutFalseSuccess()throws Exception {
        setup();try{
            String path=png("Disparu.png");show(new String[]{"image/png"},false);click("import_file:"+fixture);click("import_file:"+path);
            assertTrue(store.file(path).delete());click("import_confirm");waitText("import_status","Fichier déplacé");
            assertEquals(0,callbacks.get());click("import_cancel");waitCallbacks(1);assertNull(result.get());
        }finally{cleanup();}
    }
    @Test public void androidHandoffAndCancellationAreDeliveredOnlyOnce()throws Exception {
        setup();try{
            show(new String[]{"*/*"},false);click("import_android");
            assertEquals(1,androidCalls.get());assertEquals(0,callbacks.get());
            instrument.runOnMainSync(picker::cancel);assertEquals(0,callbacks.get());
            instrument.runOnMainSync(()->{try{picker=WorkspaceMediaChooser.show(activity,params(new String[]{"*/*"},false),uris->callbacks.incrementAndGet(),()->{});}catch(Exception e){throw new AssertionError(e);}});
            click("import_cancel");instrument.runOnMainSync(picker::cancel);assertEquals(1,callbacks.get());
        }finally{cleanup();}
    }
    @Test public void selectedPrivatePhotoActuallyReachesWebsiteInput()throws Exception {
        setup();try{
            String path=png("Import_reel.png");launch();Field current=MainActivity.class.getDeclaredField("current");current.setAccessible(true);WebView web=(WebView)current.get(activity);
            WebFixture.load(instrument,web,"https://example.com/picker-test","<meta name='viewport' content='width=device-width,initial-scale=1'><input id='media' type='file' accept='image/png' style='display:block;width:240px;height:80px;margin:30px'><output id='proof'></output><script>media.onchange=async()=>{const f=media.files[0];const b=new Uint8Array(await f.arrayBuffer());proof.textContent=f.name+':'+b[0]+':'+b.length;};</script>","!!document.querySelector('#media')");
            tapFileInput(web);
            Field active=MainActivity.class.getDeclaredField("mediaChooser");active.setAccessible(true);
            long deadline=System.currentTimeMillis()+12000;
            while(picker==null&&System.currentTimeMillis()<deadline){picker=(WorkspaceMediaChooser)active.get(activity);Thread.sleep(50);}
            assertNotNull(picker);click("import_file:"+fixture);click("import_file:"+path);click("import_confirm");
            AtomicReference<String> proof=new AtomicReference<>("");deadline=System.currentTimeMillis()+15000;
            while(System.currentTimeMillis()<deadline){CountDownLatch done=new CountDownLatch(1);instrument.runOnMainSync(()->web.evaluateJavascript("document.querySelector('#proof').textContent",raw->{proof.set(raw);done.countDown();}));assertTrue(done.await(5,TimeUnit.SECONDS));if(proof.get().contains("Import_reel.png:137:"))break;Thread.sleep(80);}
            assertTrue(proof.get(),proof.get().contains("Import_reel.png:137:"));
            WorkspaceMediaChooser completed=picker;tapFileInput(web);deadline=System.currentTimeMillis()+10000;
            while(System.currentTimeMillis()<deadline){WorkspaceMediaChooser next=(WorkspaceMediaChooser)active.get(activity);if(next!=completed&&next!=null){picker=next;break;}Thread.sleep(50);}
            assertNotSame("A new chooser must be opened",completed,picker);
            WebFixture.load(instrument,web,"https://example.com/new-document","<body id='new-page'>Other page</body>","!!document.querySelector('#new-page')");
            AtomicReference<Boolean> showing=new AtomicReference<>(true);
            instrument.runOnMainSync(()->{try{showing.set(dialog().isShowing());}catch(Exception e){throw new AssertionError(e);}});
            assertFalse("Navigation cancels the stale chooser",showing.get());
        }finally{cleanup();}
    }
}
