package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Non-destructive regression tests for the MCP session handoff and virtual file list. */
@RunWith(AndroidJUnit4.class)
public final class SessionAndVirtualFilesTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();

    @Test public void visiblePauseNeverOverwritesAutonomousPage() throws Exception {
        Context app=instrument.getTargetContext();
        SharedPreferences prefs=app.getSharedPreferences("browser_mcp_v1",0);
        String autonomous=prefs.getString("last_page","");
        String visible=prefs.getString("last_visible_page","");
        try {
            prefs.edit().putString("last_page","https://example.com/agent/edit")
                .remove("last_visible_page").commit();
            AgentService.syncVisible(app,"https://example.com/owner/form");
            assertEquals("https://example.com/agent/edit",prefs.getString("last_page",""));
            assertEquals("https://example.com/owner/form",
                AgentService.latestVisibleUrl(app));
            AgentService.syncVisible(app,"http://192.168.1.10/private");
            assertEquals("https://example.com/owner/form",
                AgentService.latestVisibleUrl(app));
        }finally {
            prefs.edit().putString("last_page",autonomous)
                .putString("last_visible_page",visible).commit();
        }
    }

    @Test public void recyclerKeepsFolderRowsAndSelections() throws Exception {
        Context app=instrument.getTargetContext();
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrument.startActivitySync(
            new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            AtomicInteger changed=new AtomicInteger();
            instrument.runOnMainSync(()->{
                RecyclerView recycler=new RecyclerView(activity);
                androidx.recyclerview.widget.GridLayoutManager manager=
                    new androidx.recyclerview.widget.GridLayoutManager(activity,3);
                LinkedHashSet<String> selection=new LinkedHashSet<>();
                WorkspaceFileAdapter adapter=new WorkspaceFileAdapter(activity,selection,
                    new WorkspaceFileAdapter.Actions(){
                        public void openFolder(String path){}
                        public void openFile(String path){}
                        public void menu(String path){}
                        public void selectedChanged(){changed.incrementAndGet();}
                    });
                recycler.setLayoutManager(manager);
                recycler.setAdapter(adapter);
                JSONArray rows=new JSONArray();
                rows.put(new JSONObject().put("path","test/folder").put("name","folder")
                    .put("mime_type","inode/directory").put("directory",true).put("size",0));
                for(int i=0;i<80;i++){
                    rows.put(new JSONObject().put("path","test/text-"+i+".txt")
                        .put("name","text-"+i+".txt").put("mime_type","text/plain")
                        .put("directory",false).put("size",100+i));
                }
                adapter.submit(rows,true,true,1);
                assertEquals(81,adapter.getItemCount());
                assertTrue(adapter.fullSpan(0));
                assertFalse(adapter.fullSpan(1));
                adapter.selectionMode(true);
                adapter.submit(rows,false,true,2);
                assertTrue(adapter.fullSpan(1));
                assertEquals(81,adapter.getItemCount());
                recycler.setAdapter(null);
            });
            assertEquals(0,changed.get());
        }finally{
            instrument.runOnMainSync(activity::finish);
        }
    }
}
