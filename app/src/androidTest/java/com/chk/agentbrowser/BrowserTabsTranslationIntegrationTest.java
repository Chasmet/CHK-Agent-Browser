package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verifies real Android tab controls and owner-authorized MCP action dispatch. */
@RunWith(AndroidJUnit4.class)
public final class BrowserTabsTranslationIntegrationTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();

    private String command(MainActivity activity,String action,JSONObject args)throws Exception {
        Method m=MainActivity.class.getDeclaredMethod("runAgentCommand",String.class,JSONObject.class,AgentClient.ResultCallback.class);
        m.setAccessible(true);
        CountDownLatch done=new CountDownLatch(1);
        AtomicReference<String> result=new AtomicReference<>();
        AtomicBoolean ok=new AtomicBoolean();
        instrument.runOnMainSync(()->{
            try {m.invoke(activity,action,args,(AgentClient.ResultCallback)(success,message)->{
                ok.set(success);result.set(message);done.countDown();
            });}catch(Exception e){result.set(e.toString());done.countDown();}
        });
        assertTrue("No MCP reply for "+action,done.await(10,TimeUnit.SECONDS));
        assertTrue("MCP "+action+": "+result.get(),ok.get());
        return result.get();
    }

    @Test public void tabsCanBeCreatedSelectedListedAndClosed()throws Exception {
        Context app=instrument.getTargetContext();
        SharedPreferences state=app.getSharedPreferences("browser_mcp_v1",0);
        boolean enabled=state.getBoolean("enabled",false);
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrument.startActivitySync(
            new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            state.edit().putBoolean("enabled",true).commit();
            final int before=new JSONArray(command(activity,"tabs",new JSONObject())).length();
            assertEquals(View.VISIBLE,activity.findViewById(R.id.translate_page).getVisibility());
            assertTrue("Browser must have spare tab capacity",before<12);
            command(activity,"new_tab",new JSONObject().put("url","https://example.com/"));
            JSONArray after=new JSONArray(command(activity,"list_tabs",new JSONObject()));
            assertEquals(before+1,after.length());
            assertTrue(after.getJSONObject(after.length()-1).getBoolean("active"));
            assertEquals(after.length()-1,after.getJSONObject(after.length()-1).getInt("index"));
            command(activity,"switch_tab",new JSONObject().put("index",0));
            JSONArray switched=new JSONArray(command(activity,"tabs",new JSONObject()));
            assertTrue(switched.getJSONObject(0).getBoolean("active"));
            command(activity,"close_tab",new JSONObject().put("index",after.length()-1));
            assertEquals(before,new JSONArray(command(activity,"tabs",new JSONObject())).length());
            AtomicReference<String> error=new AtomicReference<>();
            instrument.runOnMainSync(()->BrowserTranslator.translate(
                (android.webkit.WebView)getCurrent(activity),"invalid",
                (ok,message)->error.set(message)));
            assertTrue(error.get().contains("Langues acceptées"));
        } finally {
            state.edit().putBoolean("enabled",enabled).commit();
            instrument.runOnMainSync(activity::finish);
        }
    }

    private Object getCurrent(MainActivity activity){
        try {java.lang.reflect.Field f=MainActivity.class.getDeclaredField("current");
            f.setAccessible(true);return f.get(activity);}
        catch(Exception e){throw new RuntimeException(e);}
    }
}
