package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Local 2 dispatcher with Render disabled, Android files and the owner's WebView. */
@RunWith(AndroidJUnit4.class)
public final class Local2SessionIntegrationTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Context app;
    private SharedPreferences render,local,ui,updates;
    private boolean oldRender,oldAuto,oldLocal,oldUpdates;
    private String oldUrl,oldTabs;
    private int oldTab;
    private MainActivity browser;
    private SettingsActivity settings;
    private String folder;

    @Before public void configureLocal2Only(){
        app=instrument.getTargetContext();
        render=app.getSharedPreferences("browser_mcp_v1",0);
        local=app.getSharedPreferences("chk_local2_v1",0);
        ui=app.getSharedPreferences("mobile_ui",0);
        updates=app.getSharedPreferences("update_monitor",0);
        oldRender=render.getBoolean("enabled",false);oldAuto=render.getBoolean("autonomous_mode",false);
        oldLocal=local.getBoolean("enabled",false);oldUrl=local.getString("url","");
        oldTabs=ui.getString("tabs","[]");oldTab=ui.getInt("active_tab",0);
        oldUpdates=updates.getBoolean("enabled",true);
        render.edit().putBoolean("enabled",false).putBoolean("autonomous_mode",true).commit();
        // No transport polling/network is needed to test command dispatch.
        local.edit().putBoolean("enabled",true).putString("url","").commit();
        updates.edit().putBoolean("enabled",false).commit();
        ui.edit().putString("tabs","[\"https://example.com/\"]").putInt("active_tab",0).commit();
    }
    @After public void restore()throws Exception{
        if(settings!=null)instrument.runOnMainSync(settings::finish);
        if(browser!=null)instrument.runOnMainSync(browser::finish);
        instrument.waitForIdleSync();
        app.stopService(new Intent(app,AgentService.class));
        instrument.runOnMainSync(()->Local2Client.get(app).stop());
        if(folder!=null)new WorkspaceStore(app).file(folder).delete();
        render.edit().putBoolean("enabled",oldRender).putBoolean("autonomous_mode",oldAuto).commit();
        local.edit().putBoolean("enabled",oldLocal).putString("url",oldUrl).commit();
        ui.edit().putString("tabs",oldTabs).putInt("active_tab",oldTab).commit();
        updates.edit().putBoolean("enabled",oldUpdates).commit();
    }
    private static final class Outcome {
        final CountDownLatch done=new CountDownLatch(1);
        volatile boolean ok;
        volatile String message;
        Outcome await()throws Exception{assertTrue("Local 2 did not reply",done.await(30,TimeUnit.SECONDS));return this;}
        JSONObject json()throws Exception{assertTrue(message,ok);return new JSONObject(message);}
    }
    private Outcome start(String action,JSONObject args){
        Outcome result=new Outcome();
        AgentClient.get(app).executeLocal2(action,args,(ok,message)->{
            result.ok=ok;result.message=message;result.done.countDown();
        });
        return result;
    }
    private Outcome call(String action,JSONObject args)throws Exception{return start(action,args).await();}
    private Outcome nativeAction(String action,JSONObject args)throws Exception{
        return call("android_action",new JSONObject().put("action",action).put("args",args));
    }
    private WebView current(){
        try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("current");field.setAccessible(true);return (WebView)field.get(browser);}
        catch(Exception error){throw new RuntimeException(error);}
    }
    private void openBrowser()throws Exception{
        browser=(MainActivity)instrument.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        WebFixture.load(instrument,current(),"https://example.com/local2-fixture",
            "<html><body><h1>Real shared Android session</h1><button id='button' onclick=\"this.textContent='Clicked exactly once';this.disabled=true;\">Tap</button><input id='field'><input id='reset' oninput=\"this.value=''\"></body></html>","!!document.getElementById('button')");
    }
    @Test public void filesWorkWithoutRenderOrAnyBrowser()throws Exception{
        folder="Local2Test"+UUID.randomUUID().toString().replace("-","");
        JSONObject result=nativeAction("files_mkdir",new JSONObject().put("path",folder)).json();
        assertEquals("private_phone",result.getString("storage"));
        assertTrue(result.getBoolean("exists"));assertTrue(result.getBoolean("directory"));
        assertTrue(new WorkspaceStore(app).file(folder).isDirectory());
        JSONObject listed=nativeAction("files_list",new JSONObject().put("path","").put("query",folder)).json();
        assertEquals(1,listed.getJSONArray("items").length());
        assertFalse(render.getBoolean("enabled",true));
    }
    @Test public void local2UsesSameTabsWhileSettingsIsInFront()throws Exception{
        openBrowser();WebView original=current();
        JSONObject first=call("browser_read_page",new JSONObject()).json();
        assertTrue(first.getString("text").contains("Real shared Android session"));
        settings=(SettingsActivity)instrument.startActivitySync(new Intent(app,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrument.waitForIdleSync();
        JSONObject status=call("browser_status",new JSONObject()).json();
        assertFalse(status.getBoolean("render_enabled"));assertTrue(status.getBoolean("browser_available"));
        assertFalse(status.getBoolean("visible"));
        JSONObject paused=call("browser_read_page",new JSONObject()).json();
        assertEquals(first.getString("url"),paused.getString("url"));
        assertTrue(paused.getString("text").contains("Real shared Android session"));
        Outcome tabs=call("browser_tabs",new JSONObject());assertTrue(tabs.message,tabs.ok);
        assertEquals(1,new JSONArray(tabs.message).length());
        JSONObject proof=call("browser_open_url",new JSONObject().put("url",first.getString("url"))).json();
        assertTrue(proof.getBoolean("page_loaded"));assertFalse(proof.getBoolean("visible"));
        assertEquals("ready_when_chk_returns",proof.getString("display_state"));
        instrument.runOnMainSync(()->{assertSame(original,current());settings.finish();});settings=null;
        instrument.waitForIdleSync();
        instrument.runOnMainSync(()->assertSame(original,current()));
    }
    @Test public void newAndClosedTabsAreTheRealAndroidTabs()throws Exception{
        openBrowser();
        Outcome opening=start("browser_tabs",new JSONObject().put("operation","new").put("url","https://example.com/"));
        instrument.waitForIdleSync();
        WebFixture.load(instrument,current(),"https://example.com/","<html><body>Second real tab</body></html>","true");
        assertTrue(opening.await().json().getBoolean("page_loaded"));
        Outcome listed=call("browser_tabs",new JSONObject());assertTrue(listed.message,listed.ok);
        JSONArray tabs=new JSONArray(listed.message);assertEquals(2,tabs.length());assertTrue(tabs.getJSONObject(1).getBoolean("active"));
        Outcome closing=call("browser_tabs",new JSONObject().put("operation","close").put("index",1));
        assertTrue(closing.message,closing.ok);
        assertEquals(1,new JSONArray(call("browser_tabs",new JSONObject()).message).length());
    }
    @Test public void navigationHttpErrorNeverBecomesSuccess()throws Exception{
        openBrowser();WebView web=current();String missing="https://example.com/missing-local2";
        java.util.concurrent.atomic.AtomicBoolean receivedError=new java.util.concurrent.atomic.AtomicBoolean();
        instrument.runOnMainSync(()->{
            WebViewClient delegate=web.getWebViewClient();
            web.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    if(missing.equals(request.getUrl().toString()))return new WebResourceResponse("text/html","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream("<html><body>Missing</body></html>".getBytes()));
                    return delegate.shouldInterceptRequest(view,request);
                }
                @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){delegate.onPageStarted(view,url,icon);}
                @Override public void onPageFinished(WebView view,String url){delegate.onPageFinished(view,url);}
                @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){receivedError.set(request.isForMainFrame());delegate.onReceivedHttpError(view,request,response);}
            });
        });
        Outcome result=call("browser_open_url",new JSONObject().put("url",missing));
        assertTrue("Android did not deliver the HTTP error callback: "+result.message,receivedError.get());
        assertFalse(result.message,result.ok);assertTrue(result.message,result.message.contains("404"));
    }
    @Test public void disabledAutonomyAndLocal2AreDiagnosedSeparately()throws Exception{
        render.edit().putBoolean("autonomous_mode",false).commit();
        assertFalse(call("browser_status",new JSONObject()).json().getBoolean("autonomous"));
        Outcome blocked=call("browser_read_page",new JSONObject());
        assertFalse(blocked.ok);assertTrue(blocked.message,blocked.message.startsWith("Mode autonome désactivé"));
        render.edit().putBoolean("enabled",true).putBoolean("autonomous_mode",true).commit();
        local.edit().putBoolean("enabled",false).commit();
        Outcome disabled=nativeAction("workspace_status",new JSONObject());
        assertFalse(disabled.ok);assertTrue(disabled.message,disabled.message.startsWith("Local 2 désactivé"));
    }
    @Test public void closedBrowserHasNoInvisibleFallbackButFilesRemainAvailable()throws Exception{
        openBrowser();instrument.runOnMainSync(browser::finish);instrument.waitForIdleSync();browser=null;
        Outcome tabs=call("browser_tabs",new JSONObject());
        assertFalse(tabs.message,tabs.ok);assertTrue(tabs.message,tabs.message.startsWith("Navigateur Android fermé"));
        assertTrue(nativeAction("workspace_status",new JSONObject()).ok);
    }
    @Test public void durableLocal2FileTaskUsesItsOwnTransport()throws Exception{
        folder="Local2Task"+UUID.randomUUID().toString().replace("-","");
        String id="local2_"+UUID.randomUUID().toString().replace("-","");
        try{
            JSONArray steps=new JSONArray().put(new JSONObject().put("id","folder")
                .put("action","files_mkdir").put("args",new JSONObject().put("path",folder)));
            nativeAction("browser_task_submit",new JSONObject().put("id",id).put("steps",steps)).json();
            JSONObject result=null;
            for(int i=0;i<50;i++){
                result=nativeAction("browser_task_status",new JSONObject().put("id",id)).json();
                if("completed".equals(result.optString("state")))break;
                Thread.sleep(100);
            }
            assertNotNull(result);assertEquals(result.toString(),"completed",result.getString("state"));
            assertTrue(new WorkspaceStore(app).file(folder).isDirectory());
            assertFalse(render.getBoolean("enabled",true));
        }finally{AgentFiles.journal(app,"tasks",id).delete();}
    }
    @Test public void clickRequiresAnObservableResultAndIsNotRepeated()throws Exception{
        openBrowser();
        Outcome missing=call("browser_click",new JSONObject().put("selector","#button"));
        assertFalse(missing.ok);
        assertFalse(call("browser_read_page",new JSONObject()).json().getString("text").contains("Clicked exactly once"));
        JSONObject args=new JSONObject().put("selector","#button").put("expected_text","Clicked exactly once");
        Outcome clicked=call("browser_click",args);assertTrue(clicked.message,clicked.ok);
        Outcome repeated=call("browser_click",args);assertTrue(repeated.message,repeated.ok);
        assertTrue(repeated.message,repeated.message.contains("aucun clic supplémentaire"));
    }
    @Test public void typedValueIsReadBackAndRejectedIfPageRevertsIt()throws Exception{
        openBrowser();
        Outcome normal=call("browser_type",new JSONObject().put("selector","#field").put("text","verified text"));
        assertTrue(normal.message,normal.ok);assertTrue(normal.message,normal.message.startsWith("Saisie confirmée"));
        Outcome reverted=call("browser_type",new JSONObject().put("selector","#reset").put("text","will be reverted"));
        assertFalse(reverted.message,reverted.ok);assertTrue(reverted.message,reverted.message.contains("non confirmée"));
    }
}
