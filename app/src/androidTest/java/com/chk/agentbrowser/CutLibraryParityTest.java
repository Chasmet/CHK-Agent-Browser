package com.chk.agentbrowser;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.UUID;
import static org.junit.Assert.*;

/** Regression: library organization cannot lose videos or orphan scheduled reminders. */
@RunWith(AndroidJUnit4.class)
public final class CutLibraryParityTest {
    @Test public void collectionsNotesTrackingSurviveRecreation() throws Exception {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        CutLibraryState library=new CutLibraryState(ctx);
        String original="lot_"+UUID.randomUUID().toString().substring(0,8);
        String renamed=original+"_new";
        String id=library.createGroup("Mes clips "+original);
        try{
            library.assign(original,id);
            library.setNote("folder:"+original,"Notes personnelles");
            library.setTracked("CutVideo/"+original+"/cut_01.mp4","youtube",true);
            library.renameFolder(original,renamed);
            CutLibraryState reloaded=new CutLibraryState(ctx);
            assertEquals(id,reloaded.groupFor(renamed));
            assertEquals("",reloaded.groupFor(original));
            assertEquals("Notes personnelles",reloaded.note("folder:"+renamed));
            assertTrue(reloaded.isTracked("CutVideo/"+renamed+"/cut_01.mp4","youtube"));
            reloaded.renameVideo("CutVideo/"+renamed+"/cut_01.mp4","CutVideo/"+renamed+"/renamed.mp4");
            assertTrue(new CutLibraryState(ctx).isTracked("CutVideo/"+renamed+"/renamed.mp4","youtube"));
            reloaded.deleteGroup(id);
            assertEquals("",new CutLibraryState(ctx).groupFor(renamed));
            assertEquals("Notes personnelles",new CutLibraryState(ctx).note("folder:"+renamed));
        }finally{
            library.deleteGroup(id);
        }
    }
    @Test public void schedulePathsFollowRenamesAndTrashCancelsReminders() throws Exception {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore workspace=new WorkspaceStore(ctx);
        try{workspace.mkdir("CutVideo");}catch(java.io.IOException e){assertTrue(workspace.file("CutVideo").isDirectory());}
        String folder="CutVideo/parity_"+UUID.randomUUID().toString().substring(0,8);
        workspace.mkdir(folder);
        String old=folder+"/cut_01.mp4", renamed=folder+"/cut_renamed.mp4",id=null;
        File original=workspace.file(old), second=workspace.file(renamed);
        try{
            assertTrue(original.createNewFile());
            JSONObject entry=new JSONObject().put("path",old).put("platform","youtube")
                .put("account","chknoirshadow").put("title","Test Cut")
                .put("description","").put("hashtags","#test")
                .put("at",System.currentTimeMillis()+86400000L).put("visibility","public")
                .put("published",false);
            CutStudioStore.save(ctx,entry);id=entry.getString("id");
            assertTrue(original.renameTo(second));
            CutStudioStore.rewritePaths(ctx,old,renamed);
            assertEquals(renamed,find(ctx,id).optString("path"));
            CutStudioStore.removeForPath(ctx,folder+"/");
            assertNull(find(ctx,id));
            assertTrue(second.exists());
        }finally{
            if(id!=null)CutStudioStore.remove(ctx,id);
            original.delete();second.delete();workspace.file(folder).delete();
        }
    }
    private static JSONObject find(Context ctx,String id){
        for(JSONObject item:CutStudioStore.list(ctx))if(id.equals(item.optString("id")))return item;
        return null;
    }
}
