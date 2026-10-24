package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Same virtual folders as Files, with the site's accept filter applied to files only. */
final class WorkspaceImportIndex {
    private WorkspaceImportIndex() {}
    static List<String> types(String[] raw) {
        List<String> out=new ArrayList<>();
        if(raw!=null)for(String group:raw)if(group!=null)for(String part:group.split(",")){
            String value=part.trim().toLowerCase(Locale.ROOT);
            int semi=value.indexOf(';');if(semi>=0)value=value.substring(0,semi).trim();
            if(!value.isEmpty()&&!out.contains(value))out.add(value);
        }
        return out;
    }
    static boolean accepts(WorkspaceStore store,String path,List<String> types) {
        if(types.isEmpty())return true;
        String mime=store.mime(path).toLowerCase(Locale.ROOT);
        String lower=path.toLowerCase(Locale.ROOT);
        for(String type:types){
            if(type.equals("*/*")||type.equals("*"))return true;
            if(type.startsWith(".")&&lower.endsWith(type))return true;
            if(type.endsWith("/*")&&mime.startsWith(type.substring(0,type.length()-1)))return true;
            if(type.equals(mime))return true;
            if((type.equals("image/jpg")||type.equals("image/pjpeg"))&&mime.equals("image/jpeg"))return true;
            if((type.equals("audio/wav")||type.equals("audio/x-wav"))&&lower.endsWith(".wav"))return true;
        }
        return false;
    }
    static String initialCategory(WorkspaceStore store,List<String> types) {
        String category="";
        for(String type:types){
            String mime=type.startsWith(".")?store.mime("file"+type):type;
            String found=mime.startsWith("image/")?"image":mime.startsWith("video/")?"video":
                mime.startsWith("audio/")?"audio":"";
            if(found.isEmpty()||(!category.isEmpty()&&!category.equals(found)))return "all";
            category=found;
        }
        return category.isEmpty()?"all":category;
    }
    static String label(String category) {
        switch(category){case "image":return "Images";case "video":return "Vidéos";
            case "audio":return "Audio";case "document":return "Documents";
            case "recent":return "Récents";default:return "Accueil";}
    }
    static String visiblePath(String path) {
        String[] roots={"Categories/Images","Categories/Videos","Categories/Audio","Categories/Documents"};
        String[] names={"Images","Vidéos","Audio","Documents"};
        for(int i=0;i<roots.length;i++)if(path.equals(roots[i])||path.startsWith(roots[i]+"/"))
            return names[i]+path.substring(roots[i].length());
        return path;
    }
    static JSONObject query(Context context,String category,String folder,String search,
                            String sort,List<String> types,boolean showAll)throws Exception {
        WorkspaceStore store=new WorkspaceStore(context);
        JSONArray out=new JSONArray();int hidden=0;
        if(category.equals("all")&&folder.isEmpty()&&search.trim().isEmpty()){
            for(String key:new String[]{"image","video","audio","document","recent"})
                out.put(new JSONObject().put("path","@"+key).put("name",label(key))
                    .put("directory",true).put("virtual",true).put("compatible",true));
            return new JSONObject().put("items",out).put("hidden",0).put("truncated",false);
        }
        JSONObject source;
        if(category.equals("all")&&folder.isEmpty())
            source=WorkspaceCatalog.query(context,"","search",search,sort,0,5000);
        else source=WorkspaceCategoryView.query(context,folder,category,search,sort,0,5000);
        JSONArray rows=source.getJSONArray("items");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            boolean directory=row.optBoolean("directory");
            boolean compatible=directory||accepts(store,row.getString("path"),types);
            if(!showAll&&!compatible){hidden++;continue;}
            row.put("compatible",compatible);out.put(row);
        }
        return new JSONObject().put("items",out).put("hidden",hidden)
            .put("truncated",source.optBoolean("truncated"));
    }
}
