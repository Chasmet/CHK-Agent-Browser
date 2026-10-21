package com.chk.agentbrowser;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Previewable, checkpointed batches. A replay never repeats a confirmed file operation. */
final class AgentFiles {
    private AgentFiles(){}
    static File journal(Context c,String type,String id)throws Exception{
        if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new Exception("Identifiant requis : lettres, chiffres, tiret ou souligné (80 maximum).");
        File dir=new File(c.getFilesDir(),"agent_desktop/"+type);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Journal indisponible.");return new File(dir,id+".json");
    }
    static JSONObject read(File f)throws Exception{
        if(!f.isFile()||f.length()>2000000)throw new IOException("Journal introuvable ou trop volumineux.");
        try(InputStream in=new AtomicFile(f).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}
    }
    static void save(File f,JSONObject state)throws Exception{
        AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}
    }
    private static String stamp(File f)throws Exception{
        if(!f.exists())return "absent";
        if(f.isDirectory())return "directory:"+f.lastModified();
        return "file:"+f.length()+":"+f.lastModified();
    }
    static JSONObject command(Context c,JSONObject p,AgentClient.ResultCallback active)throws Exception{
        String operation=p.optString("operation","plan");
        if(operation.startsWith("external_"))return ExternalFiles.command(c,p,active);
        if(operation.equals("stat")){WorkspaceStore s=new WorkspaceStore(c);String path=p.getString("path");File f=s.file(path);return new JSONObject().put("ok",true).put("path",path).put("exists",f.exists()).put("directory",f.isDirectory()).put("size",f.length()).put("stamp",stamp(f));}
        String id=p.getString("id");File f=journal(c,"files",id);
        if(operation.equals("plan")||operation.equals("apply")){
            String fingerprint=AgentVision.digest(p.toString());
            JSONObject state;
            if(f.exists()){state=read(f);if(!state.getString("fingerprint").equals(fingerprint))throw new Exception("Cet identifiant appartient à un autre lot.");}
            else {state=plan(c,p).put("id",id).put("fingerprint",fingerprint);save(f,state);}
            if(operation.equals("apply")&&!p.optBoolean("dry_run",true))run(c,f,state,active);
            return summary(state,p.optInt("offset",0));
        }
        JSONObject state=read(f);
        if(operation.equals("run"))run(c,f,state,active);
        else if(operation.equals("cancel")){state.put("state","cancelled");save(f,state);}
        else if(!operation.equals("status"))throw new Exception("Opération de fichiers inconnue.");
        return summary(state,p.optInt("offset",0));
    }
    static JSONObject plan(Context c,JSONObject p)throws Exception{
        WorkspaceStore store=new WorkspaceStore(c);JSONArray paths=p.optJSONArray("paths");
        if(paths==null){String folder=p.optString("folder","");File dir=WorkspacePaths.resolve(new File(c.getFilesDir(),"workspace"),folder,true);File[] found=dir.listFiles();if(found==null)throw new Exception("Dossier introuvable.");Arrays.sort(found,(a,b)->a.getName().compareTo(b.getName()));paths=new JSONArray();String suffix=p.optString("suffix","").toLowerCase(Locale.ROOT);for(File child:found)if(child.isFile()&&!child.getName().startsWith(".")&&child.getName().toLowerCase(Locale.ROOT).endsWith(suffix))paths.put(WorkspacePaths.child(folder,child.getName()));}
        if(paths.length()<1||paths.length()>500)throw new Exception("Un lot contient de 1 à 500 éléments ; précise paths ou folder/suffix.");
        String mode=p.optString("mode","rename");if(!Arrays.asList("rename","organize","copy","move","trash","transform").contains(mode))throw new Exception("Traitement de fichiers inconnu.");
        JSONArray rows=new JSONArray();HashSet<String> sources=new HashSet<>(),targets=new HashSet<>();
        for(int i=0;i<paths.length();i++){
            String path=paths.getString(i);File source=store.file(path);if(!sources.add(path)||!source.exists())throw new Exception("Source absente ou répétée : "+path);
            String name=source.getName(),extension=name.lastIndexOf('.')>0?name.substring(name.lastIndexOf('.')):"";
            String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";String destination="";
            if(mode.equals("rename"))destination=WorkspacePaths.child(parent,p.optString("prefix","fichier-")+String.format(Locale.ROOT,"%04d",p.optInt("start",1)+i)+extension);
            else if(mode.equals("organize")){String mime=store.mime(path),category=mime.startsWith("image/")?"Images":mime.startsWith("video/")?"Videos":mime.startsWith("audio/")?"Audio":"Documents";destination=WorkspacePaths.child(WorkspacePaths.child(p.optString("destination",parent),category),name);}
            else if(mode.equals("transform"))destination=WorkspacePaths.child(p.optString("destination",parent),name+p.optString("output_suffix",".processed.txt"));
            else if(!mode.equals("trash"))destination=WorkspacePaths.child(p.getString("destination"),name);
            if(!destination.isEmpty()&&(!targets.add(destination)||destination.equals(path)))throw new Exception("Destination répétée ou identique à la source : "+destination);
            JSONObject row=new JSONObject().put("path",path).put("destination",destination).put("source_stamp",stamp(source)).put("state","pending");
            if(!destination.isEmpty()){
                File target=store.file(destination);row.put("destination_stamp",stamp(target));
                if(target.exists())row.put("state","failed").put("error","Destination déjà existante.");
            }
            if(mode.equals("transform")){if(source.isDirectory()||source.length()>65536)throw new Exception("Transformation texte limitée à 64 Ko : "+path);transform(store.readText(path,0),p);}
            rows.put(row);
        }
        // Never consume another source as a destination, including indirect rename cycles.
        for(String dest:targets)if(sources.contains(dest))throw new Exception("Une destination est aussi une source du lot : "+dest);
        return new JSONObject().put("ok",true).put("state","planned").put("mode",mode).put("parameters",new JSONObject(p.toString())).put("items",rows).put("created",System.currentTimeMillis());
    }
    private static String transform(JSONObject read,JSONObject p)throws Exception{
        if(read.optInt("next_offset",-1)!=-1)throw new Exception("Transformation limitée à 12 000 caractères.");String text=read.getString("text");String transformation=p.optString("transform","format_json");
        switch(transformation){
            case "format_json":return text.trim().startsWith("[")?new JSONArray(text).toString(2):new JSONObject(text).toString(2);
            case "text_replace":String from=p.getString("find");if(from.isEmpty())throw new Exception("Texte à remplacer vide.");return text.replace(from,p.getString("replace"));
            case "text_to_markdown":return "# "+p.optString("title","Document")+"\n\n"+text+"\n";
            case "lines_to_json":JSONArray rows=new JSONArray();for(String line:text.split("\\r?\\n"))rows.put(line);return rows.toString(2);
            default:throw new Exception("Transformation autorisée : format_json, text_replace, text_to_markdown, lines_to_json.");
        }
    }
    private static void parents(WorkspaceStore store,String destination)throws Exception{
        String[] parts=destination.split("/");String path="";for(int i=0;i<parts.length-1;i++){path=WorkspacePaths.child(path,parts[i]);File f=store.file(path);if(!f.exists())store.mkdir(path);else if(!f.isDirectory())throw new Exception("Parent non répertoire.");}
    }
    private static void run(Context c,File file,JSONObject state,AgentClient.ResultCallback active)throws Exception{
        if(state.optString("state").equals("cancelled"))throw new Exception("Lot annulé ; crée un nouveau plan.");
        WorkspaceStore store=new WorkspaceStore(c);JSONArray rows=state.getJSONArray("items");String mode=state.getString("mode");JSONObject p=state.getJSONObject("parameters");
        state.put("state","running");save(file,state);
        for(int i=0;i<rows.length();i++){
            if(!active.isActive()){state.put("state","paused");save(file,state);return;}
            JSONObject row=rows.getJSONObject(i);String status=row.getString("state");
            if(status.equals("done")||status.equals("failed")||status.equals("uncertain"))continue;
            if(status.equals("running")){row.put("state","uncertain").put("error","Traitement interrompu : vérifier les fichiers, aucune répétition automatique.");save(file,state);continue;}
            try{
                String path=row.getString("path"),destination=row.getString("destination");
                if(!stamp(store.file(path)).equals(row.getString("source_stamp")))throw new Exception("Source modifiée depuis le plan.");
                if(!destination.isEmpty()&&!stamp(store.file(destination)).equals(row.getString("destination_stamp")))throw new Exception("Destination modifiée depuis le plan.");
                row.put("state","running");save(file,state);
                if(mode.equals("trash"))row.put("trash_id",store.trash(path));
                else {parents(store,destination);
                    if(mode.equals("copy"))WorkspaceArchive.copy(c,path,destination,active);
                    else if(mode.equals("transform"))store.writeText(destination,transform(store.readText(path,0),p),false);
                    else store.move(path,destination);
                    if(!store.file(destination).exists()||!mode.equals("copy")&&!mode.equals("transform")&&store.file(path).exists())throw new Exception("État final du fichier non confirmé.");
                    row.put("result_stamp",stamp(store.file(destination)));
                }
                row.put("state","done");
            }catch(Exception e){row.put("state",row.optString("state").equals("running")?"uncertain":"failed").put("error",e.getMessage()==null?"Erreur de fichier.":e.getMessage());}
            save(file,state);
        }
        state.put("state","completed");save(file,state);
    }
    private static JSONObject summary(JSONObject state,int offset)throws Exception{
        if(offset<0||offset>500)throw new Exception("Pagination invalide.");JSONArray rows=state.getJSONArray("items"),page=new JSONArray();int done=0,failed=0,uncertain=0;
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.optString("state").equals("done"))done++;if(r.optString("state").equals("failed"))failed++;if(r.optString("state").equals("uncertain"))uncertain++;if(i>=offset&&i<offset+25)page.put(r);}
        return new JSONObject().put("ok",failed==0&&uncertain==0).put("id",state.getString("id")).put("state",state.getString("state")).put("total",rows.length()).put("succeeded",done).put("failed",failed).put("uncertain",uncertain).put("items",page).put("next_offset",offset+25<rows.length()?offset+25:-1);
    }
}
