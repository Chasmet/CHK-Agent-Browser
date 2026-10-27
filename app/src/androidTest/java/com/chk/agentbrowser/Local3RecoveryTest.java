package com.chk.agentbrowser;
import android.app.Instrumentation;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class Local3RecoveryTest {
    final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    @Test public void missingJavascriptReplyExpiresAndLateReplyCannotClick()throws Exception{
        AtomicReference<WebView> web=new AtomicReference<>();AtomicReference<ValueCallback<String>> late=new AtomicReference<>();AtomicInteger calls=new AtomicInteger(),completed=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        ins.runOnMainSync(()->{
            WebView w=new WebView(ins.getTargetContext()){@Override public void evaluateJavascript(String script,ValueCallback<String> cb){calls.incrementAndGet();late.set(cb);}};web.set(w);
            try{VerifiedClick.run(w,new JSONObject().put("selector","#go").put("expected_text","done").put("timeout_ms",800),WorkspaceCommands.UI,new AgentClient.ResultCallback(){public void finish(boolean ok,String text){completed.incrementAndGet();result.set(text);done.countDown();}});}catch(Exception e){throw new RuntimeException(e);}
        });
        try{assertTrue(done.await(3,TimeUnit.SECONDS));JSONObject outcome=new JSONObject(result.get());assertEquals("timed_out",outcome.getString("state"));assertFalse(outcome.getBoolean("action_sent"));
            ins.runOnMainSync(()->late.get().onReceiveValue("\"WAIT\""));assertEquals(1,calls.get());assertEquals(1,completed.get());
            AtomicBoolean released=new AtomicBoolean();ins.runOnMainSync(()->{try{AgentClient.ResultCallback lease=AgentControl.lease(web.get(),false,(ok,text)->released.set(true));lease.finish(true,"next");}catch(Exception e){throw new RuntimeException(e);}});assertTrue(released.get());
        }finally{ins.runOnMainSync(()->web.get().destroy());}
    }
    @Test public void missingClickAcknowledgementReportsUnknownAndNeverRetries()throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();AtomicReference<WebView> web=new AtomicReference<>();
        ins.runOnMainSync(()->{WebView w=new WebView(ins.getTargetContext()){@Override public void evaluateJavascript(String script,ValueCallback<String> cb){if(calls.incrementAndGet()==1)cb.onReceiveValue("\"WAIT\"");}};web.set(w);
            try{VerifiedClick.run(w,new JSONObject().put("selector","#go").put("expected_text","done").put("timeout_ms",800),WorkspaceCommands.UI,(ok,text)->{result.set(text);done.countDown();});}catch(Exception e){throw new RuntimeException(e);}});
        try{assertTrue(done.await(3,TimeUnit.SECONDS));JSONObject value=new JSONObject(result.get());assertTrue(value.isNull("action_sent"));assertFalse(value.getBoolean("outcome_confirmed"));assertFalse(value.getBoolean("retry_command"));assertEquals(2,calls.get());}
        finally{ins.runOnMainSync(()->web.get().destroy());}
    }
    @Test public void alreadySatisfiedDoesNotDispatchClick()throws Exception{
        AtomicInteger calls=new AtomicInteger();AtomicReference<WebView> web=new AtomicReference<>();AtomicReference<String> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        ins.runOnMainSync(()->{WebView w=new WebView(ins.getTargetContext()){@Override public void evaluateJavascript(String script,ValueCallback<String> cb){calls.incrementAndGet();cb.onReceiveValue("\"DONE\"");}};web.set(w);
            try{VerifiedClick.run(w,new JSONObject().put("selector","#go").put("expected_text","done"),WorkspaceCommands.UI,(ok,text)->{result.set(text);done.countDown();});}catch(Exception e){throw new RuntimeException(e);}});
        try{assertTrue(done.await(3,TimeUnit.SECONDS));JSONObject value=new JSONObject(result.get());assertEquals("already_satisfied",value.getString("state"));assertFalse(value.getBoolean("action_sent"));assertFalse(value.getBoolean("outcome_confirmed"));assertEquals(1,calls.get());}
        finally{ins.runOnMainSync(()->web.get().destroy());}
    }
    @Test public void independentTasksPassBlockedInputButNavigationWaits()throws Exception{
        List<String> started=new ArrayList<>();Map<String,DirectMcpServer.Reply> replies=new HashMap<>();AtomicReference<Local3Dispatcher> dispatcher=new AtomicReference<>();
        ins.runOnMainSync(()->{
            Local3Dispatcher d=new Local3Dispatcher((action,args,reply)->{started.add(action);replies.put(action,reply);});dispatcher.set(d);
            d.submit("click","browser_click",new JSONObject(),(ok,text)->{});
            d.submit("navigate","browser_open_url",new JSONObject(),(ok,text)->{});
            d.submit("diagnostic","local3_status",new JSONObject(),(ok,text)->{});
            assertEquals(Arrays.asList("browser_click","local3_status"),started);
            replies.get("browser_click").finish(false,"expired");
            replies.get("browser_click").finish(true,"late acknowledgement must be ignored");
        });ins.waitForIdleSync();ins.runOnMainSync(()->{assertEquals(Arrays.asList("browser_click","local3_status","browser_open_url"),started);replies.get("local3_status").finish(true,"done");replies.get("browser_open_url").finish(true,"done");});
    }
    @Test public void transportLeaseExpiresWithoutAnyUiCallbackAndNeverFinishesTwice()throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicInteger replies=new AtomicInteger();AtomicReference<String> result=new AtomicReference<>();
        CommandLease lease=new CommandLease(120,(ok,text)->{replies.incrementAndGet();result.set(text);done.countDown();});
        assertTrue(done.await(2,TimeUnit.SECONDS));assertFalse(lease.isActive());assertEquals("timed_out",new JSONObject(result.get()).getString("execution_state"));
        lease.finish(true,"late");assertEquals(1,replies.get());
    }
    @Test public void expiredOwnerReleasesDispatcherForTheNextTask()throws Exception{
        CountDownLatch next=new CountDownLatch(1);AtomicInteger clicks=new AtomicInteger();
        ins.runOnMainSync(()->{
            Local3Dispatcher d=new Local3Dispatcher((action,args,reply)->{if(action.equals("browser_click"))clicks.incrementAndGet();else{next.countDown();reply.finish(true,"next");}});
            d.submit("stalled","browser_click",new JSONObject(),new CommandLease(150,(ok,text)->{}));
            d.submit("next","browser_open_url",new JSONObject(),(ok,text)->{});
        });assertTrue(next.await(2,TimeUnit.SECONDS));assertEquals(1,clicks.get());
    }
    @Test public void onlyDistinctExplicitTabTargetsOverlap()throws Exception{
        JSONObject p=new JSONObject().put("tool","control").put("target","web").put("tab_id","A").put("operation","act");
        String a=Local3TaskPolicy.lane("desktop",p),b=Local3TaskPolicy.lane("desktop",new JSONObject(p.toString()).put("tab_id","B"));
        assertFalse(Local3TaskPolicy.conflicts(a,b));assertTrue(Local3TaskPolicy.conflicts(a,a));assertTrue(Local3TaskPolicy.conflicts(a,"exclusive"));assertFalse(Local3TaskPolicy.conflicts(a,"read"));
        assertEquals(45000L,Local3TaskPolicy.timeout("browser_click",p));assertEquals(900000L,Local3TaskPolicy.timeout("files_hash",p));
    }
}
