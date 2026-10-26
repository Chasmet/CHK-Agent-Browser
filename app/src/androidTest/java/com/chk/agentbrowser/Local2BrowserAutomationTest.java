package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.RandomAccessFile;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Local 2 dispatch and WebViews, no personal media, account, or public upload. */
@RunWith(AndroidJUnit4.class)
public final class Local2BrowserAutomationTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Context app;
    private MainActivity browser;
    private SettingsActivity settings;
    private SharedPreferences render,local,ui,updates;
    private boolean oldRender,oldAuto,oldLocal,oldUpdates;
    private String oldUrl,oldTabs;
    private int oldTab;
    private String path,job;
    @Before public void setup(){
        app=instrument.getTargetContext();render=app.getSharedPreferences("browser_mcp_v1",0);
        local=app.getSharedPreferences("chk_local2_v1",0);ui=app.getSharedPreferences("mobile_ui",0);
        updates=app.getSharedPreferences("update_monitor",0);
        oldRender=render.getBoolean("enabled",false);oldAuto=render.getBoolean("autonomous_mode",false);
        oldLocal=local.getBoolean("enabled",false);oldUrl=local.getString("url","");
        oldTabs=ui.getString("tabs","[]");oldTab=ui.getInt("active_tab",0);oldUpdates=updates.getBoolean("enabled",true);
        render.edit().putBoolean("enabled",false).putBoolean("autonomous_mode",true).commit();
        local.edit().putBoolean("enabled",true).putString("url","").commit();
        updates.edit().putBoolean("enabled",false).commit();
        ui.edit().putString("tabs","[\"https://example.com/\"]").putInt("active_tab",0).commit();
        job="web_"+UUID.randomUUID().toString().replace("-","");
    }
    @After public void cleanup()throws Exception{
        if(settings!=null)instrument.runOnMainSync(settings::finish);
        local.edit().putBoolean("enabled",false).commit();
        if(browser!=null)finishBrowser();
        instrument.waitForIdleSync();instrument.runOnMainSync(()->AgentClient.get(app).closeRetainedBrowser());
        app.stopService(new Intent(app,AgentService.class));instrument.runOnMainSync(()->Local2Client.get(app).stop());
        if(path!=null)new WorkspaceStore(app).file(path).delete();
        app.getSharedPreferences("browser_upload_jobs_v1",0).edit().remove("job_"+job).commit();
        java.io.File publication=AgentFiles.journal(app,"publications",job);if(publication.exists())publication.delete();
        render.edit().putBoolean("enabled",oldRender).putBoolean("autonomous_mode",oldAuto).commit();
        local.edit().putBoolean("enabled",oldLocal).putString("url",oldUrl).commit();
        ui.edit().putString("tabs",oldTabs).putInt("active_tab",oldTab).commit();
        updates.edit().putBoolean("enabled",oldUpdates).commit();
    }
    private static final class Result {
        boolean ok;String text;
        JSONObject json()throws Exception{assertTrue(text,ok);return new JSONObject(text);}
    }
    private Result call(String action,JSONObject args)throws Exception{
        Result r=new Result();CountDownLatch done=new CountDownLatch(1);
        AgentClient.get(app).executeLocal2(action,args,(ok,text)->{r.ok=ok;r.text=text;done.countDown();});
        assertTrue("Local 2 callback timed out",done.await(40,TimeUnit.SECONDS));return r;
    }
    private Result nativeCall(String action,JSONObject args)throws Exception{
        return call("android_action",new JSONObject().put("action",action).put("args",args));
    }
    private Result browserCall(String operation,JSONObject args)throws Exception{
        return nativeCall("desktop",new JSONObject(args.toString()).put("tool","browser").put("operation",operation));
    }
    private WebView web()throws Exception{
        java.lang.reflect.Field f=MainActivity.class.getDeclaredField("current");f.setAccessible(true);return (WebView)f.get(browser);
    }
    private WebView open(String host,String html)throws Exception{
        browser=(MainActivity)instrument.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        WebView web=web();WebFixture.load(instrument,web,"https://"+host+"/fixture",html,"!!document.body");return web;
    }
    private String eval(WebView web,String script)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        instrument.runOnMainSync(()->web.evaluateJavascript(script,v->{value.set(v);done.countDown();}));
        assertTrue(done.await(10,TimeUnit.SECONDS));return value.get();
    }
    @Test public void legacyDesktopCarrierFillsAndVerifiesDynamicFields()throws Exception{
        WebView web=open("example.com","<html><body><input id='title'><div id='caption' contenteditable='true'></div>"
            +"<input type='checkbox' id='audience'><select id='visibility'><option value='private'>Private</option><option value='schedule'>Schedule</option></select>"
            +"<input type='date' id='date'><input type='time' id='time'><input id='secret' type='password'></body></html>");
        JSONObject caps=nativeCall("files_browser_capabilities",new JSONObject()).json();assertEquals(2,caps.getInt("version"));
        JSONArray fields=new JSONArray().put(field("#title","text","Fixture title"))
            .put(field("#caption","text","Une description #Shorts"))
            .put(field("#audience","check",true)).put(field("#visibility","select","schedule"))
            .put(field("#date","text","2027-01-24")).put(field("#time","text","18:00"));
        JSONObject args=new JSONObject().put("expected_host","example.com").put("fields",fields);
        assertEquals(6,nativeCall("browser_fill_fields",args).json().getInt("count"));
        assertTrue(nativeCall("browser_verify_fields",args).json().getBoolean("verified"));
        assertTrue(browserCall("fill_form",args).ok);assertEquals("true",eval(web,"document.querySelector('#audience').checked"));
        assertFalse(browserCall("fill_form",new JSONObject(args.toString()).put("expected_host","other.example")).ok);
        assertFalse(browserCall("fill_form",new JSONObject().put("expected_host","example.com")
            .put("fields",new JSONArray().put(field("#secret","text","blocked")))).ok);
        assertEquals("\"\"",eval(web,"document.querySelector('#secret').value"));
    }
    @Test public void nativeLargeMp4ThroughLocal2InBackgroundSurvivesInputResetAndNoReplay()throws Exception{
        path="native-local2-"+job+".mp4";long size=34L*1024*1024;
        try(RandomAccessFile f=new RandomAccessFile(new WorkspaceStore(app).file(path),"rw")){f.setLength(size);f.seek(size-4);f.write(new byte[]{1,2,3,4});}
        WebView web=open("example.com","<html><body><input id='media' type='file' accept='video/mp4' style='display:block;width:240px;height:80px;margin:30px'>"
            +"<output id='bytes'></output><script>window.changes=0;document.querySelector('#media').addEventListener('change',function(e){"
            +"window.changes++;var f=e.target.files[0];f.slice(f.size-4).arrayBuffer().then(function(b){document.querySelector('#bytes').textContent=Array.from(new Uint8Array(b)).join(',');});e.target.value='';});</script></body></html>");
        settings=(SettingsActivity)instrument.startActivitySync(new Intent(app,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject args=new JSONObject().put("id",job).put("expected_host","example.com").put("selector","#media")
            .put("files",new JSONArray().put(new JSONObject().put("workspace_path",path)));
        JSONObject selected=nativeCall("browser_upload_workspace_file",args).json();
        assertEquals(selected.toString(),"selected",selected.getString("state"));assertFalse(selected.getBoolean("server_upload_verified"));
        String bytes="";for(int i=0;i<30;i++){bytes=eval(web,"document.querySelector('#bytes').textContent");if(bytes.contains("1,2,3,4"))break;Thread.sleep(100);}
        assertEquals("\"1,2,3,4\"",bytes);assertEquals("0",eval(web,"document.querySelector('#media').files.length"));
        assertTrue(nativeCall("browser_upload_workspace_file",args).json().getBoolean("replayed"));
        assertEquals("1",eval(web,"window.changes"));
        assertEquals("selected",nativeCall("files_browser_upload_status",new JSONObject().put("id",job)).json().getString("state"));
        assertFalse(nativeCall("browser_upload_workspace_file",new JSONObject(args.toString()).put("selector","#other")).ok);
    }
    @Test public void grokBackgroundPngUsesBoundedJsImportWithVerifiedReceipt()throws Exception{
        path="dalton-test-"+job+".png";
        long size=280000L;
        try(RandomAccessFile file=new RandomAccessFile(new WorkspaceStore(app).file(path),"rw")){
            file.setLength(size);
            file.seek(0);file.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        }
        WebView web=open("grok.com","<html><body><input id='media' type='file' accept='image/png'>"
            +"<output id='received'></output><script>document.getElementById('media').addEventListener('change',function(){"
            +"var f=this.files[0];document.getElementById('received').textContent=f.name+':'+f.size;this.value='';});</script></body></html>");
        settings=(SettingsActivity)instrument.startActivitySync(new Intent(app,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject args=new JSONObject().put("id",job).put("expected_host","grok.com").put("selector","#media")
            .put("files",new JSONArray().put(new JSONObject().put("workspace_path",path)));
        JSONObject receipt=browserCall("upload_file",args).json();
        assertEquals("selected",receipt.getString("state"));
        assertFalse(receipt.getBoolean("server_upload_verified"));
        assertEquals(JSONObject.quote(path+":"+size),eval(web,"document.getElementById('received').textContent"));
        assertEquals("selected",browserCall("upload_status",new JSONObject().put("id",job)).json().getString("state"));
    }
    @Test public void destroyedActivityRetainsExactTabsFormAndJsStateThenReattaches()throws Exception{
        WebView original=open("example.com","<html><body><input id='draft' value='keep'><input type='file' id='media'><script>window.sessionMarker='original-dom';</script></body></html>");
        eval(original,"document.querySelector('#draft').value='unsaved';var dt=new DataTransfer();dt.items.add(new File(['abc'],'retained.txt'));document.querySelector('#media').files=dt.files;true");
        finishBrowser();browser=null;
        JSONObject status=call("browser_status",new JSONObject()).json();assertTrue(status.getBoolean("retained"));assertFalse(status.getBoolean("visible"));
        assertTrue(browserCall("verify_form",new JSONObject().put("expected_host","example.com")
            .put("fields",new JSONArray().put(field("#draft","text","unsaved")))).ok);
        assertEquals("\"original-dom\"",eval(original,"window.sessionMarker"));
        assertEquals("1",eval(original,"document.querySelector('#media').files.length"));
        browser=(MainActivity)instrument.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertSame(original,web());assertEquals("\"unsaved\"",eval(web(),"document.querySelector('#draft').value"));
        assertEquals("\"original-dom\"",eval(web(),"window.sessionMarker"));
    }
    @Test public void formValidationPreventsPartialWritesAndRejectsReactReset()throws Exception{
        WebView web=open("example.com","<html><body><input id='first' value='old'><input id='reset' oninput=\"this.value=''\"></body></html>");
        Result bad=browserCall("fill_form",new JSONObject().put("expected_host","example.com").put("fields",new JSONArray()
            .put(field("#first","text","new")).put(field("#missing","text","value"))));
        assertFalse(bad.ok);assertEquals("\"old\"",eval(web,"document.querySelector('#first').value"));
        assertFalse(browserCall("fill_form",new JSONObject().put("expected_host","example.com").put("fields",new JSONArray().put(field("#reset","text","refused")))).ok);
    }
    @Test public void scheduledPublicationPreflightAndFinalReceiptUseExistingJournalExactlyOnce()throws Exception{
        WebView web=open("studio.youtube.com","<html><body><span id='account'>CHK fixture</span><input id='title' value='Fixture short'>"
            +"<button id='publish' onclick=\"window.clicks=(window.clicks||0)+1;document.querySelector('#receipt').textContent='Fixture publication scheduled';\">Schedule</button><p id='receipt'></p></body></html>");
        java.text.SimpleDateFormat iso=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",java.util.Locale.US);iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        nativeCall("publication_prepare",new JSONObject().put("id",job).put("platform","youtube").put("destination_url","https://studio.youtube.com/fixture")
            .put("account","CHK fixture").put("title","Fixture short").put("schedule_at",iso.format(new java.util.Date(System.currentTimeMillis()+86400000))+"+00:00")).json();
        JSONObject args=new JSONObject().put("id",job).put("expected_host","studio.youtube.com").put("mode","schedule")
            .put("selector","#publish").put("account_selector","#account").put("expected_text","Fixture publication scheduled")
            .put("fields",new JSONArray().put(field("#title","text","wrong title")));
        assertFalse(browserCall("publication_submit",args).ok);assertEquals("false",eval(web,"!!window.clicks"));
        args.put("fields",new JSONArray().put(field("#title","text","Fixture short")));
        assertEquals("schedule_browser_confirmed",browserCall("publication_submit",args).json().getString("status"));
        assertFalse(browserCall("publication_submit",args).ok);assertEquals("1",eval(web,"window.clicks"));
    }
    @Test public void sameUrlReloadCannotConfirmThePreviousDocument()throws Exception{
        WebView web=open("example.com","<html><body>Previous document still in renderer</body></html>");
        CountDownLatch done=new CountDownLatch(1);Result result=new Result();
        java.lang.reflect.Method verify=MainActivity.class.getDeclaredMethod("verifyNavigation",WebView.class,String.class,long.class,int.class,AgentClient.ResultCallback.class);
        verify.setAccessible(true);
        instrument.runOnMainSync(()->{
            long before=BrowserWebState.documentVersion(web);String url=web.getUrl();
            // Chromium signals page-start before replacing the old DOM, including
            // a reload with exactly the same location and readyState=complete.
            BrowserWebState.remember(web,url);
            try{verify.invoke(browser,web,url,before,1000,(AgentClient.ResultCallback)(ok,text)->{
                result.ok=ok;result.text=text;done.countDown();
            });}catch(Exception e){throw new RuntimeException(e);}
        });
        assertTrue(done.await(5,TimeUnit.SECONDS));assertFalse(result.text,result.ok);
        assertTrue(result.text,result.text.contains("Chargement non confirmé"));
    }
    @Test public void legacyDesktopDownloadUsesNativeHandlerAndRejectsNonHttps()throws Exception{
        open("example.com","<html><body>Download fixture</body></html>");
        Result result=browserCall("download_file",new JSONObject().put("expected_host","example.com").put("url","http://example.com/file.mp4"));
        assertFalse(result.ok);assertTrue(result.text,result.text.contains("HTTPS"));
    }
    @Test public void uncertainClickCanBeConfirmedLaterWithoutRepeatingIt()throws Exception{
        WebView web=open("example.com","<html><body><button id='send' onclick='window.clicks=(window.clicks||0)+1'>Envoyer</button><p id='done'></p></body></html>");
        JSONObject args=new JSONObject().put("expected_host","example.com").put("selector","label:Envoyer")
            .put("expected_text","Generation ready").put("receipt_id",job).put("timeout_ms",800);
        Result first=browserCall("click_when_enabled",args);
        assertFalse(first.ok);assertEquals("1",eval(web,"window.clicks"));
        assertFalse(browserCall("click_when_enabled",args).ok);
        assertEquals("1",eval(web,"window.clicks"));
        eval(web,"history.pushState({},'', '/confirmed');document.querySelector('#done').textContent='Generation ready'");
        assertTrue(browserCall("click_when_enabled",args).ok);
        assertEquals("1",eval(web,"window.clicks"));
        app.getSharedPreferences("chk-click-once-v1",0).edit().remove(job).commit();
    }
    @Test public void buttonWaitDoesNotConsumePostClickVerificationTime()throws Exception{
        WebView web=open("example.com","<html><body><button id='send' disabled onclick=\"window.clicks=(window.clicks||0)+1;setTimeout(function(){document.querySelector('#done').textContent='Finished'},1000)\">Envoyer</button><p id='done'></p></body></html>");
        eval(web,"setTimeout(function(){document.querySelector('#send').disabled=false},900)");
        JSONObject args=new JSONObject().put("expected_host","example.com").put("selector","#send")
            .put("expected_text","Finished").put("receipt_id",job).put("timeout_ms",1500);
        Result result=browserCall("click_when_enabled",args);
        assertTrue(result.text,result.ok);assertEquals("1",eval(web,"window.clicks"));
        app.getSharedPreferences("chk-click-once-v1",0).edit().remove(job).commit();
    }
    @Test public void ownerStopDestroysRetainedBrowserAndPreventsFurtherCommands()throws Exception{
        WebView page=open("example.com","<html><body>retained owner session</body></html>");
        finishBrowser();browser=null;
        instrument.runOnMainSync(()->AgentClient.get(app).setAutonomous(false));instrument.waitForIdleSync();
        assertFalse(call("browser_status",new JSONObject()).json().getBoolean("browser_available"));
        instrument.runOnMainSync(()->assertFalse(BrowserWebState.alive(page)));
        assertFalse(call("browser_read_page",new JSONObject()).ok);
    }
    private void finishBrowser()throws Exception{
        MainActivity closing=browser;instrument.runOnMainSync(closing::finish);
        boolean destroyed=false;
        for(int i=0;i<60;i++){
            java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
            instrument.runOnMainSync(()->done.set(closing.isDestroyed()));
            if(done.get()){destroyed=true;break;}Thread.sleep(100);
        }
        assertTrue("Activity destruction did not complete",destroyed);instrument.waitForIdleSync();
    }
    private static JSONObject field(String selector,String kind,Object value)throws Exception{
        return new JSONObject().put("selector",selector).put("kind",kind).put("value",value);
    }
}
