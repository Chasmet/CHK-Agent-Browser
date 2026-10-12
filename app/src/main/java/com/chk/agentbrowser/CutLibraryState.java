package com.chk.agentbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local-only organizational metadata for Cut, independent of browser workspace files. */
final class CutLibraryState {
    private static final String PREF = "chk_cut_library_v2";
    private static final String GROUPS = "groups", ASSIGN = "assign", NOTES = "notes", TRACK = "tracking";
    private final SharedPreferences prefs;

    CutLibraryState(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    private JSONObject object(String key) {
        try { return new JSONObject(prefs.getString(key, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }
    private JSONArray array(String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void store(String key, JSONObject value) {
        if (!prefs.edit().putString(key, value.toString()).commit())
            throw new IllegalStateException("Sauvegarde impossible");
    }
    private void store(String key, JSONArray value) {
        if (!prefs.edit().putString(key, value.toString()).commit())
            throw new IllegalStateException("Sauvegarde impossible");
    }

    synchronized JSONArray groups() { return array(GROUPS); }
    synchronized String groupFor(String folder) { return object(ASSIGN).optString(folder, ""); }
    synchronized String note(String key) { return object(NOTES).optString(key, ""); }
    synchronized String tracking(String path, String platform) {
        return object(TRACK).optJSONObject(path) == null ? "" :
            object(TRACK).optJSONObject(path).optString(platform, "");
    }
    synchronized boolean isTracked(String path, String platform) {
        return "1".equals(tracking(path, platform));
    }
    synchronized void setTracked(String path, String platform, boolean value) throws Exception {
        JSONObject root = object(TRACK);
        JSONObject row = root.optJSONObject(path);
        if (row == null) row = new JSONObject();
        row.put(platform, value ? "1" : "");
        root.put(path, row);
        store(TRACK, root);
    }
    synchronized void setNote(String key, String value) throws Exception {
        JSONObject root = object(NOTES);
        root.put(key, value);
        store(NOTES, root);
    }
    synchronized String createGroup(String name) throws Exception {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 70) throw new Exception("Nom invalide (1 à 70 caractères)");
        JSONArray arr = groups();
        for (int i=0;i<arr.length();i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null && item.optString("name").equalsIgnoreCase(clean))
                throw new Exception("Ce classement existe déjà");
        }
        String id = UUID.randomUUID().toString();
        arr.put(new JSONObject().put("id", id).put("name", clean));
        store(GROUPS, arr);
        return id;
    }
    synchronized void renameGroup(String id, String name) throws Exception {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 70) throw new Exception("Nom invalide");
        JSONArray arr = groups();
        boolean found = false;
        for (int i=0;i<arr.length();i++) {
            JSONObject item=arr.optJSONObject(i);
            if(item==null)continue;
            if(!item.optString("id").equals(id) &&
                item.optString("name").equalsIgnoreCase(clean)) throw new Exception("Nom déjà utilisé");
            if(item.optString("id").equals(id)) {item.put("name",clean);found=true;}
        }
        if(!found)throw new Exception("Classement introuvable");
        store(GROUPS,arr);
    }
    synchronized void deleteGroup(String id) throws Exception {
        JSONArray old=groups(), out=new JSONArray();
        for(int i=0;i<old.length();i++){
            JSONObject item=old.optJSONObject(i);
            if(item!=null&&!id.equals(item.optString("id")))out.put(item);
        }
        JSONObject assignments=object(ASSIGN);
        List<String> keys=new ArrayList<>();
        java.util.Iterator<String> it=assignments.keys();
        while(it.hasNext())keys.add(it.next());
        for(String key:keys)if(id.equals(assignments.optString(key)))assignments.remove(key);
        JSONObject notes=object(NOTES); notes.remove("group:"+id);
        store(ASSIGN, assignments);store(NOTES,notes);store(GROUPS,out);
    }
    synchronized void assign(String folder, String id) throws Exception {
        JSONObject root=object(ASSIGN);
        if(id==null||id.isEmpty())root.remove(folder);
        else{
            boolean found=false;JSONArray arr=groups();
            for(int i=0;i<arr.length();i++)if(id.equals(arr.optJSONObject(i).optString("id")))found=true;
            if(!found)throw new Exception("Classement introuvable");
            root.put(folder,id);
        }
        store(ASSIGN,root);
    }
    synchronized void renameFolder(String oldName,String newName) throws Exception {
        JSONObject assignments=object(ASSIGN);
        if(assignments.has(oldName)){
            assignments.put(newName,assignments.optString(oldName));assignments.remove(oldName);
        }
        store(ASSIGN,assignments);
        renameKey(NOTES,"folder:"+oldName,"folder:"+newName);
        renamePrefix(TRACK,"CutVideo/"+oldName+"/","CutVideo/"+newName+"/");
    }
    synchronized void renameVideo(String oldPath,String newPath) throws Exception {
        renameKey(TRACK,oldPath,newPath);
    }
    synchronized void clearVideo(String path) throws Exception {
        JSONObject tracks=object(TRACK);tracks.remove(path);store(TRACK,tracks);
    }
    private void renameKey(String prefKey,String oldKey,String newKey)throws Exception{
        JSONObject root=object(prefKey);
        if(root.has(oldKey)){root.put(newKey,root.get(oldKey));root.remove(oldKey);store(prefKey,root);}
    }
    private void renamePrefix(String prefKey,String oldPrefix,String newPrefix)throws Exception{
        JSONObject root=object(prefKey), updates=new JSONObject();
        List<String> names=new ArrayList<>();java.util.Iterator<String> it=root.keys();
        while(it.hasNext())names.add(it.next());
        for(String key:names)if(key.startsWith(oldPrefix)){
            updates.put(newPrefix+key.substring(oldPrefix.length()),root.get(key));root.remove(key);
        }
        java.util.Iterator<String> applied=updates.keys();
        while(applied.hasNext()){String key=applied.next();root.put(key,updates.get(key));}
        store(prefKey,root);
    }
    synchronized int layout(){return prefs.getInt("layout",0);}
    synchronized int size(){return prefs.getInt("size",1);}
    synchronized int sort(){return prefs.getInt("sort",0);}
    synchronized void display(int layout,int size,int sort) {
        if(!prefs.edit().putInt("layout",layout).putInt("size",size).putInt("sort",sort).commit())
            throw new IllegalStateException("Préférences non enregistrées");
    }
}
