package com.chk.agentbrowser;

import java.io.File;
import java.io.IOException;

/** Canonical containment is shared by UI, provider and MCP. No absolute or hidden paths. */
public final class WorkspacePaths {
    private WorkspacePaths(){}
    public static File resolve(File root,String path,boolean allowRoot)throws IOException{
        String p=path==null?"":path;
        if(p.length()>700||p.startsWith("/")||p.contains("\\")||p.indexOf('\0')>=0)
            throw new IOException("Chemin relatif invalide");
        if(!p.isEmpty())for(String part:p.split("/",-1)){
            if(part.isEmpty()||part.startsWith(".")||part.length()>120||part.trim().isEmpty())
                throw new IOException("Nom ou chemin interdit");
        }
        File base=root.getCanonicalFile(),file=new File(base,p).getCanonicalFile();
        if(file.equals(base)){if(allowRoot)return file;throw new IOException("Choisis un fichier ou dossier");}
        if(!file.getPath().startsWith(base.getPath()+File.separator))throw new IOException("Chemin hors de l’espace Fichiers");
        return file;
    }
    public static String child(String parent,String name)throws IOException{
        if(name==null||name.contains("/")||name.contains("\\")||name.startsWith(".")||name.trim().isEmpty()||name.length()>120)
            throw new IOException("Nom invalide");
        return (parent==null||parent.isEmpty()?"":parent+"/")+name.trim();
    }
}
