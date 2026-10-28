package com.chk.agentbrowser;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PrivateWebCaptureTest {
    @Test public void detachedTabProducesRealPixelsWithoutChangingItsIdentity()throws Exception{ captureDetachedTab(false); }
    @Test public void staleObservationIsRefreshedBeforeReturningPixels()throws Exception{ captureDetachedTab(true); }
    private void captureDetachedTab(boolean staleObservation)throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        // Instrumentation disables hardware drawing by default; same setup as Studio encoder tests.
        if(android.os.Build.VERSION.SDK_INT>=29)ins.runOnMainSync(()->android.graphics.HardwareRenderer.setDrawingEnabled(true));
        AtomicReference<WebView> web=new AtomicReference<>();AtomicReference<String> initial=new AtomicReference<>();
        ins.runOnMainSync(()->{WebView w=new WebView(app);w.getSettings().setJavaScriptEnabled(true);w.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(640,1073741824));w.layout(0,0,360,640);web.set(w);initial.set(BrowserTabIds.id(w));});
        try{
            WebFixture.load(ins,web.get(),"https://private-capture.test/", "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body style='margin:0;background:#ff0000'><div style='position:fixed;top:50%;height:50%;width:100%;background:#0000ff'></div><input id='keep' value='session-preserved' style='display:none'></body></html>","!!document.querySelector('#keep')");
            AtomicReference<JSONObject> observed=new AtomicReference<>();CountDownLatch dom=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
            ins.runOnMainSync(()->AgentVision.observeWeb(app,web.get(),new AgentVision.Observation(){public void ready(JSONObject info){observed.set(info);dom.countDown();}public void failed(String e){error.set(e);dom.countDown();}}));assertTrue(dom.await(6,TimeUnit.SECONDS));assertNotNull(error.get(),observed.get());
            if(staleObservation)observed.get().put("revision","deliberately-stale-revision");
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Bitmap> pixels=new AtomicReference<>();
            ins.runOnMainSync(()->PrivateWebCapture.capture(app,web.get(),observed.get(),(ok,text)->{},new PrivateWebCapture.Result(){public void ready(Bitmap bitmap){pixels.set(bitmap);done.countDown();}public void unavailable(String reason){error.set(reason);done.countDown();}}));
            assertTrue(done.await(10,TimeUnit.SECONDS));assertNotNull(error.get(),pixels.get());
            if(staleObservation)assertNotEquals("deliberately-stale-revision",observed.get().getString("revision"));
            Bitmap bitmap=pixels.get();
            int red=bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/4),blue=bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()*3/4);
            assertTrue("Top must contain actual red page pixels: "+red,Color.red(red)>200&&Color.blue(red)<60);
            assertTrue("Bottom must contain actual blue page pixels: "+blue,Color.blue(blue)>200&&Color.red(blue)<60);bitmap.recycle();
            ins.runOnMainSync(()->{assertNull(web.get().getParent());assertEquals(360,web.get().getWidth());assertEquals(640,web.get().getHeight());assertEquals(initial.get(),BrowserTabIds.id(web.get()));assertFalse(web.get().getSettings().getOffscreenPreRaster());});
        }finally{ins.runOnMainSync(()->{BrowserWebState.close(web.get());web.get().destroy();});}
    }
}
