package com.chk.agentbrowser;

import android.content.*;
import android.os.*;
import android.webkit.CookieManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TorIsolationTest {
    @Test public void launchAndExpiredOrStaleProofsNeverAllowNavigation(){
        TorSessionPolicy p=new TorSessionPolicy();assertFalse(p.usable(100));long first=p.begin();assertEquals("connecting",p.state());assertFalse(p.usable(100));
        assertFalse(p.confirm(first,false,true,100));assertEquals("error",p.state());long second=p.begin();assertTrue(p.confirm(second,true,true,100));assertTrue(p.usable(101));assertFalse(p.usable(120101));
        p.stop();assertEquals("disconnected",p.state());assertFalse(p.refresh(second,true,true,200));assertFalse(p.confirm(first,true,true,200));assertFalse(p.usable(200));
    }
    @Test public void onlyRemoteHttpHostsAndV3OnionsAreAccepted()throws Exception{
        assertEquals("https://check.torproject.org/api/ip",TorUrlPolicy.checked("https://check.torproject.org/api/ip"));
        String onion="http://rzuwtpc4wb3xdzrj3yeajsvm3fkq4vbeubm2tdxaqruzzzgs5dwemlad.onion/";assertEquals(onion,TorUrlPolicy.checked(onion));
        for(String bad:new String[]{"http://localhost/","http://127.0.0.1/","http://[::1]/","http://printer.local/","file:///etc/passwd","content://app/file","javascript:1","http://name:secret@example.com/","http://short.onion/","https://example.com./","http://example.com/\r\nx:1","http://%31%32%37.0.0.1/"}){
            try{TorUrlPolicy.checked(bad);fail("Accepted "+bad);}catch(Exception expected){}
        }
    }
    @Test public void disconnectCanInterruptBootstrapWithoutBlockingNormalBrowser()throws Exception{
        JSONObject connect=new JSONObject().put("tool","tor").put("operation","connect"),stop=new JSONObject().put("tool","tor").put("operation","disconnect");
        String a=Local3TaskPolicy.lane("desktop",connect),b=Local3TaskPolicy.lane("desktop",stop);assertFalse(Local3TaskPolicy.conflicts(a,b));assertFalse(Local3TaskPolicy.conflicts(a,"tab:normal"));assertTrue(Local3TaskPolicy.timeout("desktop",connect)>=300000);
    }
    @Test public void cookieStorageIsSeparatedInTheRealTorProcess()throws Exception{
        Assume.assumeTrue(Build.VERSION.SDK_INT>=29);Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->CookieManager.getInstance().setCookie("https://chk-tor-isolation.invalid/","normal_only=normal-value"));
        Bundle initial=inspect(app,false);assertTrue(initial.getString("process").endsWith(":tor"));assertNotEquals(android.os.Process.myPid(),initial.getInt("pid"));assertFalse(String.valueOf(initial.getString("cookie")).contains("normal_only"));
        Bundle after=inspect(app,true);assertTrue(String.valueOf(after.getString("cookie")).contains("tor_only"));
        AtomicReference<String> normal=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->normal.set(CookieManager.getInstance().getCookie("https://chk-tor-isolation.invalid/")));assertTrue(normal.get().contains("normal_only"));assertFalse(normal.get().contains("tor_only"));
    }
    private Bundle inspect(Context app,boolean set)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Bundle> result=new AtomicReference<>();
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper(),m->{result.set(m.getData());done.countDown();return true;}));
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder binder){try{Message m=Message.obtain();Bundle b=new Bundle();b.putBoolean("set_test_cookie",set);m.setData(b);m.replyTo=reply;new Messenger(binder).send(m);}catch(Exception e){done.countDown();}}public void onServiceDisconnected(ComponentName n){}};
        try{assertTrue(app.bindService(new Intent(app,TorDebugIsolationService.class),connection,Context.BIND_AUTO_CREATE));assertTrue(done.await(20,TimeUnit.SECONDS));assertNotNull(result.get());return result.get();}finally{app.unbindService(connection);}
    }
}
