package com.chk.agentbrowser;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.UUID;
import static org.junit.Assert.*;

/** Native publication handoff must carry a real MP4 content URI and text metadata. */
@RunWith(AndroidJUnit4.class)
public final class CutPublicationShareTest {
    @Test public void publishIntentContainsVideoUriAndCaptionWithoutMarkingPublished() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore storage=new WorkspaceStore(context);
        try {storage.mkdir("CutVideo");} catch(java.io.IOException ex) {
            assertTrue(storage.file("CutVideo").isDirectory());
        }
        String folder="CutVideo/test_handoff_"+UUID.randomUUID().toString().substring(0,8);
        storage.mkdir(folder);
        File video=storage.file(folder+"/clip.mp4");
        try {
            byte[] bytes="test de lecture du partage MP4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(video)){output.write(bytes);}
            JSONObject entry=new JSONObject().put("path",folder+"/clip.mp4")
                .put("platform","tiktok").put("title","Anunnaki : Urus")
                .put("description","La cité se dévoile.")
                .put("hashtags","#Anunnaki #IA").put("published",false);
            Intent intent=CutPublicationShare.buildIntent(context,storage,entry);
            assertEquals(Intent.ACTION_SEND,intent.getAction());
            assertEquals("video/mp4",intent.getType());
            Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotNull(uri);
            assertEquals("content",uri.getScheme());
            assertEquals("video/mp4",context.getContentResolver().getType(uri));
            try(java.io.InputStream input=context.getContentResolver().openInputStream(uri)){
                assertNotNull(input);
                byte[] received=new byte[bytes.length];
                int count=0;
                while(count<received.length){
                    int read=input.read(received,count,received.length-count);
                    if(read<0)break;
                    count+=read;
                }
                assertEquals(bytes.length,count);
                assertArrayEquals(bytes,received);
            }
            try(android.database.Cursor metadata=context.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE},null,null,null)){
                assertNotNull(metadata);assertTrue(metadata.moveToFirst());
                assertEquals("clip.mp4",metadata.getString(metadata.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)));
                assertEquals(bytes.length,metadata.getLong(metadata.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)));
            }
            assertEquals(context.getPackageName()+".browserfiles",uri.getAuthority());
            assertTrue((intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
            ClipData clip=intent.getClipData();
            assertNotNull(clip);
            assertEquals(uri,clip.getItemAt(0).getUri());
            assertEquals("Anunnaki : Urus",intent.getStringExtra(Intent.EXTRA_SUBJECT));
            assertEquals("Anunnaki : Urus\nLa cité se dévoile.\n#Anunnaki #IA",
                intent.getStringExtra(Intent.EXTRA_TEXT));
            assertEquals("com.zhiliaoapp.musically",CutPublicationShare.targetPackage("tiktok"));
            assertEquals("com.instagram.android",CutPublicationShare.targetPackage("instagram"));
            assertNull(CutPublicationShare.targetPackage("unknown"));
            assertFalse(entry.optBoolean("published"));
        }finally{
            video.delete();
            storage.file(folder).delete();
        }
    }
}
