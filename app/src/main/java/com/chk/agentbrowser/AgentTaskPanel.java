package com.chk.agentbrowser;

import android.app.Activity;
import android.widget.Toast;
import org.json.*;

/** The same durable task controls are available on the phone and through MCP. */
final class AgentTaskPanel {
    static void show(Activity activity){
        WorkspaceCommands.IO.execute(()->{try{
            JSONArray rows=AgentTasks.list(activity).getJSONArray("items");
            String[] labels=new String[rows.length()];
            for(int i=0;i<labels.length;i++){JSONObject row=rows.getJSONObject(i);labels[i]=row.getString("id")+" · "+row.getString("state")+" · "+row.getInt("completed")+"/"+row.getInt("total");}
            activity.runOnUiThread(()->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                if(labels.length==0){Toast.makeText(activity,"Aucune tâche MCP enregistrée",Toast.LENGTH_LONG).show();return;}
                new AgentDialog.Builder(activity).setTitle("Tâches MCP")
                    .setItems(labels,(d,index)->{
                        JSONObject task=rows.optJSONObject(index);
                        new AgentDialog.Builder(activity).setTitle(task.optString("id"))
                            .setMessage("Progression : "+task.optInt("completed")+"/"+task.optInt("total")+"\n"+task.optJSONArray("steps").toString()+"\nLa reprise ne répète pas une action dont le résultat reste incertain.")
                            .setPositiveButton("Reprendre",(x,n)->run(activity,task.optString("id"),"resume"))
                            .setNeutralButton("Pause",(x,n)->run(activity,task.optString("id"),"pause"))
                            .setNegativeButton("Annuler la tâche",(x,n)->run(activity,task.optString("id"),"cancel")).show();
                    }).setNegativeButton("Fermer",null).show();
            });
        }catch(Exception e){error(activity,e);}});
    }
    private static void run(Activity activity,String id,String operation){
        WorkspaceCommands.IO.execute(()->{try{AgentTasks.command(activity,new JSONObject().put("id",id).put("operation",operation));activity.runOnUiThread(()->show(activity));}catch(Exception e){error(activity,e);}});
    }
    private static void error(Activity activity,Exception error){activity.runOnUiThread(()->{if(!activity.isFinishing())Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();});}
}
