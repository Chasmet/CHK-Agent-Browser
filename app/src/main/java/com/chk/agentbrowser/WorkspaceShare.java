package com.chk.agentbrowser;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Android share sheet for one or many existing workspace files.
 * Content URIs remain read-only and temporary; files are never moved or copied.
 */
final class WorkspaceShare {
    private static final int MAX_FILES=100;

    private WorkspaceShare(){}

    static Intent createIntent(Context context,List<String> paths)throws IOException {
        if(paths==null||paths.isEmpty())
            throw new IOException("Sélectionne au moins un fichier à partager");
        if(paths.size()>MAX_FILES)
            throw new IOException("Partage limité à "+MAX_FILES+" fichiers à la fois");

        WorkspaceStore store=new WorkspaceStore(context);
        ArrayList<Uri> uris=new ArrayList<>();
        Set<String> unique=new HashSet<>();
        String firstMime=null,firstMajor=null;
        boolean sameMime=true,sameMajor=true;

        for(String path:paths){
            if(path==null||!unique.add(path))
                throw new IOException("Sélection de fichiers invalide");
            File file=store.file(path);
            if(file.isDirectory())
                throw new IOException("Pour partager un dossier, ouvre-le et sélectionne ses fichiers");
            if(!file.isFile()||!file.canRead())
                throw new IOException("Fichier introuvable : "+path);
            uris.add(store.uri(path));
            String mime=store.mime(path);
            if(firstMime==null){
                firstMime=mime;
                firstMajor=mime.substring(0,mime.indexOf('/'));
            }else{
                if(!firstMime.equals(mime))sameMime=false;
                if(!firstMajor.equals(mime.substring(0,mime.indexOf('/'))))sameMajor=false;
            }
        }

        String mime=sameMime?firstMime:(sameMajor?firstMajor+"/*":"*/*");
        Intent intent=new Intent(uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);
        intent.setType(mime);
        if(uris.size()==1)intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);

        // ClipData is essential for Android 5+ to grant each recipient access.
        ClipData clips=ClipData.newUri(context.getContentResolver(),"Fichiers CHK",uris.get(0));
        for(int i=1;i<uris.size();i++)clips.addItem(new ClipData.Item(uris.get(i)));
        intent.setClipData(clips);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }
}
