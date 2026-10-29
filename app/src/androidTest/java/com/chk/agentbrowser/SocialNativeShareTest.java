package com.chk.agentbrowser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

/** Local-only regression tests. No installed social app or remote publication required. */
@RunWith(AndroidJUnit4.class)
public final class SocialNativeShareTest {
    @Test public void videoShareReadsThePreparedMp4WithoutChangingPublicationState() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String folder = "SocialShareTest" + unique;
        String path = folder + "/video1.mp4";
        String id = "socialshare" + unique;
        WorkspaceStore store = new WorkspaceStore(app);
        store.mkdir(folder);
        File video = store.file(path);
        File journal = AgentFiles.journal(app, "publications", id);
        try {
            try (FileOutputStream out = new FileOutputStream(video)) {
                out.write(new byte[] {1, 2, 3, 4, 5, 6});
            }
            JSONObject prepared = PublicationJobs.command(app, PublicationJobs.ACTION_PREPARE,
                new JSONObject()
                    .put("id", id)
                    .put("platform", "tiktok")
                    .put("destination_url", "https://www.tiktok.com/tiktokstudio/upload")
                    .put("title", "Brume 2")
                    .put("caption", "Extrait officiel #CHK")
                    .put("media_paths", new JSONArray().put(path)));
            assertEquals("prepared", prepared.getString("status"));
            Intent share = SocialNativeShare.buildShare(app, prepared);
            assertEquals(Intent.ACTION_SEND, share.getAction());
            assertEquals("video/mp4", share.getType());
            assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            Uri uri = share.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotNull(uri);
            assertEquals("content", uri.getScheme());
            assertEquals(store.uri(path), uri);
            assertEquals("prepared", PublicationJobs.command(app, PublicationJobs.ACTION_STATUS,
                new JSONObject().put("id", id)).getString("status"));
        } finally {
            journal.delete();
            video.delete();
            store.file(folder).delete();
        }
    }

    @Test public void alteredFileCannotBeSilentlyTransmitted() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject fake=new JSONObject()
            .put("platform","tiktok").put("media",
                new JSONArray().put(new JSONObject()
                    .put("path","never-existing/video1.mp4").put("size",123)));
        try {
            SocialNativeShare.buildShare(app,fake);
            fail("Un fichier absent ne doit jamais être partagé.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("disparu") ||
                expected.getMessage().contains("changé"));
        }
    }
}
