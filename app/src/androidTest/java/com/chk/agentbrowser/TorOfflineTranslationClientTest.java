package com.chk.agentbrowser;

import android.content.*;
import android.content.pm.ServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** A translator crash cannot terminate the Tor browser because it runs in
 * a distinct private service process. Never downloads models in CI. */
@RunWith(AndroidJUnit4.class)
public final class TorOfflineTranslationClientTest {
    /** ML Kit's InitProvider runs in main only; the worker must explicitly
     * initialize ML Kit rather than returning TRANSLATION_WORKER_ERROR.
     * No model download is triggered and either ready/not-ready is valid.
     */
    @Test public void workerCanQueryMlKitModelsWithoutInitializationException()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<String> result=new AtomicReference<>();
        AtomicBoolean installed=new AtomicBoolean();
        TorOfflineTranslationClient.isReady(app,(ready,detail)->{
            installed.set(ready);result.set(detail);latch.countDown();
        });
        assertTrue("ML Kit readiness IPC timed out",latch.await(30,TimeUnit.SECONDS));
        String detail=result.get();
        assertNotNull("Model readiness must return a useful response",detail);
        assertFalse("ML Kit initialization failed inside private worker: "+detail,
            detail.contains("TRANSLATION_WORKER_ERROR")
              || detail.contains("MlKitContext has not been initialized")
              || detail.contains("TRANSLATION_SERVICE_DISCONNECTED")
              || detail.contains("TRANSLATION_BINDING_DIED"));
    }

    @Test public void workerIsPrivateAndSeparateAndRespondsOverBinder()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ServiceInfo info=app.getPackageManager().getServiceInfo(
            new ComponentName(app,TorOfflineTranslationService.class),0);
        assertNotNull(info);
        assertFalse("Translation worker must never be exported",info.exported);
        assertEquals(app.getPackageName()+":tortranslate",info.processName);
        CountDownLatch latch=new CountDownLatch(1);
        AtomicBoolean ready=new AtomicBoolean();
        AtomicReference<String> response=new AtomicReference<>();
        TorOfflineTranslationClient.ping(app,(ok,message)->{
            ready.set(ok);response.set(message);latch.countDown();
        });
        assertTrue("Isolated translator process did not respond",latch.await(30,TimeUnit.SECONDS));
        assertTrue("Translator worker failed: "+response.get(),ready.get());
        assertEquals("TRANSLATION_WORKER_READY",response.get());
    }
}
