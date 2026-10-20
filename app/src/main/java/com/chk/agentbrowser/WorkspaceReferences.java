package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.util.*;

/** Journaled file moves update every saved project before reporting success. */
final class WorkspaceReferences {
    private static final String JOURNAL="workspace_move_transaction.json";
    private static final Set<String> PATH_KEYS=new HashSet<>(Arrays.asList("path","source_path","output","cover_path","output_folder"));
    private WorkspaceReferences(){}
    static boolean matches(String value,String path){return value.equals(path)||value.startsWith(path+"/");}
    private static String rewrite(String value,String from,String to){return matches(value,from)?to+value.substring(from.length()):value;}
    static Object rewrite(Object input,String from,String to)throws Exception{
        if(input instanceof JSONObject){JSONObject out=new JSONObject();JSONObject obj=(JSONObject)input;Iterator<String> keys=obj.keys();while(keys.hasNext()){String key=keys.next();Object value=obj.get(key);out.put(key,PATH_KEYS.contains(key)&&value instanceof String?rewrite((String)value,from,to):rewrite(value,from,to));}return out;}
        if(input instanceof JSONArray){JSONArray out=new JSONArray();JSONArray arr=(JSONArray)input;for(int i=0;i<arr.length();i++)out.put(rewrite(arr.get(i),from,to));return out;}
        return input;
    }
    private static List<File> records(Context c){
        ArrayList<File> files=new ArrayList<>();
        for(String name:new String[]{"video_editor_project.json","video_last_export.json","video_export_job.json"}){File f=new File(c.getFilesDir(),name);if(f.isFile())files.add(f);}
        for(String dir:new String[]{"studio_projects","studio_projects_trash","agent_desktop/publications"}){File[] entries=new File(c.getFilesDir(),dir).listFiles((d,n)->n.endsWith(".json"));if(entries!=null)Collections.addAll(files,entries);}
        return files;
    }
    static synchronized JSONObject references(Context c,String path)throws Exception{
        new WorkspaceStore(c).file(path);JSONArray found=new JSONArray();
        for(File file:records(c)){JSONObject old=AgentFiles.read(file);if(!old.toString().equals(rewrite(old,path,"__CHK_REFERENCE__").toString()))found.put(new JSONObject().put("name",old.optString("name",old.optString("title",file.getName()))).put("record",file.getName()));}
        JSONArray planning=CutStudioStore.all(c);for(int i=0;i<planning.length();i++){JSONObject row=planning.getJSONObject(i);if(matches(row.optString("path"),path))found.put(new JSONObject().put("name",row.optString("title","Publication Cut")).put("record",row.optString("id")));}
        return new JSONObject().put("path",path).put("count",found.length()).put("references",found);
    }
    static synchronized void move(Context c,File source,File target,String from,String to)throws Exception{
        recover(c);
        String render=VideoEditorEngine.get().status().optString("state");
        if(render.equals("running")||render.equals("preparing")||CutStudioAutomation.isRunning())throw new IOException("Attends la fin de l’export avant de déplacer ou renommer des médias.");
        JSONArray snapshots=new JSONArray();long size=0;
        for(File file:records(c)){
            JSONObject old=AgentFiles.read(file),next=(JSONObject)rewrite(old,from,to);
            if(old.toString().equals(next.toString()))continue;
            next.put("revision",old.optInt("revision",0)+1);
            snapshots.put(new JSONObject().put("file",file.getCanonicalPath()).put("before",old).put("after",next));size+=old.toString().length();
        }
        if(size>600000)throw new IOException("Trop de références pour une transaction ; déplace un sous-dossier.");
        JSONObject prefs=new JSONObject();
        for(String name:new String[]{"cut_studio_planning_v1","chk_cut_library_v2"}){JSONObject values=new JSONObject();for(Map.Entry<String,?> e:c.getSharedPreferences(name,0).getAll().entrySet())if(e.getValue() instanceof String)values.put(e.getKey(),e.getValue());prefs.put(name,values);}
        JSONObject journal=new JSONObject().put("from",from).put("to",to).put("records",snapshots).put("prefs",prefs);
        File log=new File(c.getFilesDir(),JOURNAL);AgentFiles.save(log,journal);
        try{
            if(!source.renameTo(target))throw new IOException("Déplacement impossible");
            for(int i=0;i<snapshots.length();i++){JSONObject row=snapshots.getJSONObject(i);AgentFiles.save(new File(row.getString("file")),row.getJSONObject("after"));}
            CutStudioStore.rewritePaths(c,from,to);CutStudioStore.rewritePaths(c,from+"/",to+"/");
            CutLibraryState library=new CutLibraryState(c);
            if(from.startsWith("CutVideo/")&&to.startsWith("CutVideo/")){
                if(target.isDirectory()&&from.substring(9).indexOf('/')<0&&to.substring(9).indexOf('/')<0)library.renameFolder(from.substring(9),to.substring(9));
                else library.renameVideo(from,to);
            }
            new android.util.AtomicFile(log).delete();
        }catch(Exception error){recover(c);throw error;}
    }
    static synchronized void recover(Context c)throws Exception{
        File log=new File(c.getFilesDir(),JOURNAL);if(!log.exists())return;
        JSONObject journal=AgentFiles.read(log);WorkspaceStore store=new WorkspaceStore(c);
        File source=store.file(journal.getString("from")),target=store.file(journal.getString("to"));
        if(!source.exists()&&target.exists()&&!target.renameTo(source))throw new IOException("Récupération du déplacement impossible ; aucune nouvelle opération autorisée.");
        JSONArray records=journal.getJSONArray("records");for(int i=0;i<records.length();i++){JSONObject row=records.getJSONObject(i);File f=new File(row.getString("file")).getCanonicalFile();if(!f.getPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw new IOException("Journal invalide");AgentFiles.save(f,row.getJSONObject("before"));}
        JSONObject prefs=journal.getJSONObject("prefs");Iterator<String> names=prefs.keys();while(names.hasNext()){String name=names.next();SharedPreferences.Editor editor=c.getSharedPreferences(name,0).edit();JSONObject values=prefs.getJSONObject(name);Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();editor.putString(key,values.getString(key));}if(!editor.commit())throw new IOException("Restauration des métadonnées impossible");}
        new android.util.AtomicFile(log).delete();
    }
}
