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
