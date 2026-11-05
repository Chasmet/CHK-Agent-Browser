package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.UUID;
import static org.junit.Assert.*;

/** Ensure local sidecar share captions cannot overwrite or publish existing schedules. */
@RunWith(AndroidJUnit4.class)
public final class CutShareMetadataTest {
    @Test public void loadsPerPlatformAndLimitsCaptionLength() throws Exception {
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(ctx);
        String folder="CutVideo/test_metadata_"+UUID.randomUUID().toString().substring(0,8);
        try{store.mkdir("CutVideo");}catch(java.io.IOException e){
            assertTrue(store.file("CutVideo").isDirectory());
        }
        store.mkdir(folder);
        File mp4=store.file(folder+"/sample.mp4");
        try{
            assertTrue(mp4.createNewFile());
            JSONObject youtube=new JSONObject().put("title","Anunnaki Urus")
                .put("description","Cité et vaisseaux.")
                .put("hashtags","#Anunnaki #Shorts");
            JSONObject tiktok=new JSONObject().put("title","Urus en marche")
                .put("description","Un monde IA.")
                .put("hashtags","#Anunnaki");
            JSONObject item=new JSONObject().put("file","sample.mp4")
                .put("platforms",new JSONObject().put("youtube",youtube).put("tiktok",tiktok));
            store.writeText(folder+"/metadonnees.json",
                new JSONObject().put("items",new JSONArray().put(item)).toString(),false);
            String path=folder+"/sample.mp4";
            JSONObject y=CutShareMetadata.forVideo(store,path,"youtube");
            assertEquals("Anunnaki Urus",y.getString("title"));
            assertEquals("Anunnaki Urus\nCité et vaisseaux.\n#Anunnaki #Shorts",y.getString("text"));
            assertEquals("Urus en marche",CutShareMetadata.forVideo(store,path,"tiktok").getString("title"));
            try{
                CutShareMetadata.forVideo(store,path,"instagram");
                fail("Missing platform metadata must fail");
            }catch(java.io.IOException expected){}
            youtube.put("title","Cette publication est trop longue et elle doit être refusée si elle dépasse cent caractères au total");
            store.writeText(folder+"/metadonnees.json",
                new JSONObject().put("items",new JSONArray().put(item)).toString(),true);
            try{
                CutShareMetadata.forVideo(store,path,"youtube");fail("Overlong metadata must fail");
            }catch(java.io.IOException expected){}
        }finally{
            mp4.delete();
            store.file(folder+"/metadonnees.json").delete();
            store.file(folder).delete();
        }
    }
    @Test public void metadataBeyondPreviewPageIsReadInFull() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(context);
        try{store.mkdir("CutVideo");}catch(java.io.IOException expected){}
        String folder="CutVideo/long_meta_"+UUID.randomUUID().toString().substring(0,8);
        store.mkdir(folder);
        File video=store.file(folder+"/dernier.mp4");
        try{
            assertTrue(video.createNewFile());
            JSONArray items=new JSONArray();
            for(int i=0;i<300;i++)items.put(new JSONObject().put("file","autre-"+i+".mp4")
                .put("platforms",new JSONObject().put("youtube",new JSONObject()
                    .put("title","Archives et recherches numéro "+i)
                    .put("description","Une découverte à raconter, avec une voix française.")
                    .put("hashtags","#Archives #Vidéo"))));
            items.put(new JSONObject().put("file","dernier.mp4").put("platforms",
                new JSONObject().put("youtube",new JSONObject().put("title","Les Dalton")
                    .put("description","Le clip complet").put("hashtags","#Dalton"))));
            String payload=new JSONObject().put("items",items).toString(2);
            assertTrue(payload.length()>65536);
            store.writeText(folder+"/metadonnees.json",payload,false);
            JSONObject metadata=CutShareMetadata.forVideo(store,folder+"/dernier.mp4","youtube");
            assertEquals("Les Dalton",metadata.getString("title"));
            assertEquals("Les Dalton\nLe clip complet\n#Dalton",metadata.getString("text"));
        }finally{
            video.delete();store.file(folder+"/metadonnees.json").delete();store.file(folder).delete();
        }
    }
}
