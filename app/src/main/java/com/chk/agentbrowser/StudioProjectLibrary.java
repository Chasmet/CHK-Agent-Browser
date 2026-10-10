package com.chk.agentbrowser;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Private durable project library. Media stays in CHK Files; only editing
 * instructions are stored here. Each project is independent of the active
 * editor snapshot used by the older MCP commands.
 */
final class StudioProjectLibrary {
    private static final String ACTIVE="video_editor_project.json";
    private static final String LIBRARY="studio_projects";
    private static final String TRASH="studio_projects_trash";

    static JSONObject active(Context app)throws Exception{
        File current=new File(app.getFilesDir(),ACTIVE);
        JSONObject p=current.isFile()?read(current):blank();
        String id=p.optString("project_id","");
        boolean migrated=!validId(id);
        if(migrated){
            id=UUID.randomUUID().toString();
            p.put("project_id",id);
            p.put("created_at",System.currentTimeMillis());
            p.put("updated_at",System.currentTimeMillis());
        }
        File archive=entry(app,id,false);
        if(!archive.exists())write(archive,p);
        if(migrated||!current.isFile())write(current,p);
        return p;
    }
    static JSONObject blank()throws Exception{
        return new JSONObject().put("name","Nouveau montage")
            .put("output","Montage-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,6)+".mp4")
            .put("aspect_ratio","9:16").put("aspect_mode","fit")
            .put("resolution",720).put("clips",new JSONArray())
            .put("audio",new JSONArray());
    }
    private static boolean validId(String id){
        return id!=null&&id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    private static File dir(Context context,boolean deleted)throws Exception{
        File path=new File(context.getFilesDir(),deleted?TRASH:LIBRARY);
        if(!path.isDirectory()&&!path.mkdirs())throw new java.io.IOException("Dossier des projets inaccessible");
        return path;
    }
    private static File entry(Context app,String id,boolean deleted)throws Exception{
        if(!validId(id))throw new IllegalArgumentException("Identifiant de projet invalide");
        return new File(dir(app,deleted),id+".json");
    }
    private static JSONObject read(File source)throws Exception{
        if(!source.isFile()||source.length()==0||source.length()>250000)throw new java.io.IOException("Projet absent ou illisible");
        byte[] bytes=new byte[(int)source.length()];
        try(FileInputStream in=new FileInputStream(source)){
            int pos=0,n;
            while(pos<bytes.length&&(n=in.read(bytes,pos,bytes.length-pos))>0)pos+=n;
            if(pos!=bytes.length)throw new java.io.IOException("Projet incomplet");
        }
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private static void write(File path,JSONObject value)throws Exception{
        AtomicFile safe=new AtomicFile(path);
        FileOutputStream out=null;
        try{
            out=safe.startWrite();
            out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            safe.finishWrite(out);
        }catch(Exception e){
            if(out!=null)safe.failWrite(out);
            throw e;
        }
    }
    /** Persist to its own JSON first, then atomically refresh the old active snapshot. */
    static void save(Context app,JSONObject p)throws Exception{
        JSONObject current=active(app);
        String id=current.getString("project_id");
        if(p.has("project_id")&&!id.equals(p.optString("project_id")))
            throw new IllegalStateException("Projet différent du montage ouvert");
        p.put("project_id",id);
        p.put("created_at",current.optLong("created_at",System.currentTimeMillis()));
        p.put("updated_at",System.currentTimeMillis());
        write(entry(app,id,false),p);
        write(new File(app.getFilesDir(),ACTIVE),p);
    }
    static JSONObject create(Context app,JSONObject value)throws Exception{
        JSONObject before=active(app);
        JSONObject p=new JSONObject(value.toString());
        String id=UUID.randomUUID().toString();
        p.put("project_id",id).put("created_at",System.currentTimeMillis())
            .put("updated_at",System.currentTimeMillis())
            .put("revision",before.optInt("revision",0)+1);
        // Previous active draft has already been saved independently.
        write(entry(app,id,false),p);
        write(new File(app.getFilesDir(),ACTIVE),p);
        return p;
    }
    static JSONObject open(Context app,String id)throws Exception{
        JSONObject before=active(app);
        JSONObject p=read(entry(app,id,false));
        if(!id.equals(p.optString("project_id")))throw new java.io.IOException("Projet corrompu");
        p.put("revision",Math.max(p.optInt("revision",0),before.optInt("revision",0))+1);
        write(entry(app,id,false),p);
        write(new File(app.getFilesDir(),ACTIVE),p);
        return p;
    }
    static JSONObject duplicate(Context app,String id)throws Exception{
        JSONObject source=read(entry(app,id,false));
        source.remove("project_id");
        source.put("name","Copie de "+source.optString("name","Montage"));
        source.put("output","Montage-copie-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,6)+".mp4");
        return create(app,source);
    }
    static JSONObject rename(Context app,String id,String name)throws Exception{
        if(name==null||name.trim().isEmpty()||name.trim().length()>120)
            throw new IllegalArgumentException("Nom de projet invalide");
        JSONObject p=read(entry(app,id,false));
        p.put("name",name.trim()).put("updated_at",System.currentTimeMillis());
        JSONObject now=active(app);
        if(id.equals(now.optString("project_id"))){
            p.put("revision",now.optInt("revision",0)+1);
            write(entry(app,id,false),p);
            write(new File(app.getFilesDir(),ACTIVE),p);
        }else write(entry(app,id,false),p);
        return p;
    }
    static void trash(Context app,String id)throws Exception{
        JSONObject now=active(app);
        File from=entry(app,id,false),target=entry(app,id,true);
        if(!from.isFile())throw new java.io.IOException("Projet introuvable");
        if(target.exists())throw new java.io.IOException("Une copie est déjà dans la corbeille");
        if(!from.renameTo(target))throw new java.io.IOException("Impossible de déplacer vers la corbeille");
        if(id.equals(now.optString("project_id")))create(app,blank());
    }
    static void restore(Context app,String id)throws Exception{
        File source=entry(app,id,true),target=entry(app,id,false);
        if(!source.isFile())throw new java.io.IOException("Projet absent de la corbeille");
        if(target.exists())throw new java.io.IOException("Projet déjà présent dans la bibliothèque");
        if(!source.renameTo(target))throw new java.io.IOException("Restauration impossible");
    }
    static JSONObject list(Context app,boolean deleted)throws Exception{
        JSONObject current=active(app);
        File[] files=dir(app,deleted).listFiles((folder,name)->name.matches("[0-9a-fA-F-]{36}\\.json"));
        ArrayList<JSONObject> rows=new ArrayList<>();
        if(files!=null)for(File file:files){
            try{
                JSONObject p=read(file);
                if(!file.getName().equals(p.optString("project_id")+".json"))continue;
                JSONArray clips=p.optJSONArray("clips"),audio=p.optJSONArray("audio");
                int n=clips==null?0:clips.length();
                long total=0;
                for(int i=0;i<n;i++){
                    JSONObject c=clips.optJSONObject(i);
                    if(c!=null)total+=Math.round(c.optLong("duration_ms",10000)/Math.max(.25,c.optDouble("speed",1)));
                }
                JSONObject item=new JSONObject().put("id",p.getString("project_id"))
                    .put("name",p.optString("name","Montage"))
                    .put("created_at",p.optLong("created_at",file.lastModified()))
                    .put("updated_at",p.optLong("updated_at",file.lastModified()))
                    .put("duration_ms",total).put("clips",n)
                    .put("audio",audio==null?0:audio.length())
                    .put("aspect_ratio",p.optString("aspect_ratio","source"))
                    .put("output",p.optString("output",""))
                    .put("cover_path",n>0?clips.optJSONObject(0).optString("path",""):"")
                    .put("active",!deleted&&p.optString("project_id").equals(current.optString("project_id")));
                rows.add(item);
            }catch(Exception ignored){ /* corrupt JSON cannot hide healthy projects */ }
        }
        Collections.sort(rows,(a,b)->Long.compare(b.optLong("updated_at"),a.optLong("updated_at")));
        JSONArray items=new JSONArray();
        for(JSONObject row:rows)items.put(row);
        return new JSONObject().put("items",items).put("count",items.length())
            .put("active_id",current.optString("project_id")).put("trash",deleted);
    }
}
