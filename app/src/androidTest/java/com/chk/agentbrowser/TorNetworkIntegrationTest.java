package com.chk.agentbrowser;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.webkit.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Explicit network opt-in; never substitutes a mocked Tor connection for real bootstrap. */
@RunWith(AndroidJUnit4.class)
public final class TorNetworkIntegrationTest {
    private static final String ONION="http://i3xi5qxvbrngh3g6o7czwjfxwjzigook7zxzjmgwg5b7xnjcn5hzciad.onion/";
    private Context app;
    private JSONObject command(JSONObject args,boolean expected,int seconds)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> message=new AtomicReference<>();
        JSONObject p=new JSONObject(args.toString()).put("tool","tor");
        DesktopCommands.execute(app,null,"desktop",p,new AgentClient.ResultCallback(){public void finish(boolean success,String value){ok.set(success);message.set(value);done.countDown();}public boolean isLocal3(){return true;}});
        assertTrue("Tor callback timeout: "+args.optString("operation"),done.await(seconds,TimeUnit.SECONDS));assertEquals(message.get(),expected,ok.get());return new JSONObject(message.get());
    }
    private JSONObject args(String op)throws Exception{return new JSONObject().put("operation",op);}
    @Test public void realCircuitOnionPixelsAndDisconnectThroughDesktopCarrier()throws Exception{
        Assume.assumeTrue("Real Tor test requires explicit CI network opt-in",InstrumentationRegistry.getArguments().getString("tor_network_test","").equals("true"));
        Assume.assumeTrue(Build.VERSION.SDK_INT>=29);app=InstrumentationRegistry.getInstrumentation().getTargetContext();boolean oldPreviews=AgentClient.get(app).isPreviewAllowed();AgentClient.get(app).setPreviewAllowed(true);
        Activity entry=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(app,TorEntryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject proof=new JSONObject().put("onion_source","https://onion.torproject.org/").put("requested_onion",ONION).put("phone_local3_transport_tested",false).put("native_desktop_carrier_tested",true);
        String session="";
        try{
            JSONObject initial=command(args("status"),true,30);session=initial.getString("session_id");
            if(initial.optBoolean("tor_connected")){command(args("disconnect").put("session_id",session),true,30);initial=command(args("status"),true,30);session=initial.getString("session_id");}
            assertFalse(initial.getBoolean("tor_verified"));
            JSONObject denied=command(args("navigate").put("session_id",session).put("request_id","onion-before-tor").put("url",ONION),false,30);assertTrue(denied.toString().contains("TOR_NOT_CONNECTED"));proof.put("onion_before_connection_blocked",true);
            JSONObject connected=command(args("connect"),true,360);assertEquals("connected",connected.getString("connection_state"));assertTrue(connected.getBoolean("tor_verified"));assertTrue(connected.getJSONObject("diagnostic").getBoolean("is_tor"));assertNotEquals(android.os.Process.myPid(),connected.getInt("engine_process_id"));assertTrue(connected.getString("native_version").startsWith("0.4.9.14"));session=connected.getString("session_id");proof.put("connection",connected);
            proof.put("normal_web_local_request_after_tor",normalBrowserStillDirect());
            JSONObject network=command(args("read").put("url",ONION),true,215);assertEquals(200,network.getInt("http_status"));assertTrue(network.getBoolean("tor_verified"));assertTrue("Tor Project content expected",network.getString("text").toLowerCase(java.util.Locale.ROOT).contains("tor"));
            proof.put("onion_http",new JSONObject().put("requested_url",ONION).put("final_url",network.getString("url")).put("redirects",network.getJSONArray("redirects")).put("http_status",network.getInt("http_status")).put("response_sha256",network.getString("response_sha256")));
            command(args("open_browser"),true,30);
            JSONObject navigation=args("navigate").put("session_id",session).put("request_id","genuine-onion-navigation").put("url",ONION);
            JSONObject loaded=command(navigation,true,185);assertEquals("completed",loaded.getString("state"));assertEquals("webview_page_finished_and_url_readback",loaded.getString("verified"));proof.put("webview_navigation",loaded);
            JSONObject replay=command(navigation,true,30);assertTrue(replay.getBoolean("replayed"));proof.put("navigation_replayed_without_second_load",true);
            JSONObject capture=command(args("screenshot"),true,30);assertEquals("pixelcopy_real_tor_window",capture.getString("capture_source"));byte[] jpeg=android.util.Base64.decode(capture.getString("jpeg_base64"),android.util.Base64.DEFAULT);assertTrue(jpeg.length>1000);write("tor-onion-window.jpg",jpeg);proof.put("screenshot_source",capture.getString("capture_source"));
            JSONObject stop=command(args("disconnect").put("session_id",session),true,30);assertTrue(stop.getBoolean("process_stopped"));assertTrue(stop.getBoolean("listeners_closed"));assertTrue(stop.getBoolean("verified_disconnect"));proof.put("disconnect",stop);
            JSONObject after=command(args("status"),true,30);assertFalse(after.getBoolean("tor_connected"));assertEquals("disconnected",after.getString("connection_state"));
            JSONObject deniedAfter=command(args("navigate").put("session_id",after.getString("session_id")).put("request_id","onion-after-stop").put("url",ONION),false,30);assertTrue(deniedAfter.toString().contains("TOR_NOT_CONNECTED"));proof.put("onion_after_disconnect_blocked",true).put("status","passed");
        }catch(Throwable e){proof.put("status","failed").put("error",String.valueOf(e));throw e;}
        finally{try{JSONObject state=command(args("status"),true,30);proof.put("cleanup_disconnect",command(args("disconnect").put("session_id",state.getString("session_id")),true,30));}catch(Throwable ignored){}write("tor-network-proof.json",proof.toString(2).getBytes(StandardCharsets.UTF_8));AgentClient.get(app).setPreviewAllowed(oldPreviews);InstrumentationRegistry.getInstrumentation().runOnMainSync(entry::finish);}
    }
    private boolean normalBrowserStillDirect()throws Exception{
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){server.setSoTimeout(20000);AtomicBoolean received=new AtomicBoolean();AtomicReference<Throwable> error=new AtomicReference<>();
            Thread responder=new Thread(()->{try(Socket s=server.accept()){received.set(true);s.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}byte[] body="<html><body>normal-browser-direct-ok</body></html>".getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}catch(Throwable e){error.set(e);}});responder.start();
            AtomicReference<WebView> view=new AtomicReference<>();CountDownLatch finished=new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{WebView v=new WebView(app);view.set(v);v.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView w,String url){finished.countDown();}@Override public void onReceivedError(WebView w,WebResourceRequest request,WebResourceError e){if(request.isForMainFrame()){error.set(new AssertionError("Normal browser network error: "+e.getErrorCode()));finished.countDown();}}});v.loadUrl("http://127.0.0.1:"+server.getLocalPort()+"/");});
            try{assertTrue(finished.await(25,TimeUnit.SECONDS));assertTrue("Normal browser request did not reach its direct loopback server",received.get());assertNull(error.get());return true;}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->view.get().destroy());responder.join(1000);}
        }
    }
    private void write(String name,byte[] bytes)throws Exception{File dir=new File(app.getFilesDir(),"tor-proof");assertTrue(dir.isDirectory()||dir.mkdir());try(OutputStream out=new FileOutputStream(new File(dir,name))){out.write(bytes);}}
}
