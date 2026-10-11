package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Tests du vrai decoupage a la demande, isole dans CHK Agent Browser. */
@RunWith(AndroidJUnit4.class)
public final class CutStudioAutomationIntegrationTest {

    @Test public void rejectsMissingSourceAndInvalidDurations() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = "missing-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            CutStudioAutomation.submit(context, new JSONObject()
                    .put("request_id", id).put("source_path", "Videos/absent.mp4")
                    .put("seconds", 30).toString());
            fail("Une source inexistante ne doit jamais etre acceptee.");
        } catch (java.io.IOException expected) {}
        try {
            CutStudioAutomation.submit(context, new JSONObject()
                    .put("request_id", id).put("source_path", "Videos/absent.mp4")
                    .put("seconds", 0).toString());
            fail("La duree zero doit etre refusee.");
        } catch (IllegalArgumentException expected) {}
    }

    @Test public void exportsRealLocalClipsAndKeepsSource() throws Exception {
        android.app.Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context context = instrument.getTargetContext();
        WorkspaceStore workspace = new WorkspaceStore(context);
        File root = workspace.file("CutVideo");
        if (!root.exists()) workspace.mkdir("CutVideo");
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String folder = "CutVideo/test-import-" + uid;
        workspace.mkdir(folder);
        String relative = folder + "/original.mp4";
        File staging = new File(context.getCacheDir(), "cut-test-" + uid + ".mp4");
        try (InputStream in = instrument.getContext().getAssets().open("editor-fixture.mp4");
             OutputStream out = new FileOutputStream(staging)) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = in.read(bytes)) > 0) out.write(bytes, 0, count);
        }
        workspace.importUri(Uri.fromFile(staging), relative);
        staging.delete();

        String id = "cut-test-" + uid;
        JSONObject ack = null;
        String outputFolder = "";
        try {
            ack = CutStudioAutomation.submit(context, new JSONObject()
                    .put("request_id", id).put("source_path", relative)
                    .put("seconds", 2).toString());
            outputFolder = ack.getString("output_folder");
            assertEquals(id, ack.getString("request_id"));
            assertEquals(2, ack.getInt("total"));
            assertFalse(ack.getBoolean("platform_publication_confirmed"));
            JSONObject finalStatus = null;
            for (int i = 0; i < 300; i++) {
                JSONObject status = new JSONObject(CutStudioAutomation.statusText(context));
                if ("completed".equals(status.optString("state"))
                        || "failed".equals(status.optString("state"))) {
                    finalStatus = status; break;
                }
                Thread.sleep(250);
            }
            assertNotNull("Export jamais termine", finalStatus);
            assertEquals(finalStatus.toString(), "completed", finalStatus.getString("state"));
            assertEquals(2, finalStatus.getInt("completed"));
            assertTrue("La video source doit rester presente", workspace.file(relative).isFile());
            File[] outputs = workspace.file(outputFolder).listFiles();
            int mp4 = 0;
            if (outputs != null) for (File f : outputs)
                if (f.getName().endsWith(".mp4") && f.length() > 1024) mp4++;
            assertEquals("Deux vrais fichiers MP4 attendus", 2, mp4);
            JSONObject repeated = CutStudioAutomation.submit(context, new JSONObject()
                    .put("request_id", id).put("source_path", relative)
                    .put("seconds", 2).toString());
            assertEquals("completed", repeated.getString("state"));
            assertEquals(outputFolder, repeated.getString("output_folder"));
        } finally {
            try {
                CutStudioAutomation.cancel(context, new JSONObject().put("request_id", id).toString());
            } catch (Exception ignored) {}
            if (!outputFolder.isEmpty()) {
                File[] outputs = workspace.file(outputFolder).listFiles();
                if (outputs != null) for (File file : outputs) file.delete();
                workspace.file(outputFolder).delete();
            }
            workspace.file(relative).delete();
            workspace.file(folder).delete();
        }
    }
}
