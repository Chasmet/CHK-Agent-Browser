package com.chk.agentbrowser;
import android.content.Context;
import android.os.StatFs;
import org.json.*;
import java.io.*;
import java.util.*;
/** Global imported-file categories; bounded traversal and stable pagination. */
public final class WorkspaceCatalog {
    private WorkspaceCatalog(){}
    public static String category(String mime,String path){
        if(mime.startsWith("video/"))return "video";if(mime.startsWith("image/"))return "image";if(mime.startsWith("audio/"))return "audio";
        if(path.toLowerCase(Locale.ROOT).matches(".*\\.(zip|rar|7z|gz|tar)$"))return "archive";
        if(mime.startsWith("text/")||path.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx?|xlsx?|pptx?|odt|csv|json|md)$"))return "document";
        return "other";
    }
    public static JSONObject query(Context c,String folder,String category,String search,String sort,int offset)throws Exception{
        WorkspaceStore store=new WorkspaceStore(c);List<JSONObject> all=new ArrayList<>();ArrayDeque<String> pending=new ArrayDeque<>();pending.add(folder);int scanned=0;long bytes=0;
        JSONObject counts=new JSONObject();boolean global=!"all".equals(category);
        while(!pending.isEmpty()&&scanned<5000){String dir=pending.removeFirst();int page=0;do{
            JSONObject batch=store.list(dir,"",page);JSONArray rows=batch.getJSONArray("items");
            for(int i=0;i<rows.length()&&scanned<5000;i++){
                JSONObject row=rows.getJSONObject(i);scanned++;boolean directory=row.getBoolean("directory");
                if(directory){if(global)pending.addLast(row.getString("path"));}
                else{String type=category(row.getString("mime_type"),row.getString("path"));row.put("category",type);counts.put(type,counts.optInt(type)+1);bytes+=row.getLong("size");}
                if((!global||(!directory&&(category.equals("recent")||category.equals(row.optString("category")))))&&row.getString("name").toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))all.add(row);
            }page=batch.getInt("next_offset");
        }while(page>=0&&scanned<5000);}
        final String order=category.equals("recent")?"date":sort;
        Collections.sort(all,(a,b)->{if(a.optBoolean("directory")!=b.optBoolean("directory"))return a.optBoolean("directory")?-1:1;int diff="date".equals(order)?Long.compare(b.optLong("updated"),a.optLong("updated")):"size".equals(order)?Long.compare(b.optLong("size"),a.optLong("size")):0;return diff!=0?diff:a.optString("path").compareToIgnoreCase(b.optString("path"));});
        JSONArray items=new JSONArray();int start=Math.max(0,offset);for(int i=start;i<Math.min(start+50,all.size());i++)items.put(all.get(i));
        StatFs disk=new StatFs(c.getFilesDir().getPath());
        return new JSONObject().put("items",items).put("total",all.size()).put("next_offset",start+50<all.size()?start+50:-1).put("scanned",scanned).put("truncated",scanned>=5000).put("counts",counts).put("bytes",bytes).put("available_bytes",disk.getAvailableBytes()).put("path",folder);
    }
    public static JSONObject copy(Context c,String path,String destination)throws Exception{
        WorkspaceStore store=new WorkspaceStore(c);File source=store.file(path);if(!source.isFile())throw new IOException("La copie concerne un fichier");
        store.importUri(android.net.Uri.fromFile(source),destination);return new JSONObject().put("path",destination);
    }
}
