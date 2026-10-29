package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

/** Durable local workflows; actions are checkpointed before dispatch and before verification. */
final class AgentTasks {
    private static final Set<String> running=new HashSet<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,Boolean> active=new java.util.concurrent.ConcurrentHashMap<>(); // Accessed only on WorkspaceCommands.IO.
    private static final Set<String> ACTIONS=new HashSet<>(Arrays.asList("files_detect_format","files_copy_content","files_extract_archive","files_convert","files_search_content","files_media_inspect","files_verify","files_archive_list","files_archive_read","files_database","files_edit_text","files_stat","files_hash","files_zip","files_unzip","files_external","document_create","document_read","document_page","media_frame","video_editor_capabilities","video_editor_project_import","video_editor_timeline","video_editor_subtitles_generate","video_editor_history","video_editor_queue_add","video_editor_queue_status","video_editor_queue_pause","video_editor_queue_resume","video_editor_project_read","video_editor_project_save","video_editor_project_open","video_editor_export","video_editor_status","video_editor_wait","video_editor_verify_output","get_form","wait_for_element","click_verified","type","select_option","check","upload_file","download_file","keyboard","desktop","files_list","files_catalog","files_mkdir","files_copy","files_move","files_trash","files_read_text","files_write_text","notes_list","notes_read","notes_save","media_info","media_analyze_audio","media_audio_capabilities","media_transcribe","workspace_status","tabs","list_tabs","get_page_status","read_page","scroll","collect_public_comments","open_url","new_tab","open_tab","resume_visible_page","publication_prepare","publication_status","publication_list","publication_cancel","publication_submit","switch_tab","close_tab","translate_page"));
    private AgentTasks(){}
    static void requestCancel(String id){active.put(id,false);}
    static void validate(JSONArray steps)throws Exception{
        if(steps==null||steps.length()<1)throw new Exception("Une tâche doit contenir au moins une étape.");
        HashSet<String> ids=new HashSet<>();
        for(int i=0;i<steps.length();i++){
            JSONObject step=steps.getJSONObject(i);String id=step.getString("id");if(!id.matches("[A-Za-z0-9_-]{1,45}")||!ids.add(id))throw new Exception("Chaque étape doit avoir un identifiant unique.");
            validateAction(step.getString("action"),step.optJSONObject("args"));
            JSONObject verify=step.optJSONObject("verify");if(verify!=null){String action=verify.getString("action");JSONObject args=verify.optJSONObject("args");validateAction(action,args);
                if(!readOnly(action,args))throw new Exception("Une vérification doit uniquement lire l’état.");
                if(verify.optJSONObject("expect")==null&&verify.optJSONObject("contains")==null)throw new Exception("Précise le résultat attendu par la vérification.");}
            String action=step.getString("action");
            if(action.equals("desktop")&&Arrays.asList("browser","control").contains(step.getJSONObject("args").optString("tool"))
                &&!readOnly(action,step.getJSONObject("args"))&&verify==null)throw new Exception("Une écriture navigateur exige une vérification explicite.");
            if(action.equals("desktop")&&step.getJSONObject("args").optString("tool").equals("tor")&&step.getJSONObject("args").optString("operation").equals("open")&&verify==null)throw new Exception("Ouverture Tor : lecture d’état obligatoire après l’envoi.");
            if(action.equals("desktop")&&step.getJSONObject("args").optString("tool").equals("studio")&&!readOnly(action,step.getJSONObject("args"))&&verify==null)throw new Exception("Studio : vérification du projet obligatoire après écriture.");
            if(action.equals("desktop")&&step.getJSONObject("args").optString("tool").equals("pointer")&&verify==null)
                throw new Exception("Un geste dans une tâche exige une vérification explicite.");
            if(Arrays.asList("type","select_option","check","upload_file","click_verified","download_file").contains(action)&&verify==null)
                throw new Exception("La saisie, le choix, le clic vérifié et l'importation exigent une lecture de contrôle après l'action.");
        }
    }
    private static void validateAction(String action,JSONObject p)throws Exception{
        if(!ACTIONS.contains(action)||p==null)throw new Exception("Action de tâche non autorisée : "+action);
        if(action.equals("download_file")||action.equals("desktop")&&p.optString("tool").equals("browser")&&p.optString("operation").equals("download_file")){
            if(!p.optString("id","").matches("[A-Za-z0-9_-]{1,80}"))throw new Exception("Téléchargement : id stable obligatoire pour reprendre sans doublon.");
            if(p.optString("expected_host","").isEmpty())throw new Exception("Téléchargement : domaine de destination attendu obligatoire.");
        }
        if(action.equals("collect_public_comments"))PublicCommentCollector.validate(p);
        if(action.equals("publication_submit")){
            String selector=p.optString("selector","");
            String expected=p.optString("expected_host","");
            String proofUrl=p.optString("expected_url_contains","");
            String proofText=p.optString("expected_text","");
            if(!p.optString("id","").matches("[A-Za-z0-9_-]{1,80}")
                ||selector.length()<2||selector.length()>340
                ||!expected.matches("[A-Za-z0-9.-]{3,253}")
                ||(proofUrl.length()<10&&proofText.trim().length()<6)
                ||!java.util.Arrays.asList("publish","schedule").contains(p.optString("mode","publish")))
                throw new Exception("Publication : ID, domaine, cible et résultat observable requis.");
        }
        if(action.equals("desktop")){
            String tool=p.optString("tool");if(!Arrays.asList("vision","pointer","files","browser","control","hearing","studio","tor").contains(tool))throw new Exception("Pas de tâche ou script imbriqué.");
            if(tool.equals("tor")&&!Arrays.asList("status","capabilities","open").contains(p.optString("operation")))throw new Exception("Tor : opération non autorisée");
            if(tool.equals("studio")&&!StudioMcp.OPERATIONS.contains(StudioMcp.operation(p)))throw new Exception("Opération Studio MCP inconnue");
            if(tool.equals("hearing")&&!Arrays.asList("status","capabilities","media","analyze","transcribe").contains(p.optString("operation")))throw new Exception("Écoute dans une tâche : utiliser analyse ou transcription, puis écouter séparément les extraits.");
            if(tool.equals("control")&&!Arrays.asList("observe","status","capabilities","act").contains(p.optString("operation")))throw new Exception("Opération de contrôle non autorisée dans une tâche.");
            if(tool.equals("browser")&&!Arrays.asList("upload_file","upload_status","get_form","fill_form","verify_form","get_page_status","read_pdf","click_verified","click_when_enabled","download_file","download_status").contains(p.optString("operation")))throw new Exception("Opération navigateur non autorisée dans une tâche.");
            if(tool.equals("vision")&&p.optBoolean("capture",false))throw new Exception("Utilise la vision structurée dans une tâche, puis capture séparément.");
        }
        if(Arrays.asList("type","select_option","check","click_verified","upload_file","wait_for_element").contains(action)){
            String selector=p.optString("selector","");
            if(selector.trim().isEmpty()||selector.length()>340)throw new Exception("Sélecteur CSS précis obligatoire pour "+action+".");
        }
        if(action.equals("click_verified")){
            if(p.optString("expected_selector","").isEmpty()&&p.optString("expected_text","").isEmpty()
                    &&p.optString("expected_url_contains","").isEmpty())
                throw new Exception("Le clic vérifié exige une condition de réussite observable.");
            if(p.optInt("timeout_ms",10000)>20000)throw new Exception("Vérification du clic limitée à 20 secondes.");
        }
        if(action.equals("type")&&p.optString("text","").length()>8000)
            throw new Exception("Saisie trop longue : maximum 8 000 caractères.");
        if(action.equals("upload_file")){
            String host=p.optString("expected_host","");
            JSONArray files=p.optJSONArray("files");
            if(!host.matches("[A-Za-z0-9.-]{3,253}")||host.startsWith(".")||host.endsWith("."))
                throw new Exception("Importation : domaine de destination attendu obligatoire.");
            if(files==null||files.length()<1||files.length()>8)
                throw new Exception("Importation : entre 1 et 8 fichiers autorisés sont requis.");
        }
    }
    private static boolean readOnly(String action,JSONObject p){return action.equals("desktop")&&p.optString("tool").equals("tor")&&Arrays.asList("status","capabilities").contains(p.optString("operation"))||action.equals("desktop")&&p.optString("tool").equals("studio")&&StudioMcp.readOnly(p)||action.equals("desktop")&&p.optString("tool").equals("hearing")||action.equals("desktop")&&p.optString("tool").equals("control")&&Arrays.asList("observe","status","capabilities").contains(p.optString("operation"))||action.equals("desktop")&&p.optString("tool").equals("browser")&&Arrays.asList("download_status","upload_status","get_form","verify_form","get_page_status","read_pdf").contains(p.optString("operation"))||action.equals("files_external")&&Arrays.asList("external_roots","external_list","external_read_text").contains(p.optString("operation"))||action.equals("keyboard")&&p.optString("operation").equals("inspect")||action.equals("desktop")&&(p.optString("tool").equals("vision")||p.optString("tool").equals("files")&&Arrays.asList("stat","status","external_list","external_read_text").contains(p.optString("operation")))||Arrays.asList("files_detect_format","files_copy_content","files_search_content","files_media_inspect","files_verify","files_archive_list","files_archive_read","files_database","files_stat","files_hash","document_read","document_page","media_frame","video_editor_capabilities","video_editor_queue_status","video_editor_status","video_editor_wait","video_editor_verify_output","video_editor_project_read","files_list","files_catalog","files_read_text","notes_list","notes_read","media_info","media_analyze_audio","media_audio_capabilities","media_transcribe","workspace_status","tabs","list_tabs","get_page_status","get_form","wait_for_element","read_page","publication_status","publication_list").contains(action);}
    static JSONObject command(Context c,JSONObject p)throws Exception{
        return command(c,p,false);
    }
    static JSONObject command(Context c,JSONObject p,boolean local2)throws Exception{
        return command(c,p,local2,false);
    }
    static JSONObject command(Context c,JSONObject p,boolean local2,boolean local3)throws Exception{
        String id=p.getString("id"),op=p.optString("operation","status");File file=AgentFiles.journal(c,"tasks",id);JSONObject state;
        if(op.equals("submit")){
            JSONArray steps=p.getJSONArray("steps");validate(steps);String fingerprint=AgentVision.digest(steps.toString());
            if(file.exists()){state=AgentFiles.read(file);if(!state.getString("fingerprint").equals(fingerprint))throw new Exception("Cet identifiant appartient à une autre tâche.");}
            else{JSONArray rows=new JSONArray();for(int i=0;i<steps.length();i++)rows.put(new JSONObject(steps.getJSONObject(i).toString()).put("state","pending"));state=new JSONObject().put("id",id).put("fingerprint",fingerprint).put("state","ready").put("steps",rows).put("created",System.currentTimeMillis());AgentFiles.save(file,state);}
            if(state.getString("state").equals("ready"))start(c,file,state,local2,local3);
        }else{
            state=AgentFiles.read(file);
            if(!running.contains(id)&&state.optString("state").equals("running")){
                for(int i=0;i<state.getJSONArray("steps").length();i++){JSONObject step=state.getJSONArray("steps").getJSONObject(i);if(step.optString("state").equals("effect_running"))step.put("state","uncertain");}
                state.put("state","paused");AgentFiles.save(file,state);
            }
            if(op.equals("cancel")){active.put(id,false);state.put("state","cancelled");AgentFiles.save(file,state);}
            else if(op.equals("resume")){
                if(running.contains(id)||state.optString("state").equals("completed"))return summary(state,p.optInt("offset",0));
                if(state.getString("state").equals("cancelled"))throw new Exception("Tâche annulée ; utilise un nouvel identifiant.");
                JSONArray rows=state.getJSONArray("steps");for(int i=0;i<rows.length();i++){JSONObject step=rows.getJSONObject(i);String status=step.optString("state");
                    if(status.equals("failed")&&p.optBoolean("retry_failed",false))step.put("state","pending");
                    if(status.equals("uncertain")&&step.has("verify"))step.put("state","effect_done");
                    else if(status.equals("uncertain")&&p.optBoolean("retry_uncertain",false)
                            &&readOnly(step.optString("action"),step.optJSONObject("args")))step.put("state","pending");
                    // Never replay an uncertain write, even with retry_uncertain=true.
                    // Its postcondition must first be observed by a read-only verify step.
                }AgentFiles.save(file,state);start(c,file,state,local2,local3);
            }else if(!op.equals("status"))throw new Exception("Opération de tâche inconnue.");
        }
        return summary(state,p.optInt("offset",0));
    }
    private static void start(Context c,File file,JSONObject state,boolean local2,boolean local3)throws Exception{
        if(!(local3?Local3Client.get(c).enabled():local2?Local2Client.get(c).enabled():AgentClient.get(c).isEnabled())||!(local3?Local3Client.get(c).autonomousAllowed():AgentClient.get(c).isAutonomous()))throw new Exception("Active le canal MCP demandé et le mode autonome pour une tâche à étapes.");
        String id=state.getString("id");if(!running.add(id))return;active.put(id,true);state.put("state","running").put("transport",local3?"local3":local2?"local2":"render");AgentFiles.save(file,state);WorkspaceCommands.IO.execute(()->new Runner(c,file,id,local2,local3).next());
    }
    private static JSONObject summary(JSONObject state,int offset)throws Exception{
        JSONArray rows=state.getJSONArray("steps"),page=new JSONArray();if(offset<0||offset>rows.length())throw new Exception("Pagination invalide.");int done=0;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optString("state").equals("done"))done++;if(i>=offset&&i<offset+10){JSONObject shown=new JSONObject().put("id",row.getString("id")).put("state",row.getString("state"));if(row.has("error"))shown.put("error",row.get("error"));if(row.has("result")){String result=row.get("result").toString();shown.put("result_excerpt",result.length()>900?result.substring(0,900):result).put("result_truncated",result.length()>900);}page.put(shown);}}
        return new JSONObject().put("ok",!state.optString("state").equals("failed")).put("id",state.getString("id")).put("state",state.getString("state")).put("completed",done).put("total",rows.length()).put("steps",page).put("next_offset",offset+10<rows.length()?offset+10:-1);
    }
    private static Object at(Object current,String path)throws Exception{
        for(String part:path.split("\\.")){if(current instanceof JSONObject)current=((JSONObject)current).get(part);else if(current instanceof JSONArray)current=((JSONArray)current).get(Integer.parseInt(part));else throw new Exception("Référence de résultat invalide : "+path);}return current;
    }
    private static Object fullResult(Context app,Object value)throws Exception{
        if(!(value instanceof JSONObject)||!((JSONObject)value).optBoolean("result_in_file"))return value;
        JSONObject reference=(JSONObject)value;String path=reference.getString("result_file");
        if(!path.startsWith("AgentResults/Local3-"))throw new Exception("Référence de résultat non reconnue.");
        File source=new WorkspaceStore(app).file(path);
        if(source.length()>2000000||!WorkspaceTransfer.hash(source).equals(reference.getString("sha256")))throw new Exception("Résultat externe trop grand ou modifié.");
        try(java.io.InputStream in=new java.io.FileInputStream(source);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>2000000)throw new Exception("Résultat trop grand.");out.write(buffer,0,n);}
            return new org.json.JSONTokener(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).nextValue();
        }
    }
    private static Object resolve(Context app,Object value,JSONObject state)throws Exception{
        if(value instanceof String&&((String)value).startsWith("$")){
            String[] ref=((String)value).substring(1).split("\\.",2);JSONArray steps=state.getJSONArray("steps");
            for(int i=0;i<steps.length();i++){JSONObject row=steps.getJSONObject(i);if(row.getString("id").equals(ref[0])&&(row.optString("state").equals("done")||row.optString("state").equals("effect_done")||row.optString("state").equals("verify_failed"))&&row.has("result")){Object result=fullResult(app,row.get("result"));return ref.length==1?result:at(result,ref[1]);}}
            throw new Exception("Étape référencée non confirmée : "+ref[0]);
        }
        if(value instanceof JSONObject){JSONObject out=new JSONObject();Iterator<String> keys=((JSONObject)value).keys();while(keys.hasNext()){String key=keys.next();out.put(key,resolve(app,((JSONObject)value).get(key),state));}return out;}
        if(value instanceof JSONArray){JSONArray out=new JSONArray();for(int i=0;i<((JSONArray)value).length();i++)out.put(resolve(app,((JSONArray)value).get(i),state));return out;}
        return value;
    }
    private static final class Runner {
        final Context app;final File file;final String id;final boolean local2,local3;final int epoch;
        Runner(Context c,File f,String id,boolean local2,boolean local3){app=c.getApplicationContext();file=f;this.id=id;this.local2=local2;this.local3=local3;epoch=local3?Local3Client.get(app).sessionGeneration():Local2Client.get(app).sessionGeneration();}
        boolean allowed(){try{return (local3?Local3Client.get(app).enabled()&&epoch==Local3Client.get(app).sessionGeneration():local2?Local2Client.get(app).enabled()&&epoch==Local2Client.get(app).sessionGeneration():AgentClient.get(app).isEnabled())&&(local3?Local3Client.get(app).autonomousAllowed():AgentClient.get(app).isAutonomous())&&Boolean.TRUE.equals(active.get(id));}catch(Exception e){return false;}}
        void stop(JSONObject state,String reason){try{if(!state.optString("state").equals("cancelled"))state.put("state","paused").put("error",reason);AgentFiles.save(file,state);}catch(Exception ignored){}running.remove(id);}
        void next(){
            JSONObject state=null;try{
                state=AgentFiles.read(file);if(!allowed()){stop(state,"MCP arrêté, mode autonome désactivé ou tâche annulée.");return;}
                JSONArray rows=state.getJSONArray("steps");int index=0;while(index<rows.length()&&rows.getJSONObject(index).optString("state").equals("done"))index++;
                if(index==rows.length()){state.put("state","completed");AgentFiles.save(file,state);running.remove(id);return;}
                JSONObject row=rows.getJSONObject(index);String status=row.optString("state");if(status.equals("uncertain")||status.equals("failed")||status.equals("effect_running")){stop(state,"Étape non confirmée : inspecte son résultat avant de reprendre.");return;}
                boolean verify=status.equals("effect_done")||status.equals("verify_failed");
                if(verify&&!row.has("verify")){row.put("state","done");AgentFiles.save(file,state);WorkspaceCommands.IO.execute(this::next);return;}
                JSONObject source=verify?row.getJSONObject("verify"):row;JSONObject args=(JSONObject)resolve(app,source.getJSONObject("args"),state);
                if(!verify){row.put("state","effect_running");AgentFiles.save(file,state);}
                final int stepIndex=index;final boolean verifying=verify;
                JSONObject command=new JSONObject().put("action",source.getString("action")).put("args",args);
                java.util.concurrent.atomic.AtomicBoolean received=new java.util.concurrent.atomic.AtomicBoolean();
                AgentClient.ResultCallback callback=new AgentClient.ResultCallback(){public boolean isLocal2(){return local2;}public boolean isLocal3(){return local3;}public boolean isActive(){return !received.get()&&allowed();}public void finish(boolean ok,String text){if(!received.compareAndSet(false,true))return;WorkspaceCommands.IO.execute(()->receipt(stepIndex,verifying,ok,text));}};
                WorkspaceCommands.UI.post(()->{if(callback.isActive())AgentClient.get(app).executeTaskStep(command,callback);else WorkspaceCommands.IO.execute(this::next);});
                long timeout=source.optString("action").equals("video_editor_wait")?125000L:verifying?120000L:"upload_file".equals(source.optString("action"))?180000L:
                    "wait_for_element".equals(source.optString("action"))?35000L:(TransferLimits.longOperation(source.optString("action"))||source.optString("action").equals("video_editor_subtitles_generate"))?900000L:120000L;
                WorkspaceCommands.UI.postDelayed(()->{if(received.compareAndSet(false,true))WorkspaceCommands.IO.execute(()->receipt(stepIndex,verifying,false,"Délai dépassé ("+timeout+" ms) : résultat non confirmé, aucune répétition automatique."));},timeout);
            }catch(Exception e){if(state!=null)stop(state,e.getMessage());else running.remove(id);}
        }
        void receipt(int index,boolean verify,boolean ok,String text){
            JSONObject state=null;try{
                state=AgentFiles.read(file);if(state.optString("state").equals("cancelled")){running.remove(id);return;}
                JSONObject row=state.getJSONArray("steps").getJSONObject(index);Object result;
                try{result=new org.json.JSONTokener(text).nextValue();}catch(Exception e){result=new JSONObject().put("message",text==null?"":text);}
                ok=ok&&(!(result instanceof JSONObject)||((JSONObject)result).optBoolean("ok",true));
                if(verify&&ok&&result instanceof JSONObject&&((JSONObject)result).optString("state").equals("downloading")
                    &&row.getJSONObject("verify").getJSONObject("args").optString("operation").equals("download_status")){
                    long began=row.optLong("verification_started",System.currentTimeMillis());row.put("verification_started",began);
                    if(System.currentTimeMillis()-began<7200000L){row.put("state","effect_done");AgentFiles.save(file,state);WorkspaceCommands.UI.postDelayed(()->WorkspaceCommands.IO.execute(this::next),2000L);return;}
                }
                if(verify&&ok){JSONObject rule=row.getJSONObject("verify"),expect=rule.optJSONObject("expect"),contains=rule.optJSONObject("contains");
                    if(expect!=null){Iterator<String> keys=expect.keys();while(keys.hasNext()){String key=keys.next();Object actual=at(result,key),wanted=resolve(app,expect.get(key),state);if(!String.valueOf(actual).equals(String.valueOf(wanted)))ok=false;}}
                    if(contains!=null){Iterator<String> keys=contains.keys();while(keys.hasNext()){String key=keys.next();if(!String.valueOf(at(result,key)).contains(String.valueOf(resolve(app,contains.get(key),state))))ok=false;}}
                }
                if(ok&&!verify&&result instanceof JSONObject&&((JSONObject)result).optString("state").equals("action_sent")&&!row.has("verify")){
                    row.put("state","uncertain").put("result",result).put("error","Geste envoyé sans postcondition : ajouter une vérification indépendante.");
                    AgentFiles.save(file,state);stop(state,"Action envoyée, résultat non vérifié : aucune répétition automatique.");return;
                }
                if(ok){if(!verify){if(result.toString().getBytes("UTF-8").length>26000)result=new JSONObject(Local3Client.compactWorkflowResult(app,result.toString()));row.put("result",result);}row.put("state",verify?"done":"effect_done").remove("error");AgentFiles.save(file,state);WorkspaceCommands.IO.execute(this::next);}
                else {row.put("state",verify?"verify_failed":"uncertain").put("error",text==null?"Résultat inattendu.":text.substring(0,Math.min(text.length(),500)));AgentFiles.save(file,state);stop(state,verify?"Vérification échouée : l’action déjà exécutée ne sera pas répétée.":"Action non confirmée : aucune répétition automatique.");}
            }catch(Exception e){if(state!=null)stop(state,e.getMessage());else running.remove(id);}
        }
    }
}
