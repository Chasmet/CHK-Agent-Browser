package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Affichage virtuel par catégories : aucun déplacement des fichiers existants.
 * L'arborescence legacy reste inchangée et visible dans sa/ses catégories.
 * Les nouveaux dossiers sont physiquement rangés sous Categories/<type>.
 * Les outils MCP peuvent continuer à utiliser leurs chemins existants.
 */
public final class WorkspaceCategoryView {
    private static final String ROOT = "Categories";
    private static final int SCAN_LIMIT = 5000;
    private WorkspaceCategoryView() {}

    public static boolean isContainerCategory(String key) {
        return "video".equals(key) || "image".equals(key)
            || "audio".equals(key) || "document".equals(key);
    }

    public static String storageRoot(String category) {
        switch(category) {
            case "video": return ROOT + "/Videos";
            case "image": return ROOT + "/Images";
            case "audio": return ROOT + "/Audio";
            case "document": return ROOT + "/Documents";
            default: throw new IllegalArgumentException("Choisis Images, Vidéos, Audio ou Documents");
        }
    }

    /** Destination physique, créée seulement lors d'une action d'ajout explicite. */
    public static String destination(WorkspaceStore store, String category, String folder) throws Exception {
        if(folder != null && !folder.isEmpty()) {
            if(!store.file(folder).isDirectory()) throw new IOException("Dossier destination introuvable");
            return folder;
        }
        String base=storageRoot(category);
        if(!store.file(ROOT).exists()) store.mkdir(ROOT);
        if(!store.file(ROOT).isDirectory()) throw new IOException("Categories n'est pas un dossier");
        if(!store.file(base).exists()) store.mkdir(base);
        if(!store.file(base).isDirectory()) throw new IOException("La catégorie est inaccessible");
        return base;
    }

    public static JSONObject query(Context context, String folder, String category,
                                   String search, String sort, int offset) throws Exception {
        WorkspaceStore store=new WorkspaceStore(context);
        String q=search==null?"":search.toLowerCase(Locale.ROOT).trim();
        if(!"all".equals(category) && "recent".equals(category))
            return WorkspaceCatalog.query(context,"","recent",q,sort,offset);
        if(!folder.isEmpty()) {
            // Navigation normale une fois le dossier ouvert. Les chemins existants sont intacts.
            return WorkspaceCatalog.query(context,folder,"all",q,sort,offset);
        }
        if("all".equals(category)) {
            // L'accueil est constitué uniquement d'icônes, jamais d'une liste brute.
            if(q.isEmpty()) return page(new ArrayList<>(),sort,offset,false);
            return WorkspaceCatalog.query(context,"","recent",q,sort,offset);
        }
        if(!isContainerCategory(category))
            return WorkspaceCatalog.query(context,"",category,q,sort,offset);

        List<JSONObject> result=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        Counter scanned=new Counter();
        String base=storageRoot(category);
        if(store.file(base).isDirectory())
            appendChildren(store,base,q,result,seen,scanned);

        int next=0;
        while(next>=0 && scanned.count<SCAN_LIMIT) {
            JSONObject page=store.list("","",next);
            JSONArray items=page.getJSONArray("items");
            for(int i=0;i<items.length() && scanned.count<SCAN_LIMIT;i++) {
                JSONObject item=items.getJSONObject(i);
                scanned.count++;
                String path=item.getString("path");
                if(ROOT.equals(path)) continue; // Répertoire technique non affiché.
                if(item.optBoolean("directory")) {
                    if(matchesLegacyFolder(store,path,category,scanned))
                        add(result,seen,item,q);
                } else if(category.equals(WorkspaceCatalog.category(item.getString("mime_type"),path))) {
                    add(result,seen,item,q);
                }
            }
            next=page.getInt("next_offset");
        }
        return page(result,sort,offset,scanned.count>=SCAN_LIMIT);
    }

    private static void appendChildren(WorkspaceStore store,String base,String q,
                                       List<JSONObject> result,Set<String> seen,
                                       Counter scanned) throws Exception {
        int next=0;
        while(next>=0 && scanned.count<SCAN_LIMIT) {
            JSONObject page=store.list(base,"",next);
            JSONArray rows=page.getJSONArray("items");
            for(int i=0;i<rows.length()&&scanned.count<SCAN_LIMIT;i++) {
                scanned.count++;
                add(result,seen,rows.getJSONObject(i),q);
            }
            next=page.getInt("next_offset");
        }
    }

    private static void add(List<JSONObject> result,Set<String> seen,JSONObject row,String q) {
        String name=row.optString("name");
        if(name.toLowerCase(Locale.ROOT).contains(q)&&seen.add(row.optString("path")))
            result.add(row);
    }

    private static boolean matchesLegacyFolder(WorkspaceStore store,String initial,
                                               String category,Counter scanned) throws Exception {
        String simple=initial.toLowerCase(Locale.ROOT);
        String hinted=hint(simple);
        if(category.equals(hinted)) return true;
        ArrayDeque<String> pending=new ArrayDeque<>();
        pending.add(initial);
        boolean recognized=false, match=false;
        while(!pending.isEmpty() && scanned.count<SCAN_LIMIT) {
            String path=pending.removeFirst();
            int next=0;
            while(next>=0 && scanned.count<SCAN_LIMIT) {
                JSONObject page=store.list(path,"",next);
                JSONArray rows=page.getJSONArray("items");
                for(int i=0;i<rows.length() && scanned.count<SCAN_LIMIT;i++) {
                    JSONObject row=rows.getJSONObject(i);
                    scanned.count++;
                    String sub=row.getString("path");
                    if(row.optBoolean("directory")) {
                        if(sub.split("/").length<20)pending.addLast(sub);
                    } else {
                        String found=WorkspaceCatalog.category(row.getString("mime_type"),sub);
                        if(!"other".equals(found))recognized=true;
                        if(category.equals(found))match=true;
                    }
                }
                next=page.getInt("next_offset");
            }
        }
        // Dossiers anciens vides/inclassables : conservés dans Documents, jamais perdus.
        return match || ("document".equals(category) && !recognized && hinted.isEmpty());
    }

    private static String hint(String name) {
        if(name.contains("video")||name.contains("vidéo")||name.contains("clip")
           ||name.contains("cut")||name.contains("film"))return "video";
        if(name.contains("musique")||name.contains("music")||name.contains("audio")
           ||name.contains("sound"))return "audio";
        if(name.contains("image")||name.contains("photo")||name.contains("picture"))return "image";
        if(name.contains("document")||name.contains("pdf")||name.contains("note"))return "document";
        return "";
    }

    private static JSONObject page(List<JSONObject> rows,String sort,int offset,boolean truncated)
        throws Exception {
        Collections.sort(rows,(x,y)->{
            boolean d1=x.optBoolean("directory"),d2=y.optBoolean("directory");
            if(d1!=d2)return d1?-1:1;
            int cmp="date".equals(sort)?Long.compare(y.optLong("updated"),x.optLong("updated")):
                "size".equals(sort)?Long.compare(y.optLong("size"),x.optLong("size")):0;
            return cmp!=0?cmp:x.optString("name").compareToIgnoreCase(y.optString("name"));
        });
        JSONArray out=new JSONArray();
        int start=Math.max(0,offset);
        for(int i=start;i<Math.min(start+50,rows.size());i++)out.put(rows.get(i));
        return new JSONObject().put("items",out).put("total",rows.size())
            .put("next_offset",start+50<rows.size()?start+50:-1).put("truncated",truncated);
    }
    private static final class Counter {int count;}
}
