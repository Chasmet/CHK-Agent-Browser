package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AndroidEmulatorWorkspaceTest {
    @Test public void launcherOpensBothSpacesAndSharedTabsWorkWithoutPc() {
        Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context app = instrument.getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        assertEquals(BrowserChooserActivity.class.getName(), launch.getComponent().getClassName());
        SharedPreferences updates = app.getSharedPreferences("update_monitor", Context.MODE_PRIVATE);
        boolean wasEnabled = updates.getBoolean("enabled", true);
        updates.edit().putBoolean("enabled", false).commit();
        Instrumentation.ActivityMonitor classicMonitor = instrument.addMonitor(MainActivity.class.getName(), null, false);
        Instrumentation.ActivityMonitor androidMonitor = instrument.addMonitor(AndroidEmulatorActivity.class.getName(), null, false);
        Activity home = instrument.startActivitySync(launch);
        try {
            instrument.runOnMainSync(() -> home.findViewById(R.id.open_classic).performClick());
            Activity classic = classicMonitor.waitForActivityWithTimeout(8000);
            assertNotNull(classic);
            instrument.runOnMainSync(() -> {
                assertNotNull(classic.findViewById(R.id.nav_tor));
                assertNotNull(classic.findViewById(R.id.nav_cut));
                classic.finish();
            });
            instrument.waitForIdleSync();
            instrument.runOnMainSync(() -> home.findViewById(R.id.open_emulator).performClick());
            Activity android = androidMonitor.waitForActivityWithTimeout(8000);
            assertNotNull(android);
            instrument.runOnMainSync(() -> {
                try {
                    android.findViewById(R.id.emulator_nav_files).performClick();
                    assertEquals(View.VISIBLE, android.findViewById(R.id.emulator_workspace).getVisibility());
                    android.findViewById(R.id.emulator_nav_notes).performClick();
                    assertEquals(View.GONE, android.findViewById(R.id.emulator_screen).getVisibility());
                    android.findViewById(R.id.emulator_nav_android).performClick();
                    assertEquals(View.VISIBLE, android.findViewById(R.id.emulator_screen).getVisibility());
                    assertNotNull(android.findViewById(R.id.emulator_nav_studio));
                    assertNotNull(android.findViewById(R.id.emulator_nav_cut));
                } finally {
                    android.finish();
                }
            });
        } finally {
            instrument.runOnMainSync(home::finish);
            instrument.removeMonitor(classicMonitor);
            instrument.removeMonitor(androidMonitor);
            updates.edit().putBoolean("enabled", wasEnabled).commit();
        }
    }

    @Test public void androidClientPairsCapturesAndSendsHashedMediaThroughRealAdb() throws Exception {
        String pairing = InstrumentationRegistry.getArguments().getString("chk_emulator_pairing");
        Assume.assumeNotNull(pairing);
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            EmulatorConnection connection = EmulatorConnection.pair(app, pairing);
            assertTrue(EmulatorConnection.load(app).status().getBoolean("ready"));
            EmulatorConnection.Frame frame = connection.screen();
            try {
                assertTrue(frame.bitmap.getWidth() > 0);
                assertEquals(24, frame.id.length());
            } finally {
                frame.bitmap.recycle();
            }
            assertTrue(connection.input(new JSONObject().put("operation", "key").put("key", "home")).getBoolean("input_sent"));
            Bitmap image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally {
                image.recycle();
            }
            byte[] data = output.toByteArray();
            JSONObject media = connection.sendMedia(new ByteArrayInputStream(data), "chk-emulator-ci.png", data.length);
            assertTrue(media.getBoolean("verified"));
            assertEquals(data.length, media.getLong("size"));
            assertTrue(media.getString("path").startsWith("/sdcard/Download/CHK/"));
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) digest.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            assertEquals(digest.toString(), media.getString("sha256"));
        } finally {
            EmulatorConnection.forget(app);
        }
    }
}
