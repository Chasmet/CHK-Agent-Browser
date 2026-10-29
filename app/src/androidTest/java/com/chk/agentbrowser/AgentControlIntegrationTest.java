package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual CHK touch, keyboard readback, receipt recovery and concurrent tab input. */
@RunWith(AndroidJUnit4.class)
public final class AgentControlIntegrationTest {
    private final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    private Context app;private MainActivity activity;private WebView web;private AlertDialog dialog;
    private SharedPreferences prefs,local;private boolean oldEnabled,oldAuto,oldLocal;private final ArrayList<String> receipts=new ArrayList<>(),jobs=new ArrayList<>();
    private final String prefix="control-"+UUID.randomUUID().toString().substring(0,8);
    private static final class Result {boolean ok;String text;final CountDownLatch done=new CountDownLatch(1);JSONObject json()throws Exception{assertTrue(text,ok);return new JSONObject(text);}Result await()throws Exception{assertTrue("Control timeout",done.await(12,TimeUnit.SECONDS));return this;}}
    private AgentClient.ResultCallback callback(Result r){return new AgentClient.ResultCallback(){public boolean isLocal2(){return true;}public boolean isLocal3(){return true;}public void finish(boolean ok,String text){r.ok=ok;r.text=text;r.done.countDown();}};}
    @Before public void setup()throws Exception{
        app=ins.getTargetContext();prefs=app.getSharedPreferences("browser_mcp_v1",0);local=app.getSharedPreferences("chk_local3_v1",0);
        oldEnabled=prefs.getBoolean("enabled",false);oldAuto=prefs.getBoolean("autonomous_mode",false);oldLocal=local.getBoolean("enabled",false);
        prefs.edit().putBoolean("enabled",false).putBoolean("autonomous_mode",true).commit();local.edit().putBoolean("enabled",true).commit();
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        activity=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ins.runOnMainSync(()->web=AgentClient.get(app).controlBrowser(callback(new Result())).selectedWebView());
        WebFixture.load(ins,web,"https://control.test/first","<html><body><input id='draft' aria-label='Brouillon'><button onclick='window.clicks=(window.clicks||0)+1;this.textContent=\"Fait\"'>Exécuter</button><canvas width='300' height='300' style='display:block;background:skyblue'></canvas></body></html>","!!document.querySelector('#draft')");
    }
    @After public void cleanup()throws Exception{
        for(String id:jobs){AgentTasks.requestCancel(id);try{AgentFiles.journal(app,"tasks",id).delete();}catch(Exception ignored){}}
        ins.runOnMainSync(()->{if(activity!=null){android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);ime.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);}if(dialog!=null)dialog.dismiss();if(activity!=null)activity.finish();});ins.waitForIdleSync();
        ins.runOnMainSync(()->AgentClient.get(app).closeRetainedBrowser());app.stopService(new Intent(app,AgentService.class));
        for(String id:receipts)AgentFiles.journal(app,"control",id).delete();
        prefs.edit().putBoolean("enabled",oldEnabled).putBoolean("autonomous_mode",oldAuto).commit();local.edit().putBoolean("enabled",oldLocal).commit();
    }
    private Result start(JSONObject p){Result r=new Result();ins.runOnMainSync(()->AgentControl.execute(app,p,callback(r)));return r;}
    private Result call(JSONObject p)throws Exception{return start(p).await();}
    private JSONObject observe(String target,String tab)throws Exception{return call(new JSONObject().put("operation","observe").put("target",target).put("tab_id",tab)).json();}
    private JSONObject action(JSONObject observation,JSONObject input)throws Exception{
        String id=prefix+"-"+receipts.size();receipts.add(id);
        return new JSONObject().put("operation","act").put("id",id).put("observation_id",observation.getString("observation_id")).put("input",input);
    }
    private String js(WebView w,String script)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();ins.runOnMainSync(()->w.evaluateJavascript(script,v->{value.set(v);done.countDown();}));assertTrue(done.await(5,TimeUnit.SECONDS));return value.get();}
    private JSONObject field(JSONObject observed)throws Exception{return observed.getJSONObject("keyboard").getJSONArray("controls").getJSONObject(0);}
    private JSONObject pointer(JSONObject observed,String label)throws Exception{
        JSONArray controls=observed.getJSONArray("controls");for(int i=0;i<controls.length();i++){JSONObject c=controls.getJSONObject(i);if(c.optString("label").equals(label)||c.optString("text").equals(label)){JSONObject b=c.getJSONObject("bounds");return new JSONObject().put("tool","pointer").put("gesture","tap").put("x",b.getDouble("x")+b.getDouble("width")/2).put("y",b.getDouble("y")+b.getDouble("height")/2);}}
        throw new AssertionError("Missing control: "+label+" in "+observed);
    }
    @Test public void webKeyboardTargetsObservedFieldAndVerifiesReadback()throws Exception{
        JSONObject seen=observe("web","");JSONObject input=new JSONObject().put("tool","keyboard").put("operation","replace").put("selector",field(seen).getString("selector")).put("text","Texte vérifié");
        JSONObject result=call(action(seen,input).put("expect",new JSONObject().put("field_value","Texte vérifié"))).json();
        assertEquals("completed",result.getString("state"));assertTrue(result.toString(),result.getBoolean("outcome_confirmed"));
        assertEquals("\"Texte vérifié\"",js(web,"document.querySelector('#draft').value"));assertTrue(result.getJSONObject("after").has("observation_id"));
    }
    @Test public void acceptedPointerWithoutEvidenceReportsActionSentNotCompleted()throws Exception{
        JSONObject seen=observe("web","");
        JSONObject request=action(seen,pointer(seen,"Exécuter"));
        Result response=call(request);
        assertFalse("A gesture without outcome proof must not report success",response.ok);
        JSONObject result=new JSONObject(response.text);
        assertEquals("action_sent",result.getString("state"));
        assertTrue(result.getBoolean("action_sent"));
        assertFalse(result.getBoolean("outcome_confirmed"));
        assertTrue(result.getBoolean("effect_uncertain"));
        // Sending an Android touch does not prove a DOM click: it may miss the
        // target on a headless emulator. The receipt must never claim success.
        String clicksBeforeReplay=js(web,"window.clicks||0");
        assertTrue("One dispatched touch must not click more than once",
            "0".equals(clicksBeforeReplay)||"1".equals(clicksBeforeReplay));
        Result replay=call(request);
        assertFalse("Replaying an unconfirmed gesture must stay unconfirmed",replay.ok);
        JSONObject receipt=new JSONObject(replay.text);
        assertTrue(receipt.getBoolean("replayed"));
        assertFalse(receipt.getBoolean("outcome_confirmed"));
        assertEquals("The replay must not cause another DOM click",
            clicksBeforeReplay,js(web,"window.clicks||0"));
    }
    @Test public void conditionTrueBeforeControlActionIsNotAnEffectConfirmation()throws Exception{
        JSONObject seen=observe("web","");JSONObject request=action(seen,pointer(seen,"Exécuter")).put("expect",new JSONObject().put("text_contains","Exécuter"));
        JSONObject result=call(request).json();assertEquals("already_satisfied",result.getString("state"));assertFalse(result.getBoolean("action_sent"));assertFalse(result.getBoolean("outcome_confirmed"));assertEquals("0",js(web,"window.clicks||0"));
    }
    @Test public void nativePointerHasAfterVisionAndReplayNeverClicksTwice()throws Exception{
        AtomicInteger clicks=new AtomicInteger();ins.runOnMainSync(()->{
            LinearLayout box=new LinearLayout(activity);box.setOrientation(1);Button b=new Button(activity);b.setText("Exécuter");TextView out=new TextView(activity);out.setText("En attente");
            b.setOnClickListener(v->{clicks.incrementAndGet();out.setText("Fait");});box.addView(b);box.addView(out);
            dialog=new AgentDialog.Builder(activity).setTitle("Contrôle CHK").setView(box).create();dialog.show();
        });Thread.sleep(180);JSONObject seen=observe("native","");JSONObject request=action(seen,pointer(seen,"Exécuter")).put("expect",new JSONObject().put("text_contains","Fait"));
        JSONObject done=call(request).json();assertTrue(done.toString(),done.getBoolean("outcome_confirmed"));assertEquals(1,clicks.get());
        JSONObject replay=call(request).json();assertTrue(replay.getBoolean("replayed"));assertTrue(replay.getBoolean("outcome_confirmed"));assertEquals(1,clicks.get());
        java.io.File journal=AgentFiles.journal(app,"control",request.getString("id"));JSONObject saved=AgentFiles.read(journal);saved.remove("result");saved.put("state","executing");AgentFiles.save(journal,saved);
        Result unknown=call(request);assertFalse(unknown.ok);assertEquals("unknown",new JSONObject(unknown.text).getString("state"));assertEquals(1,clicks.get());
    }
    @Test public void nativeKeyboardIsObservedAndReadBackInSameControl()throws Exception{
        ins.runOnMainSync(()->{EditText edit=new EditText(activity);edit.setHint("Nom du projet");dialog=new AgentDialog.Builder(activity).setTitle("Projet").setView(edit).create();dialog.show();});Thread.sleep(180);
        JSONObject seen=observe("native","");JSONObject key=new JSONObject().put("tool","keyboard").put("operation","replace").put("selector",field(seen).getString("selector")).put("text","Mon projet");
        assertTrue(call(action(seen,key).put("expect",new JSONObject().put("field_value","Mon projet"))).json().getBoolean("outcome_confirmed"));
    }
    @Test public void staleObservationRejectsActionWithoutTouchOrFallback()throws Exception{
        JSONObject seen=observe("web","");JSONObject request=action(seen,pointer(seen,"Exécuter"));js(web,"document.querySelector('button').textContent='Interface remplacée';true");
        Result result=call(request);assertFalse(result.text,result.ok);assertEquals("0",js(web,"window.clicks||0"));
        JSONObject receipt=new JSONObject(result.text);assertEquals("rejected",receipt.getString("state"));assertFalse(receipt.getBoolean("action_sent"));
        assertFalse(call(new JSONObject().put("operation","observe").put("target","web").put("tab_id","tab-absent")).ok);
    }
    @Test public void invalidPointerDoesNotClaimAnAcknowledgedGestureOrKeepTheLock()throws Exception{
        JSONObject seen=observe("web","");JSONObject input=new JSONObject().put("tool","pointer").put("gesture","tap").put("x",-5).put("y",20);
        Result rejected=call(action(seen,input));assertFalse(rejected.text,rejected.ok);JSONObject result=new JSONObject(rejected.text);
        assertTrue(result.isNull("action_sent"));assertFalse(result.getBoolean("outcome_confirmed"));assertTrue(result.getBoolean("effect_uncertain"));
        assertEquals(0,call(new JSONObject().put("operation","status")).json().getJSONArray("active_inputs").length());assertEquals("0",js(web,"window.clicks||0"));
    }
    @Test public void otherTabKeyboardFinishesWhilePointerGestureIsStillRunning()throws Exception{
        JSONObject first=observe("web","");String firstId=first.getString("tab_id");
        ins.runOnMainSync(()->{try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("newTab",String.class);m.setAccessible(true);m.invoke(activity,"https://control.test/second");}catch(Exception e){throw new RuntimeException(e);}});
        AtomicReference<WebView> second=new AtomicReference<>();ins.runOnMainSync(()->second.set(AgentClient.get(app).controlBrowser(callback(new Result())).selectedWebView()));
        WebFixture.load(ins,second.get(),"https://control.test/second","<input aria-label='Autre onglet' id='other'>","!!document.querySelector('#other')");
        JSONObject a=observe("web",firstId),b=observe("web","");
        JSONObject drag=new JSONObject().put("tool","pointer").put("gesture","drag").put("x",40).put("y",120).put("end_x",160).put("end_y",220).put("duration_ms",2200);
        Result moving=start(action(a,drag));
        boolean locked=false;for(int i=0;i<40;i++){JSONObject status=call(new JSONObject().put("operation","status")).json();if(status.getJSONArray("active_inputs").length()>0){locked=true;break;}Thread.sleep(20);}assertTrue(locked);
        Result collision=call(action(a,new JSONObject(drag.toString())));assertFalse(collision.text,collision.ok);
        JSONObject key=new JSONObject().put("tool","keyboard").put("operation","replace").put("selector",field(b).getString("selector")).put("text","En parallèle");
        JSONObject typed=call(action(b,key).put("expect",new JSONObject().put("field_value","En parallèle"))).json();assertTrue(typed.toString(),typed.getBoolean("outcome_confirmed"));
        assertEquals("Other tab input must finish before long gesture",1L,moving.done.getCount());moving.await();assertFalse("Un geste sans condition ne doit pas être marqué comme réussi",moving.ok);assertEquals("action_sent",new JSONObject(moving.text).getString("state"));
        JSONObject tabs=call(new JSONObject().put("operation","status")).json();boolean selected=false;JSONArray list=tabs.getJSONArray("tabs");for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("id").equals(b.getString("tab_id")))selected=list.getJSONObject(i).getBoolean("selected");assertTrue(selected);
    }
    @Test public void parallelLaunchesIndependentDurableWorkflowsAndValidatesBeforeStarting()throws Exception{
        JSONArray launches=new JSONArray();for(int i=0;i<2;i++){String id=prefix+"-job-"+i;jobs.add(id);JSONArray steps=new JSONArray().put(new JSONObject().put("id","inspect").put("action","desktop").put("args",new JSONObject().put("tool","control").put("operation","observe").put("target","web")));
            launches.put(new JSONObject().put("id",id).put("steps",steps));}
        JSONObject submitted=call(new JSONObject().put("operation","parallel").put("jobs",launches)).json();assertEquals(2,submitted.getJSONArray("jobs").length());
        for(String id:jobs){JSONObject state=null;for(int n=0;n<100;n++){Result r=new Result();JSONObject args=new JSONObject().put("tool","tasks").put("operation","status").put("id",id);ins.runOnMainSync(()->DesktopCommands.execute(app,web,"desktop",args,callback(r)));state=r.await().json();if(state.optString("state").equals("completed"))break;Thread.sleep(30);}assertEquals(state.toString(),"completed",state.getString("state"));}
        JSONObject nested=new JSONObject().put("id",prefix+"-bad").put("steps",new JSONArray().put(new JSONObject().put("id","bad").put("action","desktop").put("args",new JSONObject().put("tool","control").put("operation","parallel"))));
        assertFalse(call(new JSONObject().put("operation","parallel").put("jobs",new JSONArray().put(nested))).ok);
    }
}
