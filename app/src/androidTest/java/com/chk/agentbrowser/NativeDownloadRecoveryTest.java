package com.chk.agentbrowser;

import android.app.DownloadManager;
import android.content.Context;
import android.os.Environment;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import static org.junit.Assert.*;

/** Real Android download records and disk verification, without an Internet upload. */
@RunWith(AndroidJUnit4.class)
public final class NativeDownloadRecoveryTest {
    @Test public void completedDownloadCanBeVerifiedImportedAndReadAgainWithoutOverwriting()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="download-"+java.util.UUID.randomUUID(),path=id+".mp4";
        File root=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);assertNotNull(root);root.mkdirs();
        File source=new File(root,path),journal=AgentFiles.journal(app,"downloads",id),target=new WorkspaceStore(app).file(path);
        DownloadManager manager=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);long nativeId=-1;
        try{
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("editor-fixture.mp4");FileOutputStream out=new FileOutputStream(source)){
                byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            String sha=WorkspaceTransfer.hash(source);
            nativeId=manager.addCompletedDownload(path,"CHK test fixture",false,"video/mp4",source.getAbsolutePath(),source.length(),true);
            AgentFiles.save(journal,new JSONObject().put("id",id).put("state","downloading").put("filename",path)
                .put("download_id",nativeId).put("workspace_path",path).put("mime","video/mp4").put("expected_sha256",sha));
            JSONObject result=BrowserDownloads.status(app,id);
            assertEquals("completed",result.getString("state"));assertTrue(result.getBoolean("verified"));
            assertEquals(path,result.getString("path"));assertEquals(sha,WorkspaceTransfer.hash(target));
            long modified=target.lastModified();JSONObject replay=BrowserDownloads.status(app,id);
            assertEquals(nativeId,replay.getLong("download_id"));assertEquals(modified,target.lastModified());
            assertEquals(sha,replay.getString("sha256"));
        }finally{if(nativeId!=-1)manager.remove(nativeId);source.delete();target.delete();journal.delete();}
    }
    @Test public void htmlMasqueradingAsMp4IsNeverImported()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();String id="invalid-"+java.util.UUID.randomUUID(),path=id+".mp4";
        File root=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);assertNotNull(root);root.mkdirs();
        File source=new File(root,path),journal=AgentFiles.journal(app,"downloads",id),target=new WorkspaceStore(app).file(path);
        DownloadManager manager=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);long nativeId=-1;
        try{
            try(FileOutputStream out=new FileOutputStream(source)){out.write("<html>Access denied</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            nativeId=manager.addCompletedDownload(path,"CHK invalid fixture",false,"video/mp4",source.getAbsolutePath(),source.length(),true);
            AgentFiles.save(journal,new JSONObject().put("id",id).put("state","downloading").put("filename",path).put("download_id",nativeId).put("workspace_path",path).put("mime","video/mp4"));
            try{BrowserDownloads.status(app,id);fail("HTML must not be accepted as MP4");}catch(Exception expected){assertTrue(expected.getMessage().contains("MP4"));}
            assertFalse(target.exists());
        }finally{if(nativeId!=-1)manager.remove(nativeId);source.delete();target.delete();journal.delete();}
    }
}
