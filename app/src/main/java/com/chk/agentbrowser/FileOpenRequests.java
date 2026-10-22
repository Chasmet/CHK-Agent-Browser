package com.chk.agentbrowser;

import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;

/** A launch request is acknowledged only by the loaded, focused viewer. */
final class FileOpenRequests {
    private static final Map<String,AgentClient.ResultCallback> pending=new HashMap<>();
    static void open(Context app,String path,AgentClient.ResultCallback cb){WorkspaceCommands.UI.post(()->{
        if(!cb.isActive())return;String id=UUID.randomUUID().toString();pending.put(id,cb);
        try{app.startActivity(new Intent(app,FileViewerActivity.class).putExtra("path",path).putExtra("request_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(Exception ex){pending.remove(id);cb.finish(false,"Ouverture Android refusée : "+ex.getClass().getSimpleName());return;}
        WorkspaceCommands.UI.postDelayed(()->{AgentClient.ResultCallback waiting=pending.remove(id);if(waiting!=null&&waiting.isActive())waiting.finish(false,"Affichage non confirmé : ouvre CHK au premier plan puis réessaie files_open");},8000);
    });}
    static void visible(String id,String path){if(id==null)return;AgentClient.ResultCallback cb=pending.remove(id);if(cb!=null&&cb.isActive())try{cb.finish(true,new JSONObject().put("opened",true).put("visible",true).put("path",path).put("viewer","chk_integrated").toString());}catch(Exception ex){cb.finish(false,ex.getMessage());}}
}
