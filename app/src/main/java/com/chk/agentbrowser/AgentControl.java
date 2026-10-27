package com.chk.agentbrowser;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.Executors;

/** Observe, input, observe again inside CHK. Independent tabs can progress concurrently. */
final class AgentControl {
    private static final long MAX_AGE=45000L;
    private static final LinkedHashMap<String,Snapshot> OBSERVATIONS=new LinkedHashMap<>();
    private static final Map<String,String> LOCKS=new HashMap<>(),REQUESTS=new HashMap<>();
    private static final java.util.concurrent.ExecutorService STORE=Executors.newSingleThreadExecutor();
    private AgentControl(){}
    private static final class Snapshot {
        final WeakReference<View> root;final WeakReference<WebView> web;
        final JSONObject before;
        final String id,target,tab,context,revision,keyboardContext;final long created=SystemClock.elapsedRealtime();
        Snapshot(View root,WebView web,String target,String tab,JSONObject info)throws Exception{
            this.root=new WeakReference<>(root);this.web=new WeakReference<>(web);this.target=target;this.tab=tab;
            id="obs-"+UUID.randomUUID();context=info.getString("context");revision=info.getString("revision");
            keyboardContext=info.getJSONObject("keyboard").getString("context");
            before=new JSONObject(info.toString());before.remove("jpeg_base64");
        }
    }
    static JSONObject capabilities()throws Exception{
        return new JSONObject().put("version",2).put("scope","chk_application_only")
            .put("operations",new JSONArray(Arrays.asList("capabilities","status","observe","act","parallel")))
            .put("inputs",new JSONArray(Arrays.asList("pointer","keyboard")))
            .put("observation_max_age_ms",MAX_AGE).put("max_parallel_workflows",4)
            .put("tab_targeting","tab_id, sans changer l'onglet affiché")
            .put("same_target_inputs","exclusive; native input excludes other input gestures")
            .put("action_receipts",true).put("automatic_after_observation",true)
            .put("pixels","real window or CHK private display pixels; timeout/unsupported devices return explicit DOM/cache fallback")
            .put("transport","desktop / tool=control; serveur 1.0.4 compatible");
    }
    private interface Outcome{void accept(boolean ok,String text);}
    private static AgentClient.ResultCallback wrap(AgentClient.ResultCallback parent,Outcome finish){
        return new AgentClient.ResultCallback(){
            public boolean isActive(){return parent.isActive();}public boolean isLocal2(){return parent.isLocal2();}public boolean isLocal3(){return parent.isLocal3();}
            public String inputLease(){return parent.inputLease();}
            public void finish(boolean ok,String text){finish.accept(ok,text);}
        };
    }
    static void execute(Context context,JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;Context app=context.getApplicationContext();
        try{
            switch(p.optString("operation","status")){
                case "capabilities":cb.finish(true,capabilities().toString());return;
                case "status":cb.finish(true,status(app,cb).toString());return;
                case "observe":observe(app,p,cb);return;
                case "act":act(app,p,cb);return;
                case "parallel":parallel(app,p,cb);return;
                default:throw new Exception("Opération de contrôle CHK inconnue.");
            }
        }catch(Exception e){cb.finish(false,message(e));}
    }
    private static JSONObject status(Context app,AgentClient.ResultCallback cb)throws Exception{
        JSONObject out=capabilities().put("ok",true).put("foreground",NativeKeyboard.foreground());
        JSONArray tabs=new JSONArray(),locks=new JSONArray();AgentClient.CommandHandler browser=AgentClient.get(app).controlBrowser(cb);
        if(browser!=null)for(WebView web:browser.browserTabs())tabs.put(new JSONObject().put("id",BrowserTabIds.id(web))
            .put("selected",web==browser.selectedWebView()).put("url",web.getUrl()==null?"":web.getUrl())
            .put("title",web.getTitle()==null?"":web.getTitle()).put("shown",web.isShown()&&NativeKeyboard.foreground()));
        for(String lane:LOCKS.keySet())locks.put(new JSONObject().put("target",lane).put("request_id",LOCKS.get(lane)));
        return out.put("tabs",tabs).put("active_inputs",locks);
    }
    static WebView resolveWeb(Context app,JSONObject p,AgentClient.ResultCallback cb)throws Exception{
        AgentClient.CommandHandler browser=AgentClient.get(app).controlBrowser(cb);
        if(browser==null)throw new Exception("Ouvre le navigateur CHK ; aucun onglet conservé.");
        String tab=p.optString("tab_id","");
        if(tab.isEmpty()){WebView selected=browser.selectedWebView();if(selected!=null)return selected;}
        else for(WebView web:browser.browserTabs())if(BrowserTabIds.id(web).equals(tab))return web;
        throw new Exception("Onglet cible fermé ou introuvable. Aucun autre onglet ne sera utilisé.");
    }
    private static void observe(Context app,JSONObject p,AgentClient.ResultCallback cb)throws Exception{
        String target=p.optString("target","native");
        if(!target.equals("native")&&!target.equals("web"))throw new Exception("Cible attendue : native ou web.");
        WebView web=target.equals("web")?resolveWeb(app,p,cb):null;
        observeAt(app,web,p,cb);
    }
    private static void observeAt(Context app,WebView web,JSONObject p,AgentClient.ResultCallback cb)throws Exception{
        String target=p.optString("target","native");View root=AgentVision.target(p,web);
        AgentVision.execute(app,web,new JSONObject(p.toString()).put("tool","vision"),wrap(cb,(ok,text)->{
            if(!cb.isActive())return;if(!ok){cb.finish(false,text);return;}
            try{
                JSONObject info=new JSONObject(text);String context=info.getString("context");
                JSONObject key=new JSONObject().put("target",target).put("operation","inspect");
                if(p.has("expected_host"))key.put("expected_host",p.get("expected_host"));
                KeyboardCommands.run(app,web,"keyboard",key,wrap(cb,(keyOk,keyboard)->{
                    if(!cb.isActive())return;
                    try{
                        if(!keyOk)throw new Exception("Clavier non observable : "+keyboard);
                        if(AgentVision.target(p,web)!=root||!AgentVision.context(root).equals(context))throw new Exception("Écran changé pendant l'observation.");
                        info.put("keyboard",new JSONObject(keyboard));String tab=web==null?"":BrowserTabIds.id(web);
                        Snapshot snapshot=new Snapshot(root,web,target,tab,info);
                        Iterator<Snapshot> iterator=OBSERVATIONS.values().iterator();while(iterator.hasNext())if(SystemClock.elapsedRealtime()-iterator.next().created>MAX_AGE)iterator.remove();
                        while(OBSERVATIONS.size()>=64)OBSERVATIONS.remove(OBSERVATIONS.keySet().iterator().next());
                        OBSERVATIONS.put(snapshot.id,snapshot);
                        cb.finish(true,info.put("observation_id",snapshot.id).put("tab_id",tab).put("expires_in_ms",MAX_AGE).toString());
                    }catch(Exception e){cb.finish(false,message(e));}
                }));
            }catch(Exception e){cb.finish(false,message(e));}
        }));
    }
    private static void act(Context app,JSONObject p,AgentClient.ResultCallback cb)throws Exception{
        String id=p.optString("id","");if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new Exception("Chaque action exige un id stable (80 caractères maximum).");
        JSONObject input=p.getJSONObject("input");String tool=input.optString("tool");
        if(!tool.equals("pointer")&&!tool.equals("keyboard"))throw new Exception("L'entrée doit être pointer ou keyboard.");
        JSONObject expect=p.optJSONObject("expect");if(expect!=null){if(expect.length()==0)throw new Exception("Résultat attendu vide.");
            Iterator<String> keys=expect.keys();while(keys.hasNext()){String key=keys.next();if(!Arrays.asList("text_contains","url_contains","field_value").contains(key)||!(expect.get(key) instanceof String)||!key.equals("field_value")&&expect.getString(key).isEmpty())throw new Exception("Vérification attendue : text_contains, url_contains ou field_value.");}
        }
        String fingerprint=AgentVision.digest(new JSONObject().put("observation_id",p.getString("observation_id")).put("input",input)
            .put("expect",expect).put("capture_after",p.optBoolean("capture_after",false)).put("target",p.optString("target","")).put("tab_id",p.optString("tab_id","")).toString());
        File journal=AgentFiles.journal(app,"control",id);
        STORE.execute(()->{
            try{
                if(journal.isFile()){
                    JSONObject saved=AgentFiles.read(journal);
                    if(!saved.getString("fingerprint").equals(fingerprint))throw new Exception("Cet id appartient à une autre action. Aucun geste rejoué.");
                    JSONObject result=saved.has("result")?saved.getJSONObject("result"):new JSONObject().put("id",id).put("state","unknown").put("outcome_confirmed",false).put("reason","Action interrompue ou encore en cours : observer avant toute nouvelle action.");
                    result.put("replayed",true);reply(cb,true,result.toString());return;
                }
                WorkspaceCommands.UI.post(()->begin(app,p,cb,id,fingerprint,journal));
            }catch(Exception e){reply(cb,false,message(e));}
        });
    }
    private static void begin(Context app,JSONObject p,AgentClient.ResultCallback cb,String id,String fingerprint,File journal){
        if(!cb.isActive())return;
        String lane=null;
        try{
            if(REQUESTS.containsKey(id)){
                if(!REQUESTS.get(id).equals(fingerprint))throw new Exception("Identifiant déjà actif pour une autre action.");
                cb.finish(true,new JSONObject().put("ok",true).put("id",id).put("state","executing").put("replayed",true).put("outcome_confirmed",false).toString());return;
            }
            Snapshot snapshot=OBSERVATIONS.get(p.getString("observation_id"));
            if(snapshot==null||SystemClock.elapsedRealtime()-snapshot.created>MAX_AGE)throw new Exception("Observation absente ou expirée : observer l'écran avant l'action.");
            if(p.has("target")&&!p.getString("target").equals(snapshot.target)||p.has("tab_id")&&!p.getString("tab_id").equals(snapshot.tab))throw new Exception("L'observation appartient à une autre cible.");
            View root=snapshot.root.get();WebView web=snapshot.web.get();
            if(root==null||snapshot.target.equals("web")&&!BrowserWebState.alive(web))throw new Exception("Écran ou onglet fermé.");
            lane=snapshot.target.equals("native")?"native":snapshot.tab;
            if(LOCKS.containsKey(lane)||LOCKS.containsKey("native")||lane.equals("native")&&!LOCKS.isEmpty())throw new Exception("Une entrée utilise déjà cet écran. Attendre sa fin sans répéter l'action.");
            LOCKS.put(lane,id);REQUESTS.put(id,fingerprint);final String key=lane;
            JSONObject saved=new JSONObject().put("id",id).put("fingerprint",fingerprint).put("state","executing").put("target",snapshot.target).put("tab_id",snapshot.tab).put("started_at",System.currentTimeMillis());
            STORE.execute(()->{try{AgentFiles.save(journal,saved);WorkspaceCommands.UI.post(()->dispatch(app,p,cb,snapshot,id,key,journal,saved));}
                catch(Exception e){WorkspaceCommands.UI.post(()->{release(key,id);if(cb.isActive())cb.finish(false,message(e));});}});
        }catch(Exception e){if(lane!=null)release(lane,id);cb.finish(false,message(e));}
    }
    private static void dispatch(Context app,JSONObject p,AgentClient.ResultCallback cb,Snapshot snapshot,String id,String lane,File journal,JSONObject saved){
        final java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable interrupted=()->{if(finished.compareAndSet(false,true))complete(journal,saved,cb,lane,id,false,null,"Action interrompue : ne pas la répéter sans observation.");};
        WorkspaceCommands.UI.postDelayed(interrupted,30000L);
        WorkspaceCommands.UI.postDelayed(new Runnable(){public void run(){if(finished.get())return;if(!cb.isActive()){interrupted.run();return;}WorkspaceCommands.UI.postDelayed(this,100L);}},100L);
        AgentClient.ResultCallback guarded=new AgentClient.ResultCallback(){
            public String inputLease(){return id;}
            public boolean isLocal2(){return cb.isLocal2();}public boolean isLocal3(){return cb.isLocal3();}
            public boolean isActive(){return cb.isActive()&&!finished.get();}
            public void finish(boolean ok,String text){
                if(finished.get())return;
                try{
                    JSONObject result;try{result=new JSONObject(text);}catch(Exception ignored){result=new JSONObject().put("message",text);}
                    boolean sent=ok&&result.optBoolean("ok",true);JSONObject inputResult=result;
                    JSONObject afterParams=new JSONObject().put("target",snapshot.target).put("capture",p.optBoolean("capture_after",false));
                    observeAt(app,snapshot.web.get(),afterParams,wrap(this,(seen,afterText)->{
                        if(!finished.compareAndSet(false,true))return;WorkspaceCommands.UI.removeCallbacks(interrupted);
                        try{
                            JSONObject after=seen?new JSONObject(afterText):new JSONObject().put("observation_error",afterText);
                            boolean confirmed=sent&&seen&&p.has("expect")&&matches(p.getJSONObject("expect"),after,p.getJSONObject("input"));
                            JSONObject out=new JSONObject().put("ok",sent).put("id",id).put("state",sent?"completed":"unconfirmed")
                                .put("action_attempted",true).put("action_sent",sent?Boolean.TRUE:JSONObject.NULL).put("effect_uncertain",!sent)
                                .put("outcome_confirmed",confirmed).put("input_result",inputResult).put("after",after);
                            complete(journal,saved,cb,lane,id,sent,out,null);
                        }catch(Exception e){complete(journal,saved,cb,lane,id,false,null,message(e));}
                    }));
                }catch(Exception e){if(finished.compareAndSet(false,true))complete(journal,saved,cb,lane,id,false,null,message(e));}
            }
        };
        try{
            if(!guarded.isActive()){interrupted.run();return;}
            View root=snapshot.root.get();WebView web=snapshot.web.get();JSONObject target=new JSONObject().put("target",snapshot.target);
            if(root==null||AgentVision.target(target,web)!=root||!AgentVision.context(root).equals(snapshot.context)||SystemClock.elapsedRealtime()-snapshot.created>MAX_AGE)throw new Exception("Écran remplacé ou observation expirée. Aucun geste envoyé.");
            AgentVision.execute(app,web,target,wrap(guarded,(ok,observed)->{
                if(!guarded.isActive())return;
                try{
                    if(!ok||!new JSONObject(observed).getString("revision").equals(snapshot.revision))throw new Exception("L'interface a changé depuis l'observation. Aucun geste envoyé.");
                    JSONObject input=new JSONObject(p.getJSONObject("input").toString()).put("target",snapshot.target);
                    String tool=input.getString("tool");
                    if(p.has("expect")&&matches(p.getJSONObject("expect"),snapshot.before,input)){
                        if(finished.compareAndSet(false,true)){
                            WorkspaceCommands.UI.removeCallbacks(interrupted);
                            complete(journal,saved,cb,lane,id,true,new JSONObject().put("ok",true).put("state","already_satisfied")
                                .put("action_attempted",false).put("action_sent",false).put("outcome_confirmed",false)
                                .put("condition_satisfied_before",true).put("reason","Condition déjà vraie avant l'action : aucun geste envoyé."),null);
                        }return;
                    }
                    if(tool.equals("pointer")){
                        input.put("expected_context",snapshot.context).put("expected_revision",snapshot.revision);
                        AgentPointer.execute(app,web,input,guarded);
                    }else{
                        input.put("expected_context",snapshot.keyboardContext);
                        KeyboardCommands.run(app,web,"keyboard",input,guarded);
                    }
                }catch(Exception e){if(finished.compareAndSet(false,true)){WorkspaceCommands.UI.removeCallbacks(interrupted);complete(journal,saved,cb,lane,id,false,new JSONObject(),message(e));}}
            }));
        }catch(Exception e){if(finished.compareAndSet(false,true)){WorkspaceCommands.UI.removeCallbacks(interrupted);complete(journal,saved,cb,lane,id,false,new JSONObject(),message(e));}}
    }
    private static boolean matches(JSONObject expect,JSONObject after,JSONObject input)throws Exception{
        if(expect.has("url_contains")&&!after.optString("url").contains(expect.getString("url_contains")))return false;
        if(expect.has("text_contains")){
            StringBuilder text=new StringBuilder(after.optString("text"));JSONArray controls=after.optJSONArray("controls");
            if(controls!=null)for(int i=0;i<controls.length();i++){JSONObject c=controls.getJSONObject(i);text.append('\n').append(c.optString("text")).append(' ').append(c.optString("label"));}
            if(!text.toString().contains(expect.getString("text_contains")))return false;
        }
        if(expect.has("field_value")){
            JSONArray fields=after.getJSONObject("keyboard").getJSONArray("controls");boolean found=false;
            for(int i=0;i<fields.length();i++){JSONObject field=fields.getJSONObject(i);if(field.optString("selector").equals(input.optString("selector"))&&field.optString("value").equals(expect.getString("field_value")))found=true;}
            if(!found)return false;
        }
        return true;
    }
    private static void complete(File journal,JSONObject saved,AgentClient.ResultCallback cb,String lane,String id,boolean ok,JSONObject out,String error){
        STORE.execute(()->{
            try{
                JSONObject result=out==null?new JSONObject().put("ok",false).put("state","unknown").put("action_sent",JSONObject.NULL).put("effect_uncertain",true).put("outcome_confirmed",false):out;
                result.put("id",id);
                if(error!=null){result.put("ok",false).put("reason",error);if(out!=null)result.put("state","rejected").put("action_attempted",false).put("action_sent",false).put("outcome_confirmed",false);}
                saved.put("result",result).put("state",result.optString("state")).put("finished_at",System.currentTimeMillis());AgentFiles.save(journal,saved);
                WorkspaceCommands.UI.postDelayed(()->{release(lane,id);if(cb.isActive())cb.finish(ok,result.toString());},32L);
            }catch(Exception e){WorkspaceCommands.UI.post(()->{release(lane,id);if(cb.isActive())cb.finish(false,"Résultat non enregistré : observer avant de renvoyer.");});}
        });
    }
    private static void release(String lane,String id){if(id.equals(LOCKS.get(lane)))LOCKS.remove(lane);REQUESTS.remove(id);}
    /** Existing pointer/keyboard tools share the same input exclusion as control/act. */
    static AgentClient.ResultCallback lease(WebView web,boolean nativeTarget,AgentClient.ResultCallback parent)throws Exception{
        if(!nativeTarget&&!BrowserWebState.alive(web))throw new Exception("Aucun onglet web disponible pour cette entrée.");
        String lane=nativeTarget?"native":BrowserTabIds.id(web);
        if(parent.inputLease().equals(LOCKS.get(lane)))return parent;
        if(LOCKS.containsKey(lane)||LOCKS.containsKey("native")||nativeTarget&&!LOCKS.isEmpty())throw new Exception("Une entrée agent utilise déjà cet écran. Aucun nouveau geste envoyé.");
        String owner="input-"+UUID.randomUUID();LOCKS.put(lane,owner);java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();
        AgentClient.ResultCallback leased=new AgentClient.ResultCallback(){
            public boolean isLocal2(){return parent.isLocal2();}public boolean isLocal3(){return parent.isLocal3();}
            public String inputLease(){return owner;}public boolean isActive(){return !ended.get()&&parent.isActive();}
            public void finish(boolean ok,String text){if(!ended.compareAndSet(false,true))return;release(lane,owner);if(parent.isActive())parent.finish(ok,text);}
        };
        WorkspaceCommands.UI.postDelayed(()->{if(!ended.compareAndSet(false,true))return;WorkspaceCommands.UI.postDelayed(()->{release(lane,owner);if(parent.isActive())parent.finish(false,"Entrée interrompue : vérifier l'écran avant de la répéter.");},32L);},30000L);return leased;
    }
    private static void parallel(Context app,JSONObject p,AgentClient.ResultCallback cb)throws Exception{
        JSONArray jobs=p.getJSONArray("jobs");if(jobs.length()<1||jobs.length()>4)throw new Exception("Entre 1 et 4 workflows parallèles par demande.");
        HashSet<String> ids=new HashSet<>();for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);String id=job.getString("id");if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id))throw new Exception("Identifiants de workflows invalides ou répétés.");AgentTasks.validate(job.getJSONArray("steps"));}
        WorkspaceCommands.IO.execute(()->{try{JSONArray results=new JSONArray();for(int i=0;i<jobs.length();i++){
            if(!cb.isActive())break;JSONObject job=new JSONObject(jobs.getJSONObject(i).toString()).put("operation","submit");
            try{results.put(AgentTasks.command(app,job,cb.isLocal2(),cb.isLocal3()));}catch(Exception e){results.put(new JSONObject().put("id",job.optString("id")).put("state","rejected").put("error",message(e)));}
        }reply(cb,true,new JSONObject().put("ok",true).put("jobs",results).put("completion_confirmed",false).put("status_action","workflow_status").toString());}
            catch(Exception e){reply(cb,false,message(e));}});
    }
    private static void reply(AgentClient.ResultCallback cb,boolean ok,String text){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(ok,text);});}
    private static String message(Exception e){return e.getMessage()==null?"Contrôle CHK indisponible.":e.getMessage();}
}
