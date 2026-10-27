package com.chk.agentbrowser;
import org.json.JSONObject;
import java.util.Arrays;
/** Only explicit tab targets may overlap browser inputs. */
final class Local3TaskPolicy {
    private Local3TaskPolicy(){}
    static String action(String a,JSONObject p){return a.equals("android_action")?p.optString("action",a):a;}
    static JSONObject args(String a,JSONObject p){JSONObject nested=a.equals("android_action")?p.optJSONObject("args"):null;return nested==null?p:nested;}
    static long timeout(String a,JSONObject args){
        String name=action(a,args);JSONObject p=args(a,args);
        if(TransferLimits.longOperation(name)||name.equals("media_transcribe")||name.equals("desktop")&&p.optString("tool").equals("hearing")&&p.optString("operation").equals("transcribe"))return 900000L;
        if(name.startsWith("media_")||name.equals("desktop")&&p.optString("tool").equals("hearing"))return 180000L;
        if(name.equals("browser_batch"))return 150000L;
        if(name.equals("browser_click")||name.equals("browser_click_when_enabled")||name.equals("click")||name.equals("click_verified")
            ||name.equals("desktop")&&p.optString("tool").equals("browser")&&p.optString("operation").equals("click_when_enabled"))return 60000L;
        return 45000L;
    }
    static String lane(String a,JSONObject args){
        String name=action(a,args);JSONObject p=args(a,args);
        if(Arrays.asList("local3_status","browser_status","files_capabilities","video_editor_capabilities").contains(name)
            ||name.equals("browser_tabs")&&p.optString("operation","list").equals("list"))return "read";
        if(name.equals("desktop")&&p.optString("tool").equals("control")){
            String op=p.optString("operation","status");
            if(op.equals("status")||op.equals("capabilities"))return "read";
            if(p.optString("target").equals("web")&&!p.optString("tab_id").isEmpty()&&(op.equals("observe")||op.equals("act")))return "tab:"+p.optString("tab_id");
        }
        if(name.equals("desktop")&&p.optString("tool").equals("hearing")&&!p.optString("operation").equals("media"))return "audio";
        return "exclusive";
    }
    static boolean conflicts(String a,String b){
        if(a.equals("read")||b.equals("read"))return false;
        if(a.equals("audio")||b.equals("audio"))return a.equals(b);
        return a.equals("exclusive")||b.equals("exclusive")||a.equals(b);
    }
}
