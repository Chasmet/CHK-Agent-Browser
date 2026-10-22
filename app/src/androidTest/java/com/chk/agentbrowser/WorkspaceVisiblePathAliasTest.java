package com.chk.agentbrowser;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The Render MCP can use the same Documents breadcrumb that Android shows. */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceVisiblePathAliasTest {
    @Test public void documentsAliasCanListReadRenameAndRestore() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(app);
        if(!store.file("Categories").isDirectory())store.mkdir("Categories");
        if(!store.file("Categories/Documents").isDirectory())store.mkdir("Categories/Documents");
        String name="agent-visible-"+UUID.randomUUID();
        String canonical="Categories/Documents/"+name,visible="Documents/"+name;
        store.mkdir(canonical);
        try {
            JSONObject saved=store.writeText(canonical+"/old.txt","Fiche de paie",false);
            assertTrue(saved.getBoolean("verified"));
            assertEquals(store.file(canonical).getCanonicalPath(),store.file(visible).getCanonicalPath());
            JSONObject listed=store.list(visible,"",0);
            JSONArray entries=listed.getJSONArray("items");
            assertEquals(1,entries.length());
            assertEquals(visible+"/old.txt",entries.getJSONObject(0).getString("path"));
            assertEquals("Fiche de paie",store.readText(visible+"/old.txt",0).getString("text"));
            assertArrayEquals("Fiche de paie".getBytes(StandardCharsets.UTF_8),
                android.util.Base64.decode(store.readBytes(visible+"/old.txt",0).getString("base64"),
                    android.util.Base64.DEFAULT));
            store.move(visible+"/old.txt",visible+"/renamed.txt");
            assertFalse(store.file(canonical+"/old.txt").exists());
            assertTrue(store.file(canonical+"/renamed.txt").exists());
            String trash=store.trash(visible+"/renamed.txt");
            assertFalse(store.file(visible+"/renamed.txt").exists());
            store.restore(trash);
            assertTrue(store.file(canonical+"/renamed.txt").exists());
            JSONArray aliases=store.list("","",0).getJSONArray("visible_categories");
            boolean documents=false;
            for(int i=0;i<aliases.length();i++)
                documents|="Documents".equals(aliases.getJSONObject(i).getString("path"));
            assertTrue(documents);
        }finally{
            WorkspaceArchive.remove(store.file(canonical));
        }
    }

    @Test public void rejectsTraversalAndLeavesLegacyRootEntriesAccessible() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(app);
        String name="legacy-visible-"+UUID.randomUUID()+".txt";
        try {
            store.writeText(name,"original",false);
            assertEquals("original",store.readText(name,0).getString("text"));
            try {
                store.file("Documents/../"+name);
                fail("Directory traversal must remain forbidden");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("interdit"));
            }
        }finally{
            store.file(name).delete();
        }
    }
}
