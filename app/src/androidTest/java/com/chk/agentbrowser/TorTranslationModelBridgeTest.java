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

/** Verifies Tor's model installer is a private bound IPC, with no network request.
 * This test deliberately does not download models or inspect browsing contents.
 */
@RunWith(AndroidJUnit4.class)
public final class TorTranslationModelBridgeTest {
    @Test public void privateModelInstallerRespondsWithoutLeavingApp()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ServiceInfo info=app.getPackageManager().getServiceInfo(
            new ComponentName(app,TorTranslationModelService.class),0);
        assertNotNull(info);
        assertFalse("Tor translator model bridge must not be exported",info.exported);
        assertEquals(app.getPackageName(),info.processName);
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<String> reply=new AtomicReference<>();
        AtomicBoolean ok=new AtomicBoolean();
        TorTranslationModelBridge.ping(app,(success,detail)->{
            ok.set(success);reply.set(detail);latch.countDown();
        });
        assertTrue("Private model service response timeout",latch.await(30,TimeUnit.SECONDS));
        assertTrue("Ping failed: "+reply.get(),ok.get());
        assertEquals("MODEL_SERVICE_READY",reply.get());
    }
}
