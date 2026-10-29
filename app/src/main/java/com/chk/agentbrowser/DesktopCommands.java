package com.chk.agentbrowser;

import android.content.Context;
import android.webkit.WebView;
import org.json.JSONObject;

/** Agent tools share the existing MCP consent and an existing-tool compatibility carrier. */
final class DesktopCommands {
    static final String CARRIER="chk://desktop";
    private DesktopCommands(){}
    static boolean handles(String action,JSONObject args){return action.equals("desktop")||action.equals("type")&&args!=null&&CARRIER.equals(args.optString("selector"));}
    static JSONObject parameters(String action,JSONObject args)throws Exception{
        JSONObject p=action.equals("type")?new JSONObject(args.getString("text")):args;
        if(p==null||p.toString().getBytes("UTF-8").length>60000)throw new Exception("Commande IA trop grande (60 Ko maximum).");
        if(!java.util.Arrays.asList("vision","pointer","files","tasks","terminal","browser","control","hearing","social_native").contains(p.optString("tool")))throw new Exception("Outil IA inconnu.");return p;
    }
    static boolean nativeTarget(JSONObject command){
        if(!handles(command.optString("action"),command.optJSONObject("args")))return false;
        try{JSONObject p=parameters(command.optString("action"),command.optJSONObject("args"));if(p.optString("tool").equals("browser"))return false;return !(p.optString("tool").equals("vision")||p.optString("tool").equals("pointer"))||!p.optString("target","native").equals("web");}catch(Exception e){return true;}
    }
    static AgentClient.CommandHandler handler(Context app){return new AgentClient.CommandHandler(){
        public String pageUrl(){return "chk://desktop";}public String pageTitle(){return "Outils IA CHK";}public String sessionSource(){return "native";}
        public void onCommand(JSONObject command,AgentClient.ResultCallback cb){
            if(!cb.isActive())return;
            Runnable run=()->execute(app,null,command.optString("action"),command.optJSONObject("args"),cb);
            if(AgentClient.get(app).isAutonomous()){run.run();return;}
            try{if(!NativeKeyboard.foreground())throw new Exception("Ouvre CHK pour autoriser l’action en mode manuel.");
                new AgentDialog.Builder(NativeKeyboard.activity()).setTitle("Outil IA CHK").setMessage("Autoriser l’outil demandé par l’agent dans CHK ?")
                    .setNegativeButton("Refuser",(d,w)->cb.finish(false,"Action refusée."))
                    .setPositiveButton("Autoriser",(d,w)->WorkspaceCommands.UI.post(run))
                    .setOnCancelListener(d->cb.finish(false,"Action annulée.")).show();
            }catch(Exception e){cb.finish(false,e.getMessage());}
        }
    };}
    static void execute(Context c,WebView web,String action,JSONObject args,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{JSONObject p=parameters(action,args);
            if((p.optString("tool").equals("tasks")||p.optString("tool").equals("terminal"))&&p.optString("operation").equals("cancel"))AgentTasks.requestCancel(p.optString("id"));
            switch(p.getString("tool")){
            case "control":AgentControl.execute(c,p,cb);return;
            case "social_native":NativeSocialControlService.command(p,cb);return;
            case "hearing":AgentHearing.execute(c,p,cb);return;
            case "browser":BrowserAutomation.execute(c,web,p,cb);return;
            case "vision":AgentVision.execute(c,web,p,cb);return;
            case "pointer":AgentPointer.execute(c,web,p,cb);return;
            default:WorkspaceCommands.IO.execute(()->{try{if(!cb.isActive())return;JSONObject result;
                if(p.getString("tool").equals("files"))result=AgentFiles.command(c,p,cb);
                else if(p.getString("tool").equals("tasks"))result=AgentTasks.command(c,p,cb.isLocal2(),cb.isLocal3());
                else result=AgentScript.command(c,p);
                WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(true,result.toString());});
            }catch(Exception e){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(false,e.getMessage()==null?"Outil IA indisponible.":e.getMessage());});}});
        }}catch(Exception e){cb.finish(false,e.getMessage());}
    }
}
