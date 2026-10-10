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
            JSONObject xDraft=new JSONObject(first.toString()).put("platform","x")
                .put("account","chknoirshadow").put("id",UUID.randomUUID().toString())
                .put("at",System.currentTimeMillis()-5000L);
            CutStudioStore.save(ctx,xDraft);
            assertTrue(CutStudioStore.list(ctx).stream()
                .anyMatch(x->xDraft.optString("id").equals(x.optString("id"))));
            CutStudioStore.remove(ctx,xDraft.getString("id"));
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
}
