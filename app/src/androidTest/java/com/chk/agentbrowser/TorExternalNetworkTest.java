package com.chk.agentbrowser;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** No owner browser/account. UI automation is restricted to a fresh official Tor APK in CI. */
@RunWith(AndroidJUnit4.class)
public final class TorExternalNetworkTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context app=inst.getTargetContext();
    static final String ONION="https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/";
    @Test public void officialExternalBrowserMustProveTorBeforeAnOnionTest()throws Exception{
        File folder=new File(app.getExternalFilesDir(null),"studio-pro");assertTrue(folder.isDirectory()||folder.mkdirs());File proof=new File(folder,"tor-network.json");
        if(!"true".equals(InstrumentationRegistry.getArguments().getString("tor_network_test"))){assertTrue("Run the dedicated real network test first",proof.isFile());assertTrue(StudioAutomation.read(proof).getBoolean("tor_diagnostic_verified"));return;}
        assertTrue("Official Tor Browser must be installed by the verified CI fixture step",TorSpace.status(app).getBoolean("browser_installed"));
        CountDownLatch opened=new CountDownLatch(1);boolean[] ok={false};String[] result={""};TorSpace.command(app,"check",new JSONObject(),(success,text)->{ok[0]=success;result[0]=text;opened.countDown();});assertTrue(opened.await(10,TimeUnit.SECONDS));assertTrue(result[0],ok[0]);assertFalse(new JSONObject(result[0]).getBoolean("connection_verified"));
        boolean diagnostic=false,onion=false;Set<String> clicked=new HashSet<>();long until=SystemClock.elapsedRealtime()+240000;
        try{
            while(SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo root=inst.getUiAutomation().getRootInActiveWindow();if(root!=null&&TorSpace.PACKAGE.contentEquals(root.getPackageName())){
                List<AccessibilityNodeInfo> nodes=nodes(root);String text=text(nodes).toLowerCase(Locale.ROOT);
                if(text.contains("check.torproject.org")&&text.contains("congratulations")&&text.contains("configured to use tor")){diagnostic=true;break;}
                for(String label:new String[]{"Connect","Connect to Tor","Start browsing","Skip","Not now","Get started","Try a bridge"})if(!clicked.contains(label)&&click(nodes,label)){clicked.add(label);break;}
            }Thread.sleep(400);}
            assertTrue("Actual Tor diagnostic page did not confirm Tor; no onion request is permitted",diagnostic);snapshot(folder,"tor-diagnostic.png");
            app.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ONION)).setPackage(TorSpace.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until=SystemClock.elapsedRealtime()+90000;
            while(SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo root=inst.getUiAutomation().getRootInActiveWindow();if(root!=null&&TorSpace.PACKAGE.contentEquals(root.getPackageName())){String text=text(nodes(root)).toLowerCase(Locale.ROOT);if(text.contains("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion")&&text.contains("duckduckgo")&&(text.contains("privacy")||text.contains("search privately"))&&!text.contains("unable to connect")&&!text.contains("onion site not found")){onion=true;break;}}Thread.sleep(500);}
            assertTrue("Legitimate DuckDuckGo onion page did not finish loading",onion);snapshot(folder,"tor-onion.png");
        }finally{
            StudioAutomation.write(proof,new JSONObject().put("tor_diagnostic_verified",diagnostic).put("onion_verified",onion).put("diagnostic_origin",TorSpace.CHECK).put("onion_url",ONION).put("scope","fresh external Tor Browser emulator UI; not the owner phone").put("browser_version",TorSpace.status(app).optString("browser_version")).put("timestamp_ms",System.currentTimeMillis()));
            app.startActivity(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        assertFalse("An external test must not fabricate CHK connection visibility",TorSpace.status(app).getBoolean("connection_verified"));assertFalse(BrowserPolicy.webUrl(ONION));assertTrue(BrowserPolicy.webUrl("https://example.com/"));
    }
    @Test public void onionIsRejectedInNormalNetworkAndErrorsRemainExplicit()throws Exception{
        for(String value:new String[]{ONION,"http://TEST.ONION./","https://test.onion/x"})assertFalse(value,BrowserPolicy.webUrl(value));assertTrue(BrowserPolicy.webUrl("https://onion.example.com/"));
        CountDownLatch done=new CountDownLatch(1);boolean[] ok={true};TorSpace.command(app,"open",new JSONObject().put("url",ONION),(success,text)->{ok[0]=success;done.countDown();});assertTrue(done.await(5,TimeUnit.SECONDS));assertFalse(ok[0]);assertEquals("error",TorSpace.status(app).getString("state"));assertFalse(TorSpace.status(app).getBoolean("normal_web_uses_tor"));
    }
    private List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root){List<AccessibilityNodeInfo> out=new ArrayList<>();ArrayDeque<AccessibilityNodeInfo> todo=new ArrayDeque<>();todo.add(root);while(!todo.isEmpty()&&out.size()<6000){AccessibilityNodeInfo n=todo.removeFirst();out.add(n);for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)todo.add(c);}}return out;}
    private String text(List<AccessibilityNodeInfo> nodes){StringBuilder text=new StringBuilder();for(AccessibilityNodeInfo n:nodes)if(!n.isPassword()){if(n.getText()!=null)text.append(n.getText()).append(' ');if(n.getContentDescription()!=null)text.append(n.getContentDescription()).append(' ');}return text.toString();}
    private boolean click(List<AccessibilityNodeInfo> nodes,String label){for(AccessibilityNodeInfo n:nodes){CharSequence t=n.getText(),d=n.getContentDescription();if(label.contentEquals(t==null?"":t)||label.contentEquals(d==null?"":d)){AccessibilityNodeInfo target=n;for(int i=0;i<5&&target!=null;i++,target=target.getParent())if(target.isClickable()&&target.isEnabled())return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);}}return false;}
    private void snapshot(File folder,String name)throws Exception{Bitmap b=inst.getUiAutomation().takeScreenshot();assertNotNull(b);try(OutputStream out=new FileOutputStream(new File(folder,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
}
