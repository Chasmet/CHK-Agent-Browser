package com.chk.agentbrowser;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Safe Tor entry bridge. Never claims Tor network status from another process. */
final class TorMcp {
    private TorMcp(){}
    static JSONObject status(Context app)throws Exception {
        boolean installed=app.getPackageManager().getLaunchIntentForPackage(TorEntryActivity.PACKAGE)!=null;
        return new JSONObject().put("state","completed").put("checked","official_tor_browser_installed")
            .put("installed",installed).put("package_identity",TorEntryActivity.PACKAGE).put("official_signature_verified",false).put("connection_state",installed?"unverified":"disconnected").put("can_verify_tor_connection",false).put("can_disconnect_external_tor",false).put("tor_connected",JSONObject.NULL)
            .put("tor_verified",false).put("isolation","separate_android_application")
            .put("diagnostic","Open official Tor Browser and verify check.torproject.org inside it.");
    }
    static void execute(Context app,JSONObject args,AgentClient.ResultCallback cb){
        try{
            String operation=args.optString("operation","status");
            if(operation.equals("status")||operation.equals("capabilities")){
                cb.finish(true,status(app).put("operation",operation).toString());return;
            }
            if(operation.equals("open")){
                Intent intent=new Intent(app,TorEntryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(intent);
                cb.finish(false,new JSONObject().put("state","action_sent")
                    .put("action_sent",true).put("outcome_confirmed",false).put("tor_verified",false)
                    .put("message","Espace Tor ouvert sur Android ; connexion Tor non confirmée.").toString());
                return;
            }
            cb.finish(false,new JSONObject().put("state","unsupported").put("outcome_confirmed",false)
                .put("message","La déconnexion de Tor Browser externe doit être effectuée dans Tor Browser.").toString());
        }catch(Exception e){cb.finish(false,"Tor non ouvert : "+e.getMessage());}
    }
}
