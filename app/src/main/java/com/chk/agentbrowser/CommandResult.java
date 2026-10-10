package com.chk.agentbrowser;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Never truncate a serialized JSON result or wedge the persistent delivery queue. */
final class CommandResult {
    static final int MAX_BYTES=240000;
    private CommandResult() {}
    static JSONObject envelope(String id,String action,boolean success,String message)throws Exception {
        boolean large=WorkspaceCommands.handles(action)||"preview".equals(action)||"screenshot".equals(action);
        JSONObject result=new JSONObject().put("id",id).put("ok",success);
        result.put(success?"result":"error",message==null?"":message);
        if((success&&message!=null&&message.length()>(large?230000:32000))||
                result.toString().getBytes(StandardCharsets.UTF_8).length>(large?MAX_BYTES:47000)) {
            result=new JSONObject().put("id",id).put("ok",false).put("error",
                "Réponse trop volumineuse. L'action peut avoir abouti : relis l'état avant de réessayer. Utilise la pagination pour les lectures.");
        }
        return result;
    }
}
