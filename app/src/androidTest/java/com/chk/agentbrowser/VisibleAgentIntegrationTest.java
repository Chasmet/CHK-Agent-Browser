package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.view.*;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Tests the actual visible command path with a local HTML page, without using the owner's session. */
@RunWith(AndroidJUnit4.class)
public final class VisibleAgentIntegrationTest {
    @Test public void testCommandsExposeRealGooglePageAndClickOnce()throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();SharedPreferences preferences=context.getSharedPreferences("browser_mcp_v1",0);boolean wasEnabled=preferences.getBoolean("enabled",false);context.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Field field=MainActivity.class.getDeclaredField("current");field.setAccessible(true);WebView web=(WebView)field.get(activity);Method command=MainActivity.class.getDeclaredMethod("runAgentCommand",String.class,JSONObject.class,AgentClient.ResultCallback.class);command.setAccessible(true);
        try{preferences.edit().putBoolean("enabled",true).commit();instrumentation.runOnMainSync(()->web.loadDataWithBaseURL("https://www.google.com/","<html><body><button id='go' onclick='this.textContent=\"Clicked once\";this.disabled=true;'>Visible button</button></body></html>","text/html","UTF-8",null));Thread.sleep(1200);
            CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();instrumentation.runOnMainSync(()->{try{command.invoke(activity,"click",new JSONObject().put("selector","#go"),(AgentClient.ResultCallback)(ok,res)->{success.set(ok);done.countDown();});}catch(Exception e){throw new RuntimeException(e);}});assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(success.get());instrumentation.runOnMainSync(()->{assertEquals(View.GONE,activity.findViewById(R.id.start_page).getVisibility());assertEquals(View.VISIBLE,activity.findViewById(R.id.agent_action).getVisibility());});
            CountDownLatch read=new CountDownLatch(1);AtomicReference<String> content=new AtomicReference<>();instrumentation.runOnMainSync(()->web.evaluateJavascript("document.getElementById('go').textContent",value->{content.set(value);read.countDown();}));assertTrue(read.await(10,TimeUnit.SECONDS));assertEquals("\"Clicked once\"",content.get());
        }finally{preferences.edit().putBoolean("enabled",wasEnabled).commit();instrumentation.runOnMainSync(()->activity.finish());}
    }
}
