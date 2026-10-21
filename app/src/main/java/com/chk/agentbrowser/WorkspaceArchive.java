package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Archives publish from a staging area, never merge into or overwrite an existing destination. */
final class WorkspaceArchive {
    private static final int MAX_ENTRIES=5000,MAX_DEPTH=24;
    private WorkspaceArchive(){}
    static JSONObject copy(Context c,String path,String destination,AgentClient.ResultCallback active)throws Exception{
        WorkspaceStore store=new WorkspaceStore(c);File source=store.file(path),target=store.file(destination);
        if(!source.exists()||target.exists()||!target.getParentFile().isDirectory()||target.getCanonicalPath().startsWith(source.getCanonicalPath()+File.separator))throw new IOException("Copie : source absente, destination existante ou récursive.");
        File stage=new File(target.getParentFile(),".copy-"+UUID.randomUUID());Counter count=new Counter(active);
        try{
            copyTree(store,path,stage,count,0);count.check();if(target.exists()||!stage.renameTo(target))throw new IOException("Publication de la copie impossible.");
            JSONObject result=new JSONObject().put("path",destination).put("exists",true).put("directory",target.isDirectory()).put("entries",count.entries).put("verified",true);
            if(target.isFile())result.put("sha256",WorkspaceTransfer.hash(target)).put("size",target.length());return result;
        }finally{remove(stage);}
    }
    private static void copyTree(WorkspaceStore store,String path,File target,Counter count,int depth)throws Exception{
        if(depth>MAX_DEPTH)throw new IOException("Copie trop profonde.");count.entry();File source=store.file(path);
        if(source.isDirectory()){
            if(!target.mkdir())throw new IOException("Création de la copie impossible.");File[] children=source.listFiles();if(children==null)throw new IOException("Dossier illisible.");
            for(File child:children)copyTree(store,WorkspacePaths.child(path,child.getName()),new File(target,child.getName()),count,depth+1);
        }else{
            long size=source.length(),modified=source.lastModified();
            try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target)){copy(in,out,count);out.getFD().sync();}
            if(size!=source.length()||modified!=source.lastModified()||!WorkspaceTransfer.hash(source).equals(WorkspaceTransfer.hash(target)))throw new IOException("Copie non confirmée : source modifiée ou empreinte différente.");
        }
    }
    static JSONObject command(Context c,String action,JSONObject p,AgentClient.ResultCallback active)throws Exception{
        WorkspaceStore store=new WorkspaceStore(c);String path=p.getString("path"),destination=p.getString("destination");
        File source=store.file(path),target=store.file(destination);
        if(!source.exists()||target.exists()||!target.getParentFile().isDirectory())throw new IOException("Source absente, destination occupée ou parent absent.");
        if(target.getCanonicalPath().startsWith(source.getCanonicalPath()+File.separator))throw new IOException("Une archive ne peut pas être créée à l’intérieur de sa source.");
        File stage=new File(target.getParentFile(),".archive-"+UUID.randomUUID());Counter count=new Counter(active);
        try{
            if(action.equals("files_zip")){
                if(!destination.toLowerCase(Locale.ROOT).endsWith(".zip"))throw new IOException("Destination ZIP requise.");
                try(FileOutputStream file=new FileOutputStream(stage);ZipOutputStream zip=new ZipOutputStream(file)){
                    add(store,path,source.getName(),zip,count,0);zip.finish();file.getFD().sync();
                }
                if(stage.length()>WorkspaceStore.MAX_IMPORT)throw new IOException("Archive supérieure à 512 Mo.");
            }else if(action.equals("files_unzip")){
                if(!source.isFile()||source.length()>WorkspaceStore.MAX_IMPORT||!stage.mkdir())throw new IOException("Archive inaccessible ou trop grande.");
                Set<String> names=new HashSet<>();
                try(ZipInputStream zip=new ZipInputStream(new FileInputStream(source))){ZipEntry entry;
                    while((entry=zip.getNextEntry())!=null){
                        count.entry();String name=entry.getName();if(entry.isDirectory()&&name.endsWith("/"))name=name.substring(0,name.length()-1);
                        if(name.isEmpty()||name.split("/",-1).length>MAX_DEPTH||!names.add(name))throw new IOException("Entrée ZIP vide, répétée ou trop profonde.");
                        File output=WorkspacePaths.resolve(stage,name,false);
                        if(!output.getParentFile().isDirectory()&&!output.getParentFile().mkdirs())throw new IOException("Parent ZIP invalide.");
                        if(entry.isDirectory()){if(output.exists()&&!output.isDirectory()||!output.isDirectory()&&!output.mkdir())throw new IOException("Conflit dans le ZIP.");}
                        else{
                            if(output.exists())throw new IOException("Conflit dans le ZIP.");
                            try(FileOutputStream out=new FileOutputStream(output)){copy(zip,out,count);out.getFD().sync();}
                        }
                        zip.closeEntry();
                    }
                }
                if(names.isEmpty())throw new IOException("Archive vide ou format ZIP invalide.");
            }else throw new IOException("Commande archive inconnue.");
            count.check();if(target.exists()||!stage.renameTo(target))throw new IOException("Publication de l’archive impossible.");
            JSONObject result=new JSONObject().put("path",destination).put("exists",true).put("entries",count.entries).put("uncompressed_bytes",count.bytes).put("verified",true);
            if(target.isFile())result.put("size",target.length()).put("sha256",WorkspaceTransfer.hash(target));
            return result;
        }finally{remove(stage);}
    }
    private static void add(WorkspaceStore store,String path,String name,ZipOutputStream zip,Counter count,int depth)throws Exception{
        if(depth>MAX_DEPTH)throw new IOException("Archive trop profonde.");count.entry();File f=store.file(path);
        zip.putNextEntry(new ZipEntry(name+(f.isDirectory()?"/":"")));
        if(f.isDirectory()){
            zip.closeEntry();File[] children=f.listFiles();if(children==null)throw new IOException("Dossier illisible.");Arrays.sort(children,(a,b)->a.getName().compareTo(b.getName()));
            for(File child:children)add(store,WorkspacePaths.child(path,child.getName()),name+"/"+child.getName(),zip,count,depth+1);
        }else{
            long size=f.length(),modified=f.lastModified();try(InputStream in=new FileInputStream(f)){copy(in,zip,count);}
            if(size!=f.length()||modified!=f.lastModified())throw new IOException("Source modifiée pendant l’archivage.");zip.closeEntry();
        }
    }
    private static void copy(InputStream in,OutputStream out,Counter count)throws Exception{
        byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){count.check();count.bytes+=n;if(count.bytes>WorkspaceStore.MAX_IMPORT)throw new IOException("Archive décompressée supérieure à 512 Mo.");out.write(buffer,0,n);}
    }
    static void remove(File f){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File child:children)remove(child);}f.delete();}
    private static final class Counter{
        int entries;long bytes;final AgentClient.ResultCallback active;
        Counter(AgentClient.ResultCallback cb){active=cb;}
        void check()throws IOException{if(!active.isActive())throw new IOException("Archive arrêtée avant publication.");}
        void entry()throws IOException{check();if(++entries>MAX_ENTRIES)throw new IOException("Archive limitée à 5 000 entrées.");}
    }
}
