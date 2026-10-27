package com.chk.agentbrowser;

import android.content.Context;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.RandomAccessFile;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class LargeFilesLocal3Test {
    @Test public void nativeProviderAndChunkReaderPreserveOffsetsAboveFourGigabytes()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();WorkspaceStore store=new WorkspaceStore(app);
        String path="large-"+java.util.UUID.randomUUID()+".bin";File file=store.file(path);
        long position=4_294_967_301L,size=TransferLimits.MAX_FILE_BYTES;
        try{
            // Sparse fixture: 6 GB logical capacity, a few physical blocks, no 6 GB allocation.
            try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(size);out.seek(position);out.write(new byte[]{7,6,5,4});}
            try(Cursor row=app.getContentResolver().query(store.uri(path),null,null,null,null)){
                assertNotNull(row);assertTrue(row.moveToFirst());assertEquals(size,row.getLong(row.getColumnIndexOrThrow(OpenableColumns.SIZE)));
            }
            try(ParcelFileDescriptor descriptor=app.getContentResolver().openFileDescriptor(store.uri(path),"r")){assertNotNull(descriptor);assertEquals(size,descriptor.getStatSize());}
            JSONObject args=new JSONObject().put("path",path).put("offset",position).put("size",size).put("updated",file.lastModified());
            JSONObject result=WorkspaceTransfer.command(app,"files_transfer_read",args,(ok,text)->{});
            byte[] bytes=android.util.Base64.decode(result.getString("base64"),0);
            assertEquals(8192,bytes.length);assertArrayEquals(new byte[]{7,6,5,4},java.util.Arrays.copyOf(bytes,4));
            assertEquals(position+8192,result.getLong("next_offset"));assertEquals(size,result.getLong("size"));
        }finally{file.delete();}
    }
    @Test public void workflowCanReferenceACompleteLargeResultInsteadOfATruncatedExcerpt()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();String text=new String(new char[35000]).replace('\0','x');
        JSONObject reference=new JSONObject(Local3Client.compactWorkflowResult(app,new JSONObject().put("payload",text).toString()));
        File file=new WorkspaceStore(app).file(reference.getString("result_file"));
        try{
            JSONObject state=new JSONObject().put("steps",new JSONArray().put(new JSONObject().put("id","large").put("state","done").put("result",reference)));
            java.lang.reflect.Method resolve=AgentTasks.class.getDeclaredMethod("resolve",Context.class,Object.class,JSONObject.class);resolve.setAccessible(true);
            assertEquals(text,resolve.invoke(null,app,"$large.payload",state));assertFalse(reference.getBoolean("truncated"));
        }finally{file.delete();}
    }
    @Test public void everyAdvertisedStudioAliasHasAnActualEngineAction(){
        assertEquals("video_editor_project_new",Local3Commands.canonicalAction("video_editor_project_create"));
        assertEquals("video_editor_project_save",Local3Commands.canonicalAction("video_editor_project_update"));
        assertEquals("video_editor_export",Local3Commands.canonicalAction("video_editor_render"));
    }
}
