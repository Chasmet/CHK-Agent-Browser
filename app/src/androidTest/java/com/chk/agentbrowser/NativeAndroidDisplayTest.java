package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NativeAndroidDisplayTest {
    @Test public void packageNamesNeverReachShellWithInjectedArguments() {
        assertTrue(NativeDisplayMcp.validPackage("com.google.android.youtube"));
        assertTrue(NativeDisplayMcp.validPackage("com.zhiliaoapp.musically"));
        assertFalse(NativeDisplayMcp.validPackage("com.test; reboot"));
        assertFalse(NativeDisplayMcp.validPackage("com.test/path"));
        assertFalse(NativeDisplayMcp.validPackage("com.test\nreboot"));
        assertFalse(NativeDisplayMcp.validPackage(""));
        assertFalse(NativeDisplayMcp.validPackage(null));
    }

    @Test public void chkLauncherKeepsClassicAndIntegratedWithoutQemu() {
        Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context app = instrument.getTargetContext();
        Intent intent = new Intent(app, BrowserChooserActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Instrumentation.ActivityMonitor nativeMonitor =
                instrument.addMonitor(NativeAndroidActivity.class.getName(), null, false);
        Activity launcher = instrument.startActivitySync(intent);
        try {
            assertNotNull(launcher.findViewById(R.id.open_classic));
            assertNull(launcher.findViewById(R.id.open_emulator));
            View nativeButton = launcher.findViewById(R.id.open_native_android);
            assertNotNull(nativeButton);
            instrument.runOnMainSync(nativeButton::performClick);
            Activity nativeScreen = nativeMonitor.waitForActivityWithTimeout(8000);
            assertNotNull(nativeScreen);
            try {
                instrument.runOnMainSync(() -> {
                    assertNotNull(nativeScreen.findViewById(R.id.native_screen));
                    assertNotNull(nativeScreen.findViewById(R.id.native_shizuku));
                    assertNotNull(nativeScreen.findViewById(R.id.native_apps));
                    assertNotNull(nativeScreen.findViewById(R.id.native_agent));
                });
            } finally {
                instrument.runOnMainSync(nativeScreen::finish);
            }
        } finally {
            instrument.runOnMainSync(launcher::finish);
            instrument.removeMonitor(nativeMonitor);
        }
    }
}
