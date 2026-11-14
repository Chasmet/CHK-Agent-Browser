package com.chk.agentbrowser;

import android.content.Context;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** The desktop mode changes the physical Android WebView width, not only the user agent. */
@RunWith(AndroidJUnit4.class)
public final class BrowserViewportIntegrationTest {
    @Test public void desktopAndMobileChangeRealWebViewWidth()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String site="https://play.google.com/console";
        android.content.SharedPreferences pref=context.getSharedPreferences("browser_site_settings",0);
        boolean original=pref.getBoolean("play.google.com:desktop",false);
        int oldWidth=pref.getInt("play.google.com:desktop_width",1366);
        AtomicReference<WebView> reference=new AtomicReference<>();
        try{
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                WebView web=new WebView(context);
                web.getSettings().setJavaScriptEnabled(true);
                web.getSettings().setUseWideViewPort(true);
                web.getSettings().setLoadWithOverviewMode(true);
                reference.set(web);
                BrowserViewport.change(context,site,"desktop",1366);
                BrowserViewport.apply(context,web,site);
                assertEquals(BrowserViewport.dp(context,1366),web.getWidth());
                assertTrue(BrowserViewport.desktop(context,site));
                BrowserViewport.change(context,site,"desktop",1920);
                BrowserViewport.apply(context,web,site);
                assertEquals(BrowserViewport.dp(context,1920),web.getWidth());
                BrowserViewport.change(context,site,"mobile",1366);
                BrowserViewport.apply(context,web,site);
                assertEquals(BrowserViewport.dp(context,540),web.getWidth());
                assertFalse(BrowserViewport.desktop(context,site));
                BrowserViewport.change(context,site,"auto",1366);
                assertTrue(BrowserViewport.desktop(context,site));
            });
        }finally{
            pref.edit().putBoolean("play.google.com:desktop",original)
                .putInt("play.google.com:desktop_width",oldWidth).commit();
            if(reference.get()!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->reference.get().destroy());
        }
    }
}
