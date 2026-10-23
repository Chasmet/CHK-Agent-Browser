package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real foreground service plus isolated HTTP peers; no PC, Render or private files required. */
@RunWith(AndroidJUnit4.class)
public final class Local2RecoveryIntegrationTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Context app;
    private SettingsActivity screen;
    private Map<String,?> renderBefore,localBefore,healthBefore;
    private SharedPreferences render,local,health;
    @Before public void setup(){
        app=instrument.getTargetContext();
        if(android.os.Build.VERSION.SDK_INT>=33)instrument.getUiAutomation().grantRuntimePermission(app.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        render=app.getSharedPreferences("browser_mcp_v1",0);
        local=app.getSharedPreferences("chk_local2_v1",0);health=app.getSharedPreferences("mcp_service_health",0);
        renderBefore=new HashMap<>(render.getAll());localBefore=new HashMap<>(local.getAll());healthBefore=new HashMap<>(health.getAll());
        render.edit().putBoolean("enabled",false).putBoolean("direct_mode",false).commit();
        local.edit().putBoolean("enabled",false).putString("url","").commit();
        instrument.runOnMainSync(()->{Local2Client.get(app).stop();app.stopService(new Intent(app,AgentService.class));});
        instrument.waitForIdleSync();
    }
    private static void restore(SharedPreferences prefs,Map<String,?> values){
        SharedPreferences.Editor edit=prefs.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object v=entry.getValue();String k=entry.getKey();
            if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);
            else if(v instanceof String)edit.putString(k,(String)v);
            else if(v instanceof Integer)edit.putInt(k,(Integer)v);
            else if(v instanceof Long)edit.putLong(k,(Long)v);
            else if(v instanceof Float)edit.putFloat(k,(Float)v);
        }edit.commit();
    }
    @After public void cleanup(){
        local.edit().putBoolean("enabled",false).commit();render.edit().putBoolean("enabled",false).commit();
        instrument.runOnMainSync(()->{
            Local2Client.get(app).stop();app.stopService(new Intent(app,AgentService.class));
            if(screen!=null)screen.finish();
        });instrument.waitForIdleSync();restore(render,renderBefore);restore(local,localBefore);restore(health,healthBefore);
    }
    private static final class Peer extends HttpURLConnection {
        final int status;final byte[] data;final ByteArrayOutputStream sent=new ByteArrayOutputStream();boolean disconnected;
        Peer(int status,String data)throws Exception{super(new URL("https://test.invalid/mcp/private"));this.status=status;this.data=data.getBytes(StandardCharsets.UTF_8);}
        @Override public void connect(){}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public OutputStream getOutputStream(){return sent;}
        @Override public int getResponseCode(){return status;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(data);}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(data);}
    }
    @Test public void httpErrorsRetainCodeAndNeverExposePrivateResponseOrUrl()throws Exception{
        for(int code:new int[]{401,403,404,429,502,503}){
            Peer peer=new Peer(code,"private-key-and-cookie");
            try{Local2Transport.exchange(peer,"initialize",new JSONObject());fail("HTTP error counted as success");}
            catch(Local2Transport.HttpFailure error){
                assertEquals(code,error.status);String shown=Local2Transport.describe(error);
                assertTrue(shown,shown.contains("HTTP "+code));assertFalse(shown.contains("private"));assertFalse(shown.contains("cookie"));
            }assertTrue(peer.disconnected);
        }
    }
    @Test public void validReplyKeepsTransportBoundariesAndInvalidRepliesNeverConnect()throws Exception{
        Peer good=new Peer(200,"{\"command\":null}");
        assertNull(Local2Protocol.command(Local2Transport.exchange(good,"android/next",new JSONObject())));
        assertEquals("POST",good.getRequestMethod());assertEquals(25000,good.getReadTimeout());assertEquals(8000,good.getConnectTimeout());
        assertFalse(good.getInstanceFollowRedirects());assertTrue(good.disconnected);
        assertEquals("android/next",new JSONObject(new String(good.sent.toByteArray(),StandardCharsets.UTF_8)).getString("method"));
        Peer invalid=new Peer(200,"<html>private</html>");
        try{Local2Transport.exchange(invalid,"initialize",new JSONObject());fail();}
        catch(Local2Transport.InvalidReply expected){assertFalse(Local2Transport.describe(expected).contains("private"));}
        assertTrue(invalid.disconnected);
        Peer oversized=new Peer(200,new String(new char[96*1024+1]).replace('\0','x'));
        try{Local2Transport.exchange(oversized,"initialize",new JSONObject());fail();}
        catch(Local2Transport.OversizedReply expected){}
        assertTrue(oversized.disconnected);
        Peer rpc=new Peer(200,"{\"error\":{\"code\":-32601,\"message\":\"private-token\"}}");
        try{Local2Protocol.command(Local2Transport.exchange(rpc,"android/next",new JSONObject()));fail();}
        catch(Local2Protocol.RpcFailure error){assertTrue(Local2Transport.describe(error).contains("-32601"));assertFalse(Local2Transport.describe(error).contains("private"));}
    }
    @Test public void dnsTlsAndTimeoutHaveDistinctPrivateSafeDiagnostics(){
        String secret="https://private.example/mcp/secret?cookie=value";
        assertTrue(Local2Transport.describe(new java.net.UnknownHostException(secret)).contains("DNS"));
        assertTrue(Local2Transport.describe(new javax.net.ssl.SSLHandshakeException(secret)).contains("TLS"));
        assertTrue(Local2Transport.describe(new java.net.SocketTimeoutException(secret)).contains("Délai"));
        assertFalse(Local2Transport.describe(new java.io.IOException(secret)).contains(secret));
    }
    private JSONObject awaitForeground(boolean expected)throws Exception{
        JSONObject state=null;
        for(int i=0;i<100;i++){
            state=AgentService.state(app);if(state.optBoolean("foreground")==expected&&(!expected?!state.optBoolean("running"):true))return state;
            Thread.sleep(50);
        }fail("Service state: "+state);return state;
    }
    @Test public void local2OnlyForegroundIsIdempotentAndNotificationStopStaysStopped()throws Exception{
        local.edit().putBoolean("enabled",true).putString("url","").commit();
        screen=(SettingsActivity)instrument.startActivitySync(new Intent(app,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject initial=awaitForeground(true);int starts=initial.getInt("start_commands");
        instrument.runOnMainSync(()->{for(int i=0;i<5;i++)AgentService.ensureRunning(screen);});instrument.waitForIdleSync();
        assertEquals(starts,AgentService.state(app).getInt("start_commands"));assertFalse(AgentClient.get(app).isEnabled());
        final int epoch=Local2Client.get(app).sessionGeneration();
        instrument.runOnMainSync(()->AgentService.reconnect(screen));instrument.waitForIdleSync();
        assertEquals("Reconnection must not invalidate an executing native task",epoch,Local2Client.get(app).sessionGeneration());
        instrument.runOnMainSync(()->app.startService(new Intent(app,AgentService.class).setAction(AgentService.ACTION_STOP)));
        JSONObject stopped=awaitForeground(false);
        assertFalse(Local2Client.get(app).enabled());assertFalse(AgentClient.get(app).isEnabled());assertEquals(0,stopped.getLong("next_recovery_at"));
        new AgentRestartReceiver().onReceive(app,new Intent(AgentRestartReceiver.ACTION_RECOVER));instrument.waitForIdleSync();
        assertFalse(AgentService.state(app).getBoolean("running"));
        assertTrue(stopped.getString("last_stop").contains("propriétaire"));
    }
    @Test public void rapidStopRecoveryBacksOffInsteadOfCreatingAnImmediateLoop(){
        assertEquals(60000,AgentService.recoveryDelay(1));assertEquals(120000,AgentService.recoveryDelay(2));
        assertEquals(240000,AgentService.recoveryDelay(3));assertEquals(900000,AgentService.recoveryDelay(10));
    }
}
