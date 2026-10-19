package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
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

/** Real Android chooser payloads, per-file read grants and direct selection toolbar. */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceShareTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();

    @Test public void oneVideoSendsActualReadableUriWithoutMutatingFile() throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);
        String name="share-"+UUID.randomUUID()+".mp4";
        store.writeText(name,"sample video fixture",false);
        File file=store.file(name);
        try{
            Intent share=WorkspaceShare.createIntent(app,Collections.singletonList(name));
            assertEquals(Intent.ACTION_SEND,share.getAction());
            assertEquals("video/mp4",share.getType());
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                share.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri uri=share.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotNull(uri);
            assertEquals("content",uri.getScheme());
            ClipData clips=share.getClipData();
            assertNotNull(clips);
            assertEquals(1,clips.getItemCount());
            assertEquals(uri,clips.getItemAt(0).getUri());
            try(InputStream input=app.getContentResolver().openInputStream(uri)){
                assertNotNull(input);
                assertEquals('s',input.read());
            }
            assertTrue("Sharing must not move or rename",file.exists());
        }finally{file.delete();}
    }

    @Test public void mixedSelectedFilesShareWithClipDataForEveryUri() throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);
        String id=UUID.randomUUID().toString();
        String video="share-"+id+".mp4",image="share-"+id+".jpg",text="share-"+id+".txt";
        String[] paths={video,image,text};
        for(String path:paths)store.writeText(path,"fixture",false);
        try{
            Intent multi=WorkspaceShare.createIntent(app,Arrays.asList(paths));
            assertEquals(Intent.ACTION_SEND_MULTIPLE,multi.getAction());
            assertEquals("*/*",multi.getType());
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                multi.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clips=multi.getClipData();
            assertNotNull(clips);
            assertEquals(3,clips.getItemCount());
            java.util.ArrayList<Uri> extras=multi.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            assertNotNull(extras);
            assertEquals(3,extras.size());
            for(int i=0;i<paths.length;i++){
                assertEquals(extras.get(i),clips.getItemAt(i).getUri());
                try(InputStream stream=app.getContentResolver().openInputStream(extras.get(i))){
                    assertNotNull(stream);
                    assertEquals('f',stream.read());
                }
                assertTrue(store.file(paths[i]).isFile());
            }
            assertEquals("image/*",
                WorkspaceShare.createIntent(app,Arrays.asList(image,
                    makeSecondImage(store,id))).getType());
        }finally{
            for(String path:paths)store.file(path).delete();
            store.file("share-"+id+"-2.png").delete();
        }
    }

    private String makeSecondImage(WorkspaceStore store,String id)throws Exception{
        String path="share-"+id+"-2.png";
        store.writeText(path,"fixture",false);
        return path;
    }

    @Test public void missingFoldersAndRepeatedItemsAreRejected() throws Exception {
        WorkspaceStore store=new WorkspaceStore(app);
        String folder="share-"+UUID.randomUUID().toString();
        store.mkdir(folder);
        String file=folder+"/item.txt";
        store.writeText(file,"fixture",false);
        try{
            try{WorkspaceShare.createIntent(app,Collections.singletonList(folder));fail("Folder must not be a file URI");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("dossier"));}
            try{WorkspaceShare.createIntent(app,Arrays.asList(file,file));fail("Duplicate URI must be rejected");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("invalide"));}
            try{WorkspaceShare.createIntent(app,Collections.singletonList(folder+"/missing.mp4"));fail("Missing file");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("introuvable"));}
            assertTrue(store.file(file).isFile());
        }finally{store.file(file).delete();store.file(folder).delete();}
    }

    @Test public void selectionBarExposesPartagerWithoutHidingRename() throws Exception {
        app.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(
            new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{
                try{
                    activity.findViewById(R.id.nav_files).performClick();
                    Field field=MainActivity.class.getDeclaredField("workspace");
                    field.setAccessible(true);
                    WorkspacePanel panel=(WorkspacePanel)field.get(activity);
                    Field selection=WorkspacePanel.class.getDeclaredField("selected");
                    selection.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Set<String> selected=(Set<String>)selection.get(panel);
                    selected.add("test/file.mp4");
                    Field mode=WorkspacePanel.class.getDeclaredField("selectMode");
                    mode.setAccessible(true);
                    mode.setBoolean(panel,true);
                    Method update=WorkspacePanel.class.getDeclaredMethod("updateSelectionToolbar");
                    update.setAccessible(true);
                    update.invoke(panel);
                    View share=activity.getWindow().getDecorView()
                        .findViewWithTag("workspace_share_button");
                    assertNotNull("Partager must be directly visible after selection",share);
                    assertEquals(View.VISIBLE,share.getVisibility());
                    assertTrue(share.isEnabled());
                    TextView count=activity.getWindow().getDecorView()
                        .findViewWithTag("workspace_selected_count");
                    assertNotNull(count);
                    assertEquals("1 sélectionné(s)",count.getText().toString());
                }catch(Exception e){throw new AssertionError(e);}
            });
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
}
