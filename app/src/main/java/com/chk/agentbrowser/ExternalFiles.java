package com.chk.agentbrowser;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only persisted directory grants selected by the owner through Android's document picker. */
final class ExternalFiles {
    private ExternalFiles(){}
    static String grantId(Uri tree)throws Exception{return AgentVision.digest(tree.toString()).substring(0,16);}
    static JSONArray grants(Context c)throws Exception{
        JSONArray result=new JSONArray();for(UriPermission grant:c.getContentResolver().getPersistedUriPermissions())if(grant.isReadPermission()&&DocumentsContract.isTreeUri(grant.getUri()))
            result.put(new JSONObject().put("id",grantId(grant.getUri())).put("name",DocumentsContract.getTreeDocumentId(grant.getUri())).put("writable",grant.isWritePermission()));return result;
    }
    static Uri tree(Context c,String id,boolean write)throws Exception{
        for(UriPermission grant:c.getContentResolver().getPersistedUriPermissions())if(grant.isReadPermission()&&DocumentsContract.isTreeUri(grant.getUri())&&grantId(grant.getUri()).equals(id)){
            if(write&&!grant.isWritePermission())throw new Exception("Ce dossier est autorisé en lecture seule.");return grant.getUri();}
        throw new Exception("Dossier externe non autorisé ou accès révoqué.");
    }
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS};
    private static JSONArray children(Context c,Uri tree,Uri parent)throws Exception{
        Uri query=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));JSONArray rows=new JSONArray();
        try(Cursor cursor=c.getContentResolver().query(query,COLUMNS,null,null,null)){
            if(cursor==null)throw new Exception("Fournisseur de fichiers indisponible.");
            while(cursor.moveToNext()){
                if(rows.length()>=2000)throw new Exception("Dossier externe trop grand ; sélectionne un sous-dossier.");
                rows.put(new JSONObject().put("document_id",cursor.getString(0)).put("name",cursor.getString(1)).put("mime_type",cursor.getString(2)).put("size",cursor.getLong(3)).put("flags",cursor.getInt(4)));
            }
        }return rows;
    }
    static Uri resolve(Context c,Uri tree,String path)throws Exception{
        if(path.startsWith("/")||path.contains("\\")||path.length()>700)throw new Exception("Chemin externe relatif requis.");
        Uri current=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        if(path.isEmpty())return current;
        for(String part:path.split("/",-1)){
            if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\0')>=0)throw new Exception("Chemin externe invalide.");
            JSONArray rows=children(c,tree,current);JSONObject found=null;
            for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("name").equals(part)){if(found!=null)throw new Exception("Nom ambigu dans le fournisseur externe.");found=rows.getJSONObject(i);}
            if(found==null)throw new Exception("Fichier externe introuvable : "+part);current=DocumentsContract.buildDocumentUriUsingTree(tree,found.getString("document_id"));
        }return current;
    }
    static void revoke(Context c,String id)throws Exception{
        Uri uri=tree(c,id,false);for(UriPermission grant:c.getContentResolver().getPersistedUriPermissions())if(grant.getUri().equals(uri))c.getContentResolver().releasePersistableUriPermission(uri,(grant.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)|(grant.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0));
    }
    static JSONObject command(Context c,JSONObject p)throws Exception{
        String op=p.getString("operation");
        if(op.equals("external_roots"))return new JSONObject().put("ok",true).put("roots",grants(c));
        if(op.equals("external_request_access")){WorkspaceCommands.UI.post(()->c.startActivity(new Intent(c,ExternalFilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pick",true)));return new JSONObject().put("ok",true).put("access_granted",false).put("owner_action_required",true);}
        String id=p.getString("root_id");
        if(op.equals("external_revoke")){revoke(c,id);return new JSONObject().put("ok",true).put("revoked",true);}
        Uri tree=tree(c,id,op.equals("external_rename")||op.equals("external_export"));String path=p.optString("path","");Uri file=resolve(c,tree,path);
        if(op.equals("external_list")){
            int offset=p.optInt("offset",0);if(offset<0||offset>2000)throw new Exception("Pagination externe invalide.");JSONArray all=children(c,tree,file),rows=new JSONArray();
            for(int i=offset;i<Math.min(offset+50,all.length());i++){JSONObject row=all.getJSONObject(i);rows.put(new JSONObject().put("name",row.getString("name")).put("path",path.isEmpty()?row.getString("name"):path+"/"+row.getString("name")).put("mime_type",row.getString("mime_type")).put("size",row.getLong("size")));}
            return new JSONObject().put("ok",true).put("root_id",id).put("items",rows).put("total",all.length()).put("next_offset",offset+50<all.length()?offset+50:-1);
        }
        if(path.isEmpty()&&!op.equals("external_export"))throw new Exception("Sélectionne un fichier externe.");
        WorkspaceStore store=new WorkspaceStore(c);
        if(op.equals("external_import")){String destination=p.getString("destination");store.importUri(file,destination);return new JSONObject().put("ok",true).put("path",destination);}
        if(op.equals("external_read_text")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getContentResolver().openInputStream(file)){if(in==null)throw new Exception("Fichier inaccessible.");byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new Exception("Lecture externe limitée à 64 Ko.");out.write(b,0,n);}}
            String text=new String(out.toByteArray(),StandardCharsets.UTF_8);int offset=p.optInt("offset",0);if(offset<0||offset>text.length())throw new Exception("Position invalide.");int end=Math.min(offset+12000,text.length());return new JSONObject().put("ok",true).put("text",text.substring(offset,end)).put("next_offset",end<text.length()?end:-1);
        }
        if(op.equals("external_rename")){
            String name=p.getString("name");WorkspacePaths.child("",name);String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";
            JSONArray siblings=children(c,tree,resolve(c,tree,parent));for(int i=0;i<siblings.length();i++)if(siblings.getJSONObject(i).getString("name").equals(name))throw new Exception("Nom externe déjà existant.");
            Uri renamed=DocumentsContract.renameDocument(c.getContentResolver(),file,name);if(renamed==null)throw new Exception("Le fournisseur refuse le renommage.");
            String result=parent.isEmpty()?name:parent+"/"+name;resolve(c,tree,result);return new JSONObject().put("ok",true).put("path",result);
        }
        if(op.equals("external_export")){
            String source=p.getString("source"),name=p.getString("name");WorkspacePaths.child("",name);JSONArray siblings=children(c,tree,file);
            for(int i=0;i<siblings.length();i++)if(siblings.getJSONObject(i).getString("name").equals(name))throw new Exception("Destination externe déjà existante.");
            File input=store.file(source);if(!input.isFile()||input.length()>WorkspaceStore.MAX_IMPORT)throw new Exception("Source invalide ou trop grande.");
            Uri created=DocumentsContract.createDocument(c.getContentResolver(),file,store.mime(source),name);if(created==null)throw new Exception("Création externe refusée.");
            try(InputStream in=new FileInputStream(input);OutputStream out=c.getContentResolver().openOutputStream(created,"w")){if(out==null)throw new Exception("Écriture externe refusée.");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            catch(Exception e){try{DocumentsContract.deleteDocument(c.getContentResolver(),created);}catch(Exception ignored){}throw e;}
            return new JSONObject().put("ok",true).put("exported",name).put("size",input.length());
        }
        throw new Exception("Opération externe inconnue.");
    }
}
