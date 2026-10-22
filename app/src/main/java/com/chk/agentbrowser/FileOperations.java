package com.chk.agentbrowser;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

final class FileOperations {
    static JSONObject search(Context app,JSONObject args,AgentClient.ResultCallback active)throws Exception{
        WorkspaceStore s=new WorkspaceStore(app);String folder=args.optString("folder",""),query=args.getString("query");if(query.isEmpty()||query.length()>200)throw new IOException("Recherche de 1 à 200 caractères requise");
        File root=folder.isEmpty()?s.file("search-placeholder").getParentFile():s.file(folder);if(!root.isDirectory())throw new IOException("Dossier absent");
        ArrayDeque<File> todo=new ArrayDeque<>();todo.add(root);JSONArray matches=new JSONArray();int entries=0,scanned=0;long bytes=0,deadline=android.os.SystemClock.elapsedRealtime()+10000;boolean limited=false;
        while(!todo.isEmpty()){
            if(!active.isActive())throw new IOException("Recherche arrêtée");if(entries>=2000||bytes>=32L*1024*1024||matches.length()>=100||android.os.SystemClock.elapsedRealtime()>deadline){limited=true;break;}
            File file=todo.removeFirst();entries++;String path=file.getCanonicalPath().substring(root.getCanonicalPath().length());if(path.startsWith("/"))path=path.substring(1);String full=folder.isEmpty()?path:folder+(path.isEmpty()?"":"/"+path);
            if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)if(!child.getName().startsWith("."))todo.add(child);continue;}
            file=s.file(full);if(file.length()>8L*1024*1024)continue;
            JSONObject info=FileFormats.detect(app,full);if(!info.getString("kind").equals("text"))continue;scanned++;bytes+=file.length();
            try(Reader in=FileContent.reader(file,info.getString("encoding"))){char[] buffer=new char[8192];String tail="";long position=0;int n;
                while((n=in.read(buffer))!=-1){if(!active.isActive())throw new IOException("Recherche arrêtée");if(android.os.SystemClock.elapsedRealtime()>deadline){limited=true;break;}String part=tail+new String(buffer,0,n);int found=part.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
                    if(found>=0){matches.put(new JSONObject().put("path",full).put("offset",position-tail.length()+found).put("excerpt",part.substring(Math.max(0,found-60),Math.min(part.length(),found+query.length()+100))));break;}
                    tail=part.substring(Math.max(0,part.length()-query.length()+1));position+=n;
                }
            }
        }
        return new JSONObject().put("matches",matches).put("scanned_files",scanned).put("visited_entries",entries).put("limited",limited).put("complete",!limited);
    }
    static JSONObject batch(Context app,JSONObject args,AgentClient.ResultCallback active)throws Exception{
        JSONArray operations=args.getJSONArray("operations");if(operations.length()<1||operations.length()>50)throw new IOException("1 à 50 opérations requises");String id=args.getString("id");if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("Identifiant de lot invalide");
        JSONArray steps=new JSONArray();for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);String action=op.getString("action");if(!Arrays.asList("files_copy","files_move","files_mkdir","files_write_text","files_edit_text","files_convert","files_verify","files_extract_archive").contains(action))throw new IOException("Action de lot non disponible : "+action);steps.put(new JSONObject().put("action",action).put("args",op.getJSONObject("args")));}
        if(steps.length()>50)throw new IOException("Ce moteur accepte 50 étapes par tâche : répartir le lot en deux identifiants");
        return AgentTasks.command(app,new JSONObject().put("operation","submit").put("id",id).put("steps",steps),active.isLocal2());
    }
}
