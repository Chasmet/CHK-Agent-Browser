package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Verifies a real WebView can receive a file from CHK's private Fichiers storage. */
@RunWith(AndroidJUnit4.class)
public final class BrowserMediaUploadTest {
    @Test public void uploadFromWorkspaceReachesHtmlFileInput()throws Exception{
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context app=instrumentation.getTargetContext();
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        WorkspaceStore store=new WorkspaceStore(app);
        String path="test-cut-"+UUID.randomUUID()+".png";
        byte[] png=new byte[]{(byte)137,80,78,71,13,10,26,10,1,2,3,4};
        try(FileOutputStream out=new FileOutputStream(store.file(path))){out.write(png);}
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(
            new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            Field f=MainActivity.class.getDeclaredField("current");f.setAccessible(true);
            WebView web=(WebView)f.get(activity);
            String html="<html><body data-page='workspace-upload-fixture'><input id='media' type='file' accept='image/png'>"
                +"<span id='selected'>none</span>"
                +"<script>document.getElementById('media').addEventListener('change',"
                +"function(e){document.getElementById('selected').textContent="
                +"e.target.files[0]&&e.target.files[0].name||'none';});</script></body></html>";
            WebFixture.load(instrumentation,web,"https://example.com/agent-test",html,"!!document.querySelector('#media')");
            // The real HTTPS navigation exercises the destination guard, without network timing.
            instrumentation.runOnMainSync(()->assertEquals("https://example.com/agent-test",web.getUrl()));
            CountDownLatch uploaded=new CountDownLatch(1);
            AtomicBoolean success=new AtomicBoolean();
            AtomicReference<String> result=new AtomicReference<>("");
            BrowserJsUploader.upload(app,web,"#media",new Uri[]{store.uri(path)},(ok,message)->{
                success.set(ok);result.set(message);uploaded.countDown();
            });
            assertTrue("Upload callback timed out",uploaded.await(90,TimeUnit.SECONDS));
            assertTrue(result.get(),success.get());
            CountDownLatch verified=new CountDownLatch(1);
            AtomicReference<String> selected=new AtomicReference<>("");
            instrumentation.runOnMainSync(()->web.evaluateJavascript(
                "JSON.stringify({name:document.getElementById('selected').textContent,"
                +"size:document.querySelector('#media').files[0].size})",
                raw->{selected.set(raw);verified.countDown();}));
            assertTrue(verified.await(10,TimeUnit.SECONDS));
            String decoded=new JSONArray("["+selected.get()+"]").getString(0);
            org.json.JSONObject info=new org.json.JSONObject(decoded);
            assertEquals(path,info.getString("name"));
            assertEquals(png.length,info.getInt("size"));
        }finally{
            instrumentation.runOnMainSync(activity::finish);
            store.file(path).delete();
        }
    }

    /**
     * Regression: large files must travel as a native content URI, not through
     * the 32 MiB JavaScript/base64 path. The sparse fixture avoids media copies.
     */
    @Test public void largeWorkspaceVideoUsesNativeFileChooser()throws Exception{
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context app=instrumentation.getTargetContext();
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        WorkspaceStore store=new WorkspaceStore(app);
        String path="test-native-large-"+UUID.randomUUID()+".mp4";
        long length=34L*1024L*1024L;
        try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(store.file(path),"rw")){
            file.setLength(length);
        }
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(
            new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try{
            Field current=MainActivity.class.getDeclaredField("current");current.setAccessible(true);
            WebView web=(WebView)current.get(activity);
            String html="<html><body><input id='media' type='file' accept='video/mp4' "
                +"style='display:block;width:240px;height:70px;margin:35px'>"
                +"<output id='selected'></output><script>"
                +"document.querySelector('#media').addEventListener('change',function(e){"
                +"var f=e.target.files[0];document.querySelector('#selected').textContent="
                +"f?f.name+':'+f.size:'none';});</script></body></html>";
            WebFixture.load(instrumentation,web,"https://example.com/native-large-video",html,
                "!!document.getElementById('media')");
            CountDownLatch received=new CountDownLatch(1);
            AtomicBoolean success=new AtomicBoolean(false);
            AtomicReference<String> message=new AtomicReference<>("");
            Uri source=store.uri(path);
            instrumentation.runOnMainSync(()->BrowserNativeUploader.upload(
                app,web,"#media",new Uri[]{source},(ok,response)->{
                    success.set(ok);message.set(response);received.countDown();
                }));
            assertTrue("Native upload result timed out",received.await(25,TimeUnit.SECONDS));
            assertTrue("Expected native 34 MiB URI upload: "+message.get(),success.get());
            String after=evaluate(instrumentation,web,
                "JSON.stringify({size:document.querySelector('#media').files[0].size,"
                +"name:document.querySelector('#media').files[0].name})");
            org.json.JSONObject obj=new org.json.JSONObject(new JSONArray("["+after+"]").getString(0));
            assertEquals(length,obj.getLong("size"));
            assertEquals(path,obj.getString("name"));
        }finally{
            instrumentation.runOnMainSync(activity::finish);
            store.file(path).delete();
        }
    }

    @Test public void queuedUploadStopsAfterReloadingTheSameUrl()throws Exception{
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context app=instrumentation.getTargetContext();
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        WorkspaceStore store=new WorkspaceStore(app);
        String path="test-reload-"+UUID.randomUUID()+".txt";
        try(FileOutputStream out=new FileOutputStream(store.file(path))){out.write(new byte[]{1,2,3});}
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(
            new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        CountDownLatch release=new CountDownLatch(1),blocked=new CountDownLatch(1);
        try{
            Field current=MainActivity.class.getDeclaredField("current");current.setAccessible(true);
            WebView web=(WebView)current.get(activity);
            String url="https://example.com/upload-reload";
            loadPage(instrumentation,web,url,"first");
            Field io=BrowserJsUploader.class.getDeclaredField("IO");io.setAccessible(true);
            ((java.util.concurrent.ExecutorService)io.get(null)).execute(()->{
                blocked.countDown();
                try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            assertTrue(blocked.await(5,TimeUnit.SECONDS));
            CountDownLatch uploaded=new CountDownLatch(1);
            AtomicBoolean success=new AtomicBoolean(true);
            AtomicReference<String> message=new AtomicReference<>("");
            Uri source=store.uri(path);
            instrumentation.runOnMainSync(()->BrowserJsUploader.upload(app,web,"#media",new Uri[]{source},(ok,result)->{
                success.set(ok);message.set(result);uploaded.countDown();
            }));
            loadPage(instrumentation,web,url,"second");
            release.countDown();
            assertTrue("Upload callback timed out",uploaded.await(15,TimeUnit.SECONDS));
            assertFalse("An old command must not populate the replacement document",success.get());
            assertTrue(message.get(),message.get().contains("page a changé"));
            assertEquals("0",evaluate(instrumentation,web,"document.querySelector('#media').files.length"));
            assertEquals("false",evaluate(instrumentation,web,"!!window.__chkStage"));
        }finally{
            release.countDown();instrumentation.runOnMainSync(activity::finish);store.file(path).delete();
        }
    }

    private static void loadPage(Instrumentation instrumentation,WebView web,String url,String marker)throws Exception{
        String html="<html><body data-page='"+marker+"'><input id='media' type='file'></body></html>";
        WebFixture.load(instrumentation,web,url,html,"!!document.body&&document.body.getAttribute('data-page')==='"+marker+"'");
    }

    private static String evaluate(Instrumentation instrumentation,WebView web,String script)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>("");
        instrumentation.runOnMainSync(()->web.evaluateJavascript(script,value->{result.set(value);done.countDown();}));
        assertTrue("WebView evaluation timed out",done.await(5,TimeUnit.SECONDS));return result.get();
    }
}
