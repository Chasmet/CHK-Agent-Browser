package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;

/** CHK Script v1 is an allowlisted data language, without shell, process execution or network. */
final class AgentScript {
    private AgentScript(){}
    static JSONObject command(Context app,JSONObject p)throws Exception{
        String operation=p.optString("operation","validate");
        if(Arrays.asList("status","resume","cancel").contains(operation))return AgentTasks.command(app,new JSONObject(p.toString()).put("operation",operation));
        JSONObject script=p.getJSONObject("script");if(script.optInt("version",0)!=1)throw new Exception("Langage autorisé : CHK Script version 1.");
        JSONArray commands=script.getJSONArray("commands");if(commands.length()<1||commands.length()>50)throw new Exception("Un script contient de 1 à 50 commandes.");
        JSONArray steps=new JSONArray(),preview=new JSONArray();String id=p.getString("id");
        for(int i=0;i<commands.length();i++){
            JSONObject cmd=new JSONObject(commands.getJSONObject(i).toString());String mode=cmd.getString("command");
            if(!Arrays.asList("rename","organize","copy","move","trash","transform").contains(mode))throw new Exception("Commande de script non autorisée : "+mode);
            cmd.remove("command");cmd.put("mode",mode).put("operation","apply").put("id","script-"+AgentVision.digest(id+":"+i).substring(0,28)).put("dry_run",false).put("tool","files");
            // Expand and validate every path and transformation before starting the workflow.
            if(operation.equals("validate")){JSONObject plan=AgentFiles.plan(app,cmd);preview.put(new JSONObject().put("command",mode).put("total",plan.getJSONArray("items").length()).put("items",first(plan.getJSONArray("items"),10)));}
            steps.put(new JSONObject().put("id","command_"+i).put("action","desktop").put("args",cmd));
        }
        AgentTasks.validate(steps);
        if(operation.equals("validate"))return new JSONObject().put("ok",true).put("language","chk-script-v1").put("dry_run",true).put("commands",preview);
        if(!operation.equals("run"))throw new Exception("Terminal : validate, run, status, resume ou cancel.");
        return AgentTasks.command(app,new JSONObject().put("id",id).put("operation","submit").put("steps",steps));
    }
    private static JSONArray first(JSONArray rows,int maximum)throws Exception{JSONArray result=new JSONArray();for(int i=0;i<Math.min(maximum,rows.length());i++)result.put(rows.get(i));return result;}
}
