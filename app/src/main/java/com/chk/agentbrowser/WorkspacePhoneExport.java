package com.chk.agentbrowser;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Export local workspace files to a user-selected Android SAF directory.
 * Nothing is removed or renamed in the original CHK workspace.
 */
final class WorkspacePhoneExport {
    private static final int MAX_ENTRIES=5000;
    private static final int MAX_DEPTH=24;
    private static final int BUFFER_SIZE=65536;

    private WorkspacePhoneExport(){}

    static String save(Context context,List<String> paths,Uri treeUri)throws Exception{
        if(paths==null||paths.isEmpty())throw new IOException("Aucun fichier sélectionné");
        if(treeUri==null||!"content".equals(treeUri.getScheme()))
            throw new IOException("Dossier Android non autorisé");
        ContentResolver resolver=context.getContentResolver();
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(treeUri,
            DocumentsContract.getTreeDocumentId(treeUri));
        WorkspaceStore store=new WorkspaceStore(context);
        Totals totals=new Totals();
        for(String path:paths){
            try{
                File source=store.file(path);
                if(!source.exists())throw new IOException("Fichier introuvable");
                copy(context,store,resolver,source,path,parent,totals,0);
            }catch(Exception e){
                totals.failed++;
                if(totals.firstError==null)totals.firstError=e.getMessage();
            }
        }
        return totals.files+" fichier(s) et "+totals.folders+
            " dossier(s) enregistrés sur le téléphone"+
            (totals.failed==0?"":" · "+totals.failed+" échec(s) : "+totals.firstError);
    }

    private static void copy(Context context,WorkspaceStore store,ContentResolver resolver,
                             File source,String workspacePath,Uri parent,Totals totals,int depth)
        throws Exception {
        if(depth>MAX_DEPTH||totals.visited++>=MAX_ENTRIES)
            throw new IOException("Export limité à "+MAX_ENTRIES+" éléments ou "+MAX_DEPTH+" niveaux");
        // Always revalidate canonical workspace containment for every recursive child.
        File checked=store.file(workspacePath);
        if(!checked.exists())throw new IOException("Fichier source inaccessible");
        if(checked.isDirectory()){
            Uri directory=DocumentsContract.createDocument(resolver,parent,
                DocumentsContract.Document.MIME_TYPE_DIR,checked.getName());
            if(directory==null)throw new IOException("Impossible de créer "+checked.getName());
            totals.folders++;
            File[] children=checked.listFiles();
            if(children==null)throw new IOException("Dossier illisible : "+checked.getName());
            for(File child:children){
                try{
                    String next=WorkspacePaths.child(workspacePath,child.getName());
                    copy(context,store,resolver,child,next,directory,totals,depth+1);
                }catch(Exception e){
                    totals.failed++;
                    if(totals.firstError==null)totals.firstError=e.getMessage();
                }
            }
            return;
        }
        Uri destination=DocumentsContract.createDocument(resolver,parent,
            store.mime(workspacePath),checked.getName());
        if(destination==null)throw new IOException("Création refusée : "+checked.getName());
        boolean saved=false;
        try(FileInputStream input=new FileInputStream(checked);
            OutputStream output=resolver.openOutputStream(destination,"w")){
            if(output==null)throw new IOException("Écriture interdite : "+checked.getName());
            byte[] buffer=new byte[BUFFER_SIZE];
            int n;
            while((n=input.read(buffer))!=-1)output.write(buffer,0,n);
            output.flush();
            saved=true;
            totals.files++;
        }finally{
            if(!saved)try{DocumentsContract.deleteDocument(resolver,destination);}
                catch(Exception ignored){}
        }
    }

    private static final class Totals {
        int files,folders,failed,visited;
        String firstError;
    }
}
