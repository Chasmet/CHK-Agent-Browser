package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android regressions for the fixed selection menu and phone-export boundary. */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceFileShareSaveTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();

    @Test public void moreMenuIsPinnedAndSelectionRetainsFile()throws Exception {
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        WorkspaceStore store=new WorkspaceStore(app);
        String path="export-ui-"+UUID.randomUUID()+".mp4";
        store.writeText(path,"video preview",false);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(
            new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{
                try{
                    activity.findViewById(R.id.nav_files).performClick();
                    Field workspace=MainActivity.class.getDeclaredField("workspace");
                    workspace.setAccessible(true);
                    WorkspacePanel panel=(WorkspacePanel)workspace.get(activity);
                    Field selection=WorkspacePanel.class.getDeclaredField("selected");
                    selection.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Set<String> selected=(Set<String>)selection.get(panel);
                    selected.add(path);
                    Field mode=WorkspacePanel.class.getDeclaredField("selectMode");
                    mode.setAccessible(true);mode.setBoolean(panel,true);
                    Method toolbar=WorkspacePanel.class.getDeclaredMethod("updateSelectionToolbar");
                    toolbar.setAccessible(true);toolbar.invoke(panel);
                    View more=activity.getWindow().getDecorView()
                        .findViewWithTag("workspace_selection_more");
                    assertNotNull("Three-dot actions are always directly visible",more);
                    assertEquals(View.VISIBLE,more.getVisibility());
                    assertTrue("Tap target",more.getWidth()>0||
                        more.getLayoutParams().width>=MobileUi.dp(activity,48));
                    assertTrue(selected.contains(path));
                    assertEquals("video/mp4",store.mime(path));
                }catch(Exception e){throw new AssertionError(e);}
            });
            assertTrue(store.file(path).exists());
        }finally{
            instrumentation.runOnMainSync(activity::finish);
            store.file(path).delete();
        }
    }

    @Test public void rejectedExportDestinationNeverDeletesOriginal()throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);
        String path="no-export-"+UUID.randomUUID()+".txt";
        store.writeText(path,"Protected original",false);
        try{
            try{
                WorkspacePhoneExport.save(app,Collections.singletonList(path),
                    Uri.parse("file:///sdcard/Downloads"));
                fail("Only Android-provided content tree URIs may be used");
            }catch(IOException expected){}
            assertEquals("Protected original",store.readText(path,0).getString("text"));
            try{
                WorkspacePhoneExport.save(app,Arrays.asList(path),
                    Uri.parse("content://bad-provider/not-a-tree"));
                fail("A document tree must be selected through the Android picker");
            }catch(IllegalArgumentException expected){}
            assertEquals("Protected original",store.readText(path,0).getString("text"));
        }finally{store.file(path).delete();}
    }
}
