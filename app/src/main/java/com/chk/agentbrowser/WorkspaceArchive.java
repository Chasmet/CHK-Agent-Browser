package com.chk.agentbrowser;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Streaming ZIP creation, bounded space and safe source paths. */
final class WorkspaceArchive {
    static JSONObject create(Context c,JSONArray paths,String destination)throws Exception{
        if(paths.length()<1||paths.length()>500||!destination.toLowerCase(Locale.ROOT).endsWith(".zip"))throw new IOException("Sélection ou destination ZIP invalide");
        WorkspaceStore store=new WorkspaceStore(c);File target=store.file(destination);
        if(target.exists()||!target.getParentFile().isDirectory())throw new IOException("Destination existante ou parent absent");
        for(int i=0;i<paths.length();i++)if(WorkspaceReferences.matches(destination,paths.getString(i)))throw new IOException("L’archive doit être hors des dossiers sélectionnés");
        File temp=new File(target.getParentFile(),".archive-"+UUID.randomUUID());Set<String> names=new HashSet<>();long[] totals={0,0};
        try{
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))){
                for(int i=0;i<paths.length();i++){String path=paths.getString(i);append(store,path,path,zip,names,totals,temp,0);}
            }
            if(target.exists()||!temp.renameTo(target))throw new IOException("Finalisation ZIP impossible");
            return new JSONObject().put("path",destination).put("files",totals[0]).put("source_bytes",totals[1]).put("size",target.length());
        }finally{temp.delete();}
    }
    private static void append(WorkspaceStore store,String path,String name,ZipOutputStream zip,Set<String> names,long[] totals,File temp,int depth)throws Exception{
        if(depth>64||names.size()>=100000)throw new IOException("Archive trop profonde ou trop volumineuse");
        if(Thread.currentThread().isInterrupted())throw new IOException("Archive interrompue");
        File source=store.file(path);if(!source.exists())throw new IOException("Source introuvable : "+path);
        if(!names.add(name))return;
        ZipEntry entry=new ZipEntry(name+(source.isDirectory()?"/":""));entry.setTime(source.lastModified());zip.putNextEntry(entry);
        if(source.isDirectory()){
            zip.closeEntry();File[] children=source.listFiles();if(children==null)throw new IOException("Dossier illisible");
            for(File child:children)if(!child.getName().startsWith("."))append(store,WorkspacePaths.child(path,child.getName()),name+"/"+child.getName(),zip,names,totals,temp,depth+1);
        }else{
            try(InputStream in=new BufferedInputStream(new FileInputStream(source))){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(temp.getParentFile().getUsableSpace()<16L*1024*1024)throw new IOException("Espace libre insuffisant");zip.write(buffer,0,n);totals[1]+=n;}}zip.closeEntry();totals[0]++;
        }
    }
}
