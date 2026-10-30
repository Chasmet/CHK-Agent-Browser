package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Native Android mode must stay off without explicit system consent. */
@RunWith(AndroidJUnit4.class)
public final class NativeSocialSessionTest {
    @Test public void onlyExplicitSocialPackagesAreEligible() {
        assertEquals("com.zhiliaoapp.musically",NativeSocialSession.packageFor("tiktok"));
        assertEquals("com.google.android.youtube",NativeSocialSession.packageFor("youtube"));
        assertEquals("com.instagram.android",NativeSocialSession.packageFor("instagram"));
        assertTrue(NativeSocialSession.allowed("com.twitter.android"));
        assertFalse(NativeSocialSession.allowed("com.chk.agentbrowser"));
        assertFalse(NativeSocialSession.allowed("com.android.settings"));
        assertFalse(NativeSocialSession.allowed("com.android.chrome"));
    }
    @Test public void noAutomaticSessionAfterRestartOrStop() {
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        NativeSocialSession.stop(ctx);
        JSONObject state=NativeSocialSession.current(ctx);
        assertEquals(0,state.length());
        assertFalse(NativeSocialSession.isActive(ctx,"com.zhiliaoapp.musically"));
    }
    @Test public void accessibilityServiceNeedsSystemBindingPermission()throws Exception {
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.pm.ServiceInfo info=ctx.getPackageManager().getServiceInfo(
            new android.content.ComponentName(ctx,NativeSocialControlService.class),0);
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE",info.permission);
        assertNull(NativeSocialControlService.activeService());
    }
}
