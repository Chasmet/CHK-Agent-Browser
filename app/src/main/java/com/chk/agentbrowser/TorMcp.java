package com.chk.agentbrowser;

import android.content.*;
import android.os.*;
import org.json.*;
import java.util.Arrays;

/** Explicit Tor commands over the unchanged Local 3 desktop carrier. */
final class TorMcp {
    static final java.util.List<String> OPERATIONS=Arrays.asList("status","capabilities","open","connect","disconnect","open_browser","close_browser","navigate","back","forward","reload","diagnostic","read","screenshot","open_external");
    static String operation(JSONObject args){String op=args.optString("operation","status");return op.startsWith("tor_")?op.substring(4):op;}
    static boolean readOnly(JSONObject args){return Arrays.asList("status","capabilities","diagnostic","read","screenshot").contains(operation(args));}
    private TorMcp(){}
    static JSONObject status(Context app)throws Exception{
        return new JSONObject().put("state","completed").put("checked","capabilities_only_not_a_connection_proof")
            .put("integrated_supported",Build.VERSION.SDK_INT>=29).put("integrated_min_android_api",29)
            .put("installed",app.getPackageManager().getLaunchIntentForPackage(TorEntryActivity.PACKAGE)!=null)
            .put("package_identity",TorEntryActivity.PACKAGE).put("official_signature_verified",false)
            .put("can_verify_tor_connection",Build.VERSION.SDK_INT>=29).put("can_disconnect_external_tor",false)
            .put("tor_connected",JSONObject.NULL).put("tor_verified",false).put("isolation","dedicated_process_and_webview_data_directory")
            .put("operations",new JSONArray(OPERATIONS))
            .put("write_guard","navigation: session_id from status/connect and stable request_id required")
            .put("javascript_enabled",false).put("external_tor_state_observable",false)
            .put("background_navigation","retained_isolated_webview_only; open_browser once; no direct network fallback")
            .put("background_capture","webview_draw_background is an offscreen render, not native window pixels");
    }
    static void execute(Context app,JSONObject args,AgentClient.ResultCallback cb){WorkspaceCommands.UI.post(()->{
        try{
            String op=operation(args);
            if(op.equals("capabilities")){cb.finish(true,status(app).toString());return;}
            if(op.equals("open")){
                app.startActivity(new Intent(app,TorEntryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitOpen(cb,SystemClock.elapsedRealtime()+5000);return;
            }
            if(op.equals("open_external")){
                Intent launch=app.getPackageManager().getLaunchIntentForPackage(TorEntryActivity.PACKAGE);if(launch==null)throw new Exception("TOR_BROWSER_NOT_INSTALLED");app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                cb.finish(false,new JSONObject().put("state","action_sent").put("outcome_confirmed",false).put("tor_verified",false).put("message","Lancement externe envoyé ; circuit Tor non vérifié par CHK").toString());return;
            }
            if(op.equals("disconnect")&&!args.has("session_id"))throw new Exception("TOR_SESSION_ID_REQUIRED");
            JSONObject command=new JSONObject(args.toString());command.put("operation",op);command.remove("tool");
            if(op.equals("screenshot"))command.put("preview_allowed",AgentClient.get(app).isPreviewAllowed());
            TorBridge.request(app,command,(ok,value)->{if(cb.isActive())cb.finish(ok,value.toString());});
        }catch(Exception e){cb.finish(false,TorBridge.error(e.getMessage()).toString());}
    });}
    private static void waitOpen(AgentClient.ResultCallback cb,long deadline){if(!cb.isActive())return;if(TorEntryActivity.visible){cb.finish(true,"{\"state\":\"completed\",\"ui_open\":true,\"verified\":\"activity_resumed\",\"connection_checked\":false}");return;}if(SystemClock.elapsedRealtime()>deadline){cb.finish(false,TorBridge.error("TOR_ENTRY_OPEN_UNCONFIRMED").toString());return;}WorkspaceCommands.UI.postDelayed(()->waitOpen(cb,deadline),100);}
}
