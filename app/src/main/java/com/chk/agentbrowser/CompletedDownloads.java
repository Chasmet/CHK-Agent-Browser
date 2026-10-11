package com.chk.agentbrowser;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** A file being written by DownloadManager is never offered as a completed import. */
final class CompletedDownloads {
    private CompletedDownloads(){}
    static Set<String> unavailable(Context context)throws IOException {
        DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
        if(manager==null)throw new IOException("Gestionnaire de téléchargements indisponible");
        File root=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(root==null)throw new IOException("Stockage des téléchargements indisponible");
        android.content.SharedPreferences updates=context.getSharedPreferences("apk_updates",Context.MODE_PRIVATE);
        Set<String> paths=new HashSet<>();
        try(Cursor rows=manager.query(new DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_PENDING|DownloadManager.STATUS_RUNNING
                |DownloadManager.STATUS_PAUSED|DownloadManager.STATUS_FAILED))){
            if(rows==null)throw new IOException("État des téléchargements inaccessible");
            while(rows.moveToNext()){
                String local=rows.getString(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                if(local==null){
                    // Android can leave LOCAL_URI null before creating the download file.
                    // Our browser requests use the destination filename as their title.
                    addName(root,rows.getString(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),paths);
                    if(rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))==updates.getLong("download_id",-1))
                        addName(root,updates.getString("filename",""),paths);
                    continue;
                }
                Uri uri=Uri.parse(local);
                if(!"file".equals(uri.getScheme())||uri.getPath()==null)continue;
                File file=new File(uri.getPath()).getCanonicalFile();
                if(root.getCanonicalFile().equals(file.getParentFile()))paths.add(file.getPath());
            }
        }catch(RuntimeException e){throw new IOException("État des téléchargements inaccessible",e);}
        return paths;
    }
    private static void addName(File root,String name,Set<String> paths)throws IOException {
        if(name==null||name.isEmpty()||name.contains("/")||name.contains("\\"))return;
        File file=new File(root,name).getCanonicalFile();
        if(root.getCanonicalFile().equals(file.getParentFile()))paths.add(file.getPath());
    }
    static boolean ready(File file,Set<String> unavailable)throws IOException {
        return file.isFile()&&file.length()>0&&!file.getName().startsWith(".")
            &&!unavailable.contains(file.getCanonicalPath());
    }
}
