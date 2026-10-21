package com.chk.agentbrowser;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Disk checkpoints, bounded chunks and a verified atomic publish. Called on WorkspaceCommands.IO. */
final class WorkspaceTransfer {
    static final int CHUNK=8192;
    static final int UPLOAD_CHUNK=49152;
    private WorkspaceTransfer(){}
    static String hash(File file)throws Exception{
        try(InputStream in=new FileInputStream(file)){return hash(in);}
    }
    static String hash(InputStream in)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;
        while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static JSONObject stat(WorkspaceStore store,String path,boolean digest)throws Exception{
        File f=store.file(path);JSONObject row=new JSONObject().put("path",path).put("exists",f.exists()).put("directory",f.isDirectory()).put("storage","private_phone");
        if(f.exists())row.put("size",f.length()).put("updated",f.lastModified());
        if(digest){if(!f.isFile())throw new IOException("Fichier absent pour SHA-256.");long size=f.length(),modified=f.lastModified();String sha=hash(f);if(size!=f.length()||modified!=f.lastModified())throw new IOException("Fichier modifié pendant le calcul SHA-256.");row.put("sha256",sha);}
        return row;
    }
    static JSONObject command(Context c,String action,JSONObject p,AgentClient.ResultCallback active)throws Exception{
        WorkspaceStore store=new WorkspaceStore(c);
        if(action.equals("files_transfer_read")){
            String path=p.getString("path");File source=store.file(path);
            if(!source.isFile())throw new IOException("Fichier source absent.");
            long size=source.length(),modified=source.lastModified(),offset=p.getLong("offset");
            if(p.getLong("size")!=size||p.getLong("updated")!=modified)throw new IOException("Source modifiée : relire files_hash avant de reprendre.");
            if(offset<0||offset>size)throw new IOException("Position invalide.");
            byte[] bytes=new byte[(int)Math.min(CHUNK,size-offset)];
            try(RandomAccessFile in=new RandomAccessFile(source,"r")){in.seek(offset);in.readFully(bytes);}
            if(size!=source.length()||modified!=source.lastModified())throw new IOException("Source modifiée pendant la lecture.");
            return new JSONObject().put("path",path).put("offset",offset).put("size",size).put("updated",modified)
                .put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("chunk_sha256",hash(new ByteArrayInputStream(bytes)))
                .put("next_offset",offset+bytes.length<size?offset+bytes.length:-1);
        }
        String id=p.getString("id");File journal=AgentFiles.journal(c,"transfers",id),part=new File(journal.getParentFile(),id+".part");
        JSONObject state;
        if(action.equals("files_transfer_begin")){
            String path=p.getString("path"),sha=p.getString("sha256");long size=p.getLong("size");
            if(size<0||size>WorkspaceStore.MAX_IMPORT||!sha.matches("[a-f0-9]{64}"))throw new IOException("Taille 0..512 Mo et SHA-256 minuscule requis.");
            File destination=store.file(path);
            if(journal.exists()){
                state=AgentFiles.read(journal);
                if(!path.equals(state.getString("path"))||size!=state.getLong("size")||!sha.equals(state.getString("sha256")))throw new IOException("Identifiant déjà utilisé pour un autre transfert.");
            }else{
                if(destination.exists()||!destination.getParentFile().isDirectory())throw new IOException("Destination existante ou parent absent.");
                int count=0;long reserved=0;File[] journals=journal.getParentFile().listFiles();
                if(journals!=null)for(File f:journals)if(f.getName().endsWith(".json")){
                    JSONObject prior=AgentFiles.read(f);String status=prior.optString("state");
                    if(status.equals("receiving")||status.equals("committing")){
                        if(path.equals(prior.optString("path")))throw new IOException("Destination réservée par un autre transfert.");
                        count++;reserved+=prior.optLong("size");
                    }
                }
                if(count>=32||reserved+size>2L*1024*1024*1024)throw new IOException("Transferts actifs saturés : terminer ou annuler les précédents.");
                // Publish metadata first. A crash before the first chunk is safely resumable at zero.
                state=new JSONObject().put("id",id).put("path",path).put("size",size).put("sha256",sha).put("received",0).put("state","receiving");
                AgentFiles.save(journal,state);
            }
        }else state=AgentFiles.read(journal);
        if(!action.equals("files_transfer_abort")||state.getString("state").equals("committing"))recover(store,journal,part,state);
        if(action.equals("files_transfer_abort")){
            if(state.getString("state").equals("committed"))throw new IOException("Transfert déjà publié : utiliser la corbeille pour retirer le fichier.");
            if(part.exists()&&!part.delete())throw new IOException("Annulation impossible.");
            state.put("state","cancelled");AgentFiles.save(journal,state);
        }else if(action.equals("files_transfer_write")){
            requireReceiving(state);String encoded=p.getString("base64");
            if(encoded.length()>65536||!encoded.matches("[A-Za-z0-9+/]*={0,2}"))throw new IOException("Bloc Base64 limité à 48 Ko.");
            byte[] bytes=Base64.decode(encoded,Base64.NO_WRAP);long offset=p.getLong("offset"),received=state.getLong("received");
            if(bytes.length<1||bytes.length>UPLOAD_CHUNK||offset<0||offset>received||offset+bytes.length>state.getLong("size"))throw new IOException("Bloc vide, trop grand ou position non contiguë.");
            if(p.has("chunk_sha256")&&!p.getString("chunk_sha256").equals(hash(new ByteArrayInputStream(bytes))))throw new IOException("SHA-256 du bloc incorrect.");
            if(!active.isActive())throw new IOException("Transfert arrêté avant écriture.");
            try(RandomAccessFile out=new RandomAccessFile(part,"rw")){
                if(out.length()<received)throw new IOException("Données de transfert manquantes.");
                out.setLength(received);out.seek(offset);
                if(offset<received){
                    if(offset+bytes.length>received)throw new IOException("Bloc chevauchant la limite confirmée.");
                    byte[] prior=new byte[bytes.length];out.readFully(prior);
                    if(!Arrays.equals(bytes,prior))throw new IOException("Reprise refusée : le bloc déjà reçu diffère.");
                }else{out.write(bytes);out.getFD().sync();state.put("received",received+bytes.length);AgentFiles.save(journal,state);}
            }
        }else if(action.equals("files_transfer_commit")){
            if(!state.getString("state").equals("committed")){
                requireReceiving(state);
                if(state.getLong("received")!=state.getLong("size"))throw new IOException("Transfert incomplet.");
                if(!part.exists()&&state.getLong("size")==0)try(FileOutputStream empty=new FileOutputStream(part)){empty.getFD().sync();}
                if(!state.getString("sha256").equals(hash(part)))throw new IOException("SHA-256 final incorrect : aucun fichier publié.");
                File destination=store.file(state.getString("path"));
                if(destination.exists()||!destination.getParentFile().isDirectory())throw new IOException("Destination occupée ou parent absent.");
                if(!active.isActive())throw new IOException("Transfert arrêté avant publication.");
                state.put("state","committing");AgentFiles.save(journal,state);
                if(!part.renameTo(destination)){state.put("state","receiving");AgentFiles.save(journal,state);throw new IOException("Publication atomique impossible.");}
                recover(store,journal,part,state);
            }
        }else if(!action.equals("files_transfer_status")&&!action.equals("files_transfer_begin"))throw new IOException("Commande transfert inconnue.");
        if(state.getString("state").equals("committed")){
            File target=store.file(state.getString("path"));
            if(!target.isFile()||target.length()!=state.getLong("size")||!hash(target).equals(state.getString("sha256")))throw new IOException("Le fichier publié a changé depuis le transfert.");
        }
        return new JSONObject(state.toString()).put("chunk_bytes",UPLOAD_CHUNK).put("verified",state.getString("state").equals("committed"));
    }
    private static void requireReceiving(JSONObject state)throws Exception{
        if(!state.getString("state").equals("receiving"))throw new IOException("Transfert fermé : "+state.getString("state"));
    }
    private static void recover(WorkspaceStore store,File journal,File part,JSONObject state)throws Exception{
        if(state.getString("state").equals("committing")){
            File target=store.file(state.getString("path"));
            if(!part.exists()&&target.isFile()&&target.length()==state.getLong("size")&&hash(target).equals(state.getString("sha256")))state.put("state","committed");
            else if(part.exists()&&!target.exists())state.put("state","receiving");
            else throw new IOException("Publication interrompue : état ambigu, vérifier les fichiers.");
            AgentFiles.save(journal,state);
        }
        if(state.getString("state").equals("receiving")){
            long received=state.getLong("received");
            if(!part.exists()&&received>0||part.exists()&&part.length()<received)throw new IOException("Bloc confirmé perdu : annuler le transfert.");
            if(part.exists()&&part.length()>received)try(RandomAccessFile f=new RandomAccessFile(part,"rw")){f.setLength(received);f.getFD().sync();}
        }
    }
}
