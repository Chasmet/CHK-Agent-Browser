package com.chk.agentbrowser;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;
import static org.junit.Assert.*;

/** Regression : Cut est isolé et garde ses programmations sans toucher à l'appli Cut Vidéo. */
@RunWith(AndroidJUnit4.class)
public final class CutStudioIntegrationTest {
    @Test public void localPlanningPersistsAndValidatesNetworks() throws Exception {
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore files=new WorkspaceStore(ctx);
        try{files.mkdir("CutVideo");}catch(java.io.IOException e){
            assertTrue(files.file("CutVideo").isDirectory());
        }
        String dirname="test_"+UUID.randomUUID().toString().substring(0,8);
        String folder="CutVideo/"+dirname;
        files.mkdir(folder);
        String path=folder+"/cut_01.mp4";
        try {
            assertTrue(files.file(path).createNewFile());
            long future=System.currentTimeMillis()+86400000L;
            JSONObject first=new JSONObject().put("path",path).put("platform","youtube")
                .put("account","chknoirshadow").put("title","Clip test")
                .put("description","Essai").put("hashtags","#video")
                .put("at",future).put("visibility","public").put("published",false);
            CutStudioStore.save(ctx,first);
            String id=first.getString("id");
            assertTrue(CutStudioStore.text(first).contains("#video"));
            assertTrue(files.readText("CutVideo/publications.json",0).getString("text").contains(id));
            assertTrue(CutStudioStore.list(ctx).stream().anyMatch(x->id.equals(x.optString("id"))));
            CutStudioStore.published(ctx,id,true);
            assertTrue(CutStudioStore.list(ctx).stream()
                .filter(x->id.equals(x.optString("id"))).findFirst().get().optBoolean("published"));
            CutStudioStore.published(ctx,id,false);
            JSONObject bad=new JSONObject(first.toString()).put("platform","instagram")
                .put("account","qg").put("id",UUID.randomUUID().toString());
            try{CutStudioStore.save(ctx,bad);fail("QG Instagram must be rejected");}
            catch(IllegalArgumentException expected){}
            JSONObject tooLong=new JSONObject(first.toString())
                .put("title","Un titre beaucoup trop long qui ne respecte pas les règles de métadonnées des réseaux sociaux et des 100 caractères maximum")
                .put("id",UUID.randomUUID().toString());
            try{CutStudioStore.save(ctx,tooLong);fail("Long metadata must be rejected");}
            catch(IllegalArgumentException expected){}
            CutStudioStore.remove(ctx,id);
            assertFalse(CutStudioStore.list(ctx).stream().anyMatch(x->id.equals(x.optString("id"))));
        }finally{
            files.file(path).delete();files.file(folder).delete();
        }
    }

    @Test public void cutTabDoesNotReplaceBrowserOrStudio() throws Exception {
        android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        c.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        MainActivity browser=(MainActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(
            new android.content.Intent(c,MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertNotNull(browser.findViewById(R.id.nav_browser));
            assertNotNull(browser.findViewById(R.id.nav_video));
            assertNotNull(browser.findViewById(R.id.nav_cut));
            browser.findViewById(R.id.nav_cut).performClick();
            browser.finish();
        });
    }
    @Test public void agentSchedulesAreAtomicAndIdempotent() throws Exception {
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore workspace=new WorkspaceStore(ctx);
        try{workspace.mkdir("CutVideo");}catch(java.io.IOException e){
            assertTrue(workspace.file("CutVideo").isDirectory());
        }
        String folder="CutVideo/test_mcp_"+UUID.randomUUID().toString().substring(0,8);
        workspace.mkdir(folder);
        String video=folder+"/cut_01.mp4";
        assertTrue(workspace.file(video).createNewFile());
        String unique=UUID.randomUUID().toString();
        String identifier=null;
        try{
            long next=System.currentTimeMillis()+86400000L;
            JSONObject task=new JSONObject().put("request_id",unique)
                .put("path",video).put("platform","youtube")
                .put("account","chknoirshadow").put("title","Extrait à publier")
                .put("description","Test").put("hashtags","#shorts")
                .put("at",next).put("visibility","public");
            String json=new org.json.JSONArray().put(task).toString();
            JSONObject first=CutStudioStore.importFromAgent(ctx,json);
            identifier=first.getJSONArray("ids").getString(0);
            assertEquals(1,first.getInt("imported"));
            assertFalse(first.getBoolean("platform_publication_confirmed"));
            assertEquals(identifier,CutStudioStore.importFromAgent(ctx,json)
                .getJSONArray("ids").getString(0));
            int count=0;
            for(JSONObject row:CutStudioStore.list(ctx))
                if(identifier.equals(row.optString("id")))count++;
            assertEquals("Relancer un import ne doit pas dupliquer le rappel",1,count);
            JSONObject illegal=new JSONObject(task.toString())
                .put("request_id",UUID.randomUUID().toString())
                .put("path","CutVideo/absent.mp4");
            try{
                CutStudioStore.importFromAgent(ctx,
                    new org.json.JSONArray().put(task).put(illegal).toString());
                fail("L'importation partielle doit être interdite");
            }catch(IllegalArgumentException expected){}
            task.put("published",true);
            try{CutStudioStore.importFromAgent(ctx,new org.json.JSONArray().put(task).toString());
                fail("La validation de publication à distance doit être interdite");
            }catch(IllegalArgumentException expected){}
            assertEquals(1,CutStudioStore.list(ctx).stream()
                .filter(row->unique.equals(row.optString("request_id"))||
                    identifier.equals(row.optString("id"))).count());
        }finally{
            if(identifier!=null)CutStudioStore.remove(ctx,identifier);
            workspace.file(video).delete();workspace.file(folder).delete();
        }
    }

}
