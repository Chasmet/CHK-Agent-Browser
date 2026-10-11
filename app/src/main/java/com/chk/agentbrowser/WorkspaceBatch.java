package com.chk.agentbrowser;

import android.content.Context;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Each selected item is independent; a collision must not hide earlier successes. */
final class WorkspaceBatch {
    private WorkspaceBatch(){}
    static JSONObject run(Context context,List<String> paths,int operation,String destination)throws Exception {
        WorkspaceStore store=new WorkspaceStore(context);
        JSONArray succeeded=new JSONArray(),failed=new JSONArray();
        for(String path:paths){
            try{
                if(operation==2)store.trash(path);
                else{
                    String target=WorkspacePaths.child(destination,path.substring(path.lastIndexOf('/')+1));
                    if(operation==0)WorkspaceCatalog.copy(context,path,target);
                    else if(operation==1)store.move(path,target);
                    else throw new IllegalArgumentException("Action inconnue");
                }
                succeeded.put(path);
            }catch(Exception e){failed.put(new JSONObject().put("path",path)
                .put("error",e.getMessage()==null?"Opération impossible":e.getMessage()));}
        }
        return new JSONObject().put("succeeded",succeeded).put("failed",failed).put("total",paths.size());
    }
}
