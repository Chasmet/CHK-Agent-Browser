package com.chk.agentbrowser;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
/** Main-thread dispatch; asynchronous work never holds the poller. */
final class Local3Dispatcher {
    interface Execute {void run(String action,JSONObject args,DirectMcpServer.Reply reply);}
    private final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
    private final Execute executor;
    private final Runnable monitor=()->{reap();drain();};
    private void reap(){
        for(Job job:new ArrayList<>(jobs.values()))if(!job.reply.isActive()){
            job.done.set(true);jobs.remove(job.id);job.reply.finish(false,"Commande interrompue ; vérifier avant répétition.");
        }
    }
    Local3Dispatcher(Execute executor){this.executor=executor;}
    private static final class Job {String id,action,lane;JSONObject args;DirectMcpServer.Reply reply;boolean started;AtomicBoolean done=new AtomicBoolean();}
    void submit(String id,String action,JSONObject args,DirectMcpServer.Reply reply){
        if(jobs.containsKey(id))return;
        Job job=new Job();job.id=id;job.action=action;job.args=args;job.reply=reply;job.lane=Local3TaskPolicy.lane(action,args);jobs.put(id,job);drain();
    }
    private void drain(){
        WorkspaceCommands.UI.removeCallbacks(monitor);
        for(Job job:new ArrayList<>(jobs.values())){
            if(job.started||job.done.get())continue;
            if(!job.reply.isActive()){job.done.set(true);jobs.remove(job.id);job.reply.finish(false,"Canal interrompu avant l'exécution : aucun geste envoyé.");continue;}
            boolean conflict=false;
            for(Job other:jobs.values())if(other!=job&&other.started&&!other.done.get()&&Local3TaskPolicy.conflicts(job.lane,other.lane)){conflict=true;break;}
            if(conflict)continue;
            job.started=true;
            DirectMcpServer.Reply guarded=new DirectMcpServer.Reply(){
                public boolean isActive(){return !job.done.get()&&job.reply.isActive();}
                public void finish(boolean ok,String text){
                    if(!job.done.compareAndSet(false,true))return;
                    job.reply.finish(ok,text);
                    WorkspaceCommands.UI.post(()->{jobs.remove(job.id);drain();});
                }
            };
            try{executor.run(job.action,job.args,guarded);}catch(RuntimeException e){guarded.finish(false,"Commande interrompue : vérifier son effet, aucun rejeu automatique.");}
        }
        if(!jobs.isEmpty())WorkspaceCommands.UI.postDelayed(monitor,250L);
    }
}
