package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;


import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AndroidEmulatorWorkspaceTest {
    @Test public void launcherOpensBothSpacesAndSharedTabsBeforeGuestBoot() {
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

    @Test public void unopenedGuestReportsOnDeviceAndStopped() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject status = EmulatorConnection.load(app).status();
        assertEquals("on_device", status.getString("execution"));
        assertFalse(status.getBoolean("running"));
        assertFalse(status.getBoolean("ready"));
    }

    @Test public void uefiFlashUsesTheQcow2VirtualSizeWithoutResizingItsContainer() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File nvram = java.io.File.createTempFile("guest-flash", ".fd", app.getCacheDir());
        try {
            try (java.io.RandomAccessFile flash = new java.io.RandomAccessFile(nvram, "rw")) {
                flash.setLength(334848);
                flash.writeInt(0x514649fb);
                flash.seek(24);
                flash.writeLong(64L * 1024 * 1024);
            }
            assertEquals("qcow2", EmulatorConnection.prepareNvram(nvram));
            assertEquals(334848, nvram.length());
        } finally {
            assertTrue(nvram.delete());
        }
    }

    @Test public void onlyAcceptedMediaAndApksCanBeStagedForIsolatedAndroid() {
        assertTrue(EmulatorConnection.acceptedGuestFile("app-v2.apk"));
        assertTrue(EmulatorConnection.acceptedGuestFile("clip.mp4"));
        assertTrue(EmulatorConnection.acceptedGuestFile("photo.JPG"));
        assertFalse(EmulatorConnection.acceptedGuestFile("../outside.apk"));
        assertFalse(EmulatorConnection.acceptedGuestFile("file.sh"));
        assertFalse(EmulatorConnection.acceptedGuestFile("malware.apk.exe"));
        assertFalse(EmulatorConnection.acceptedGuestFile(""));
    }

    @Test public void bootStatusNeverClaimsOperatingSystemBootWasVerifiedByQemuDisplay() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject status = EmulatorConnection.load(app).status();
        assertFalse(status.getBoolean("guest_boot_verified"));
        assertTrue(status.has("display_ready"));
        assertEquals(0L, status.getLong("boot_elapsed_seconds"));
        JSONObject diagnostic = EmulatorConnection.load(app).diagnostics(app);
        assertTrue(diagnostic.getBoolean("display_does_not_prove_boot"));
        assertTrue(diagnostic.has("image_present"));
        assertTrue(diagnostic.has("engine_present"));
    }

    @Test public void virtualDisplayDetectsLineageThreeDotStartupButNotARealScreen() {
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(320, 200,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(image);
        android.graphics.Paint paint = new android.graphics.Paint();
        try {
            image.eraseColor(android.graphics.Color.BLACK);
            paint.setColor(android.graphics.Color.WHITE);
            paint.setStrokeWidth(3);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            canvas.drawCircle(145, 97, 8, paint);
            canvas.drawCircle(175, 97, 8, paint);
            canvas.drawCircle(160, 120, 8, paint);
            assertTrue(GuestFrame.looksLikeBootAnimation(image));
            paint.setStyle(android.graphics.Paint.Style.FILL);
            canvas.drawRect(0, 0, 320, 38, paint);
            assertFalse(GuestFrame.looksLikeBootAnimation(image));
        } finally {
            image.recycle();
        }
    }

    @Test public void guestScreenDecodesQemuPpmWithoutDroppingLeadingPixelBytes() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File screen = java.io.File.createTempFile("guest-screen", ".ppm", app.getCacheDir());
        try {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(screen)) {
                output.write("P6\n2 1\n255\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                output.write(new byte[] {32, (byte) 129, (byte) 255, 0, 16, (byte) 230});
            }
            android.graphics.Bitmap image = GuestFrame.read(screen);
            try {
                assertEquals(2, image.getWidth());
                assertEquals(1, image.getHeight());
                assertEquals(0xff2081ff, image.getPixel(0, 0));
                assertEquals(0xff0010e6, image.getPixel(1, 0));
            } finally {
                image.recycle();
            }
        } finally {
            assertTrue(screen.delete());
        }
    }
}
