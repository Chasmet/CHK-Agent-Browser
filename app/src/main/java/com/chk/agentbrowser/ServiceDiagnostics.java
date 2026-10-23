package com.chk.agentbrowser;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import org.json.JSONObject;

/** Own-app lifecycle information only. No process traces, private descriptions or external packages. */
final class ServiceDiagnostics {
    private ServiceDiagnostics(){}
    static void captureProcessExit(Context c){
        if(Build.VERSION.SDK_INT<30)return;
        try{
            ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(c.getPackageName(),0,8)){
                if(!c.getPackageName().equals(exit.getProcessName()))continue;
                android.content.SharedPreferences p=c.getSharedPreferences("mcp_service_health",0);
                if(exit.getTimestamp()<=p.getLong("exit_time",0))return;
                String reason=reason(exit.getReason());
                p.edit().putLong("exit_time",exit.getTimestamp()).putString("exit_reason",reason).apply();
                AgentLog.add(c,"processus","dernier arrêt enregistré par Android : "+reason);return;
            }
        }catch(RuntimeException ignored){}
    }
    private static String reason(int code){
        if(Build.VERSION.SDK_INT>=30){
            if(code==ApplicationExitInfo.REASON_LOW_MEMORY)return "mémoire insuffisante";
            if(code==ApplicationExitInfo.REASON_CRASH)return "plantage Java";
            if(code==ApplicationExitInfo.REASON_CRASH_NATIVE)return "plantage natif";
            if(code==ApplicationExitInfo.REASON_ANR)return "application non répondante";
            if(code==ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)return "limite de ressources";
            if(code==ApplicationExitInfo.REASON_USER_REQUESTED)return "arrêt utilisateur signalé par Android";
            if(code==ApplicationExitInfo.REASON_PERMISSION_CHANGE)return "autorisations modifiées";
        }
        return "code Android "+code;
    }
    static String summary(Context c){
        JSONObject state=AgentService.state(c);
        android.content.SharedPreferences p=c.getSharedPreferences("mcp_service_health",0);
        long exitTime=p.getLong("exit_time",0),next=state.optLong("next_recovery_at");
        String exitDate=exitTime==0?"":new java.text.SimpleDateFormat("dd/MM HH:mm:ss",java.util.Locale.FRANCE).format(new java.util.Date(exitTime));
        return "Service Android : "+(state.optBoolean("foreground")?"actif au premier plan · notification persistante":state.optBoolean("running")?"initialisation":"arrêté")
            +"\nDurée de cette session service : "+(state.optLong("uptime_ms")/1000)+" s"
            +"\nDernière fin de service : "+state.optString("last_stop")
            +"\nDernier refus de démarrage : "+state.optString("start_error")
            +"\nReprise demandée : "+(next==0?"aucune en attente":"au plus tôt dans "+Math.max(0,(next-System.currentTimeMillis())/1000)+" s, selon Android")
            +"\nDernière fin de processus enregistrée : "+(exitTime==0?"non disponible":exitDate+" · "+p.getString("exit_reason","inconnue"));
    }
}
