package com.chk.agentbrowser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import org.json.*;

/** Local 2 and the editor share projects. Queue snapshots do not switch the visible project. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioAutomation {
    private static final java.util.concurrent.ExecutorService IO=VideoEditorEngine.DISK;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static JSONObject queue;
    private static boolean polling;
    private static volatile boolean pending;
    static boolean pendingExports(){return pending;}
    static boolean handles(String action){return action.equals("video_editor_capabilities")||action.equals("video_editor_project_import")
        ||action.equals("video_editor_timeline")||action.equals("video_editor_history")||action.startsWith("video_editor_queue_");}
    static void command(Context app,String action,JSONObject args,VideoEditorEngine.Done cb){IO.execute(()->{
        try{
            JSONObject result;
            if(action.equals("video_editor_capabilities"))result=capabilities();
            else if(action.equals("video_editor_project_import")){
                requireIdle();JSONObject p=args.has("path")?readProject(app,args.getString("path")):new JSONObject(args.getJSONObject("project").toString());
                VideoEditorEngine.get().validate(app,p);result=new JSONObject().put("project",StudioProjectLibrary.create(app,p)).put("saved",true);
            }else if(action.equals("video_editor_timeline")){
                requireIdle();JSONObject p=VideoEditorEngine.get().load(app);
                if(!args.has("expected_revision")||args.getInt("expected_revision")!=p.optInt("revision"))throw new IOException("Révision requise : relire le projet avant de modifier la timeline");
                JSONArray operations=args.getJSONArray("operations");if(operations.length()<1||operations.length()>100)throw new IOException("1 à 100 opérations requises");
                for(int i=0;i<operations.length();i++)edit(p,operations.getJSONObject(i));
                VideoEditorEngine.get().validate(app,p);p.put("revision",p.optInt("revision")+1);StudioProjectLibrary.save(app,p);
                result=new JSONObject().put("project",p).put("saved",true).put("applied",operations.length());
            }else if(action.equals("video_editor_history")){
                requireIdle();JSONObject p=VideoEditorEngine.get().load(app);
                if(args.getInt("expected_revision")!=p.optInt("revision"))throw new IOException("Révision périmée");
                result=new JSONObject().put("project",StudioProjectLibrary.history(app,args.optBoolean("redo",false)));
            }else{
                loadQueue(app);
                if(action.equals("video_editor_queue_add")){
                    String request=args.getString("request_id");if(!request.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("request_id invalide");
                    JSONArray jobs=queue.getJSONArray("jobs");
                    for(int i=0;i<jobs.length();i++)if(request.equals(jobs.getJSONObject(i).optString("request_id"))){cb.completed(true,summary().toString());return;}
                    JSONArray projects=args.getJSONArray("projects");if(projects.length()<1||projects.length()>10||jobs.length()+projects.length()>40)throw new IOException("1 à 10 projets par ajout, 40 exports conservés maximum");
                    JSONArray staged=new JSONArray();Set<String> outputs=new HashSet<>();
                    for(int i=0;i<jobs.length();i++)outputs.add(jobs.getJSONObject(i).getJSONObject("project").getString("output"));
                    for(int i=0;i<projects.length();i++){
                        JSONObject spec=projects.getJSONObject(i),p=spec.has("path")?readProject(app,spec.getString("path")):new JSONObject(spec.getJSONObject("project").toString());
                        VideoEditorEngine.get().validate(app,p);if(p.getJSONArray("clips").length()==0)throw new IOException("Projet vide");
                        String out=p.getString("output");if(!outputs.add(out)||new WorkspaceStore(app).file(out).exists())throw new IOException("Destination déjà utilisée : "+out);
                        staged.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("request_id",request).put("project",p)
                            .put("sources",fingerprints(app,p)).put("state","queued").put("created_at",System.currentTimeMillis()));
                    }
                    for(int i=0;i<staged.length();i++)jobs.put(staged.get(i));queue.put("paused",false);saveQueue(app);pump(app);result=summary();
                }else if(action.equals("video_editor_queue_resume")){
                    for(int i=0;i<queue.getJSONArray("jobs").length();i++){JSONObject job=queue.getJSONArray("jobs").getJSONObject(i);
                        if(job.optString("state").equals("interrupted")){
                            JSONObject verified=VideoEditorEngine.get().verifyProjectOutput(app,job.getJSONObject("project"));
                            if(verified.optBoolean("valid"))job.put("state","completed").put("verification",verified);
                            else if(!new WorkspaceStore(app).file(job.getJSONObject("project").getString("output")).exists())job.put("state","queued");
                            else job.put("state","failed").put("error","Sortie existante non validée ; choisir une autre destination");
                        }
                    }
                    queue.put("paused",false);saveQueue(app);pump(app);result=summary();
                }else if(action.equals("video_editor_queue_pause")){queue.put("paused",true);saveQueue(app);result=summary();}
                else if(action.equals("video_editor_queue_status")){pump(app);result=summary();}
                else throw new IOException("Commande de file inconnue");
            }
            cb.completed(true,result.toString());
        }catch(Exception ex){cb.completed(false,ex.getMessage());}
    });}
    private static void requireIdle()throws IOException{String state=VideoEditorEngine.get().status().optString("state");if(state.equals("running")||state.equals("preparing"))throw new IOException("Export actif");}
    static JSONObject readProject(Context app,String path)throws Exception{
        WorkspaceStore store=new WorkspaceStore(app);JSONObject p=read(store.file(path));
        String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')+1):"";
        for(String track:new String[]{"clips","audio","layers"}){JSONArray items=p.optJSONArray(track);if(items!=null)for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);String source=item.getString("path");
            if(!store.file(source).isFile()&&!parent.isEmpty())item.put("path",parent+source);
        }}
        if(!p.optString("output").contains("/"))p.put("output",parent+p.optString("output"));return p;
    }
    private static void edit(JSONObject p,JSONObject op)throws Exception{
        String type=op.getString("op");
        if(type.equals("settings")){
            JSONObject values=op.getJSONObject("values");for(Iterator<String> it=values.keys();it.hasNext();){String key=it.next();
                if(!Arrays.asList("name","output","aspect_ratio","aspect_mode","resolution","fps","mute_original").contains(key))throw new IOException("Réglage inconnu : "+key);p.put(key,values.get(key));}return;
        }
        String track=op.optString("track","clips");if(!track.equals("clips")&&!track.equals("audio")&&!track.equals("layers"))throw new IOException("Piste inconnue");
        JSONArray items=p.optJSONArray(track);if(items==null){items=new JSONArray();p.put(track,items);}
        int index=op.optInt("index",items.length());
        if(type.equals("add")){if(index<0||index>items.length())throw new IOException("Index invalide");JSONArray out=new JSONArray();for(int i=0;i<=items.length();i++){if(i==index)out.put(new JSONObject(op.getJSONObject("clip").toString()));if(i<items.length())out.put(items.get(i));}p.put(track,out);}
        else{
            if(index<0||index>=items.length())throw new IOException("Plan absent");
            if(type.equals("update")){JSONObject c=items.getJSONObject(index),values=op.getJSONObject("values");for(Iterator<String> it=values.keys();it.hasNext();){String key=it.next();
                if(!(track.equals("layers")?Arrays.asList("path","start_ms","duration_ms","offset_ms","x","y","scale","opacity"):Arrays.asList("path","start_ms","duration_ms","volume","speed","mute","filter","fade_ms","rotation","text","grade_exposure","grade_exposure_end","grade_warmth","grade_warmth_end","grade_saturation","grade_saturation_end","zoom","zoom_end","pan_x","pan_x_end","pan_y","pan_y_end")).contains(key))throw new IOException("Paramètre inconnu : "+key);c.put(key,values.get(key));}}
            else if(type.equals("delete"))items.remove(index);
            else if(type.equals("move")){int to=op.getInt("to");if(to<0||to>=items.length())throw new IOException("Destination invalide");Object moved=items.remove(index);JSONArray out=new JSONArray();for(int i=0;i<=items.length();i++){if(i==to)out.put(moved);if(i<items.length())out.put(items.get(i));}p.put(track,out);}
            else throw new IOException("Opération inconnue : "+type);
        }
    }
    private static JSONObject fingerprints(Context app,JSONObject p)throws Exception{
        JSONObject out=new JSONObject();WorkspaceStore store=new WorkspaceStore(app);
        for(String track:new String[]{"clips","audio","layers"}){JSONArray items=p.optJSONArray(track);if(items!=null)for(int i=0;i<items.length();i++){String path=items.getJSONObject(i).getString("path");if(!out.has(path))out.put(path,WorkspaceTransfer.hash(store.file(path)));}}return out;
    }
    private static File queueFile(Context app){return new File(app.getFilesDir(),"studio_export_queue.json");}
    private static void loadQueue(Context app)throws Exception{
        if(queue!=null)return;File file=queueFile(app);
        queue=(file.exists()||new File(file+".bak").exists())?read(file):new JSONObject().put("paused",true).put("jobs",new JSONArray());
        for(int i=0;i<queue.getJSONArray("jobs").length();i++){JSONObject job=queue.getJSONArray("jobs").getJSONObject(i);if(job.optString("state").equals("running"))job.put("state","interrupted");}
        queue.put("paused",true);saveQueue(app);
    }
    private static void saveQueue(Context app)throws Exception{write(queueFile(app),queue);pending=false;if(!queue.optBoolean("paused",true)){JSONArray jobs=queue.getJSONArray("jobs");for(int i=0;i<jobs.length();i++)if(Arrays.asList("queued","running").contains(jobs.getJSONObject(i).optString("state")))pending=true;}}
    private static void pump(Context app)throws Exception{
        if(polling||queue.optBoolean("paused",true))return;
        String engine=VideoEditorEngine.get().status().optString("state");if(engine.equals("running")||engine.equals("preparing")){MAIN.postDelayed(()->IO.execute(()->{try{pump(app);}catch(Exception ex){android.util.Log.e("ChkStudioQueue","Queue retry",ex);}}),1500);return;}
        JSONArray jobs=queue.getJSONArray("jobs");
        for(int i=0;i<jobs.length();i++){
            JSONObject job=jobs.getJSONObject(i);if(!job.optString("state").equals("queued"))continue;
            try{JSONObject actual=fingerprints(app,job.getJSONObject("project")),expected=job.getJSONObject("sources");for(Iterator<String> keys=expected.keys();keys.hasNext();){String key=keys.next();if(!expected.getString(key).equals(actual.optString(key)))throw new IOException("Un média source a changé : "+key);}}
            catch(Exception ex){job.put("state","failed").put("error",ex.getMessage());saveQueue(app);continue;}
            polling=true;job.put("state","running");saveQueue(app);
            VideoEditorEngine.get().command(app,"video_editor_export",new JSONObject().put("project",job.getJSONObject("project")),(ok,value)->IO.execute(()->{
                try{if(!ok){job.put("state","failed").put("error",value);polling=false;saveQueue(app);pump(app);}else poll(app,job);}catch(Exception ex){polling=false;android.util.Log.e("ChkStudioQueue","Export queue",ex);}
            }));return;
        }
    }
    private static void poll(Context app,JSONObject job){MAIN.postDelayed(()->IO.execute(()->{
        try{JSONObject status=VideoEditorEngine.get().status();String state=status.optString("state");
            if(state.equals("running")||state.equals("preparing")){poll(app,job);return;}
            if(state.equals("completed")){
                JSONObject check=VideoEditorEngine.get().verifyProjectOutput(app,job.getJSONObject("project"));
                job.put("verification",check).put("state",check.optBoolean("valid")?"completed":"failed");
            }else job.put("state","failed").put("error",status.optString("error",state));
            job.put("finished_at",System.currentTimeMillis());polling=false;saveQueue(app);pump(app);
        }catch(Exception ex){try{job.put("state","failed").put("error",ex.getMessage());saveQueue(app);}catch(Exception ignored){}polling=false;}
    }),1000);}
    private static JSONObject summary()throws Exception{
        JSONArray rows=new JSONArray();JSONArray jobs=queue.getJSONArray("jobs");for(int i=0;i<jobs.length();i++){JSONObject j=jobs.getJSONObject(i),p=j.getJSONObject("project");
            JSONObject row=new JSONObject(j.toString());row.remove("project");row.remove("sources");row.put("name",p.optString("name")).put("output",p.getString("output"));rows.put(row);}
        return new JSONObject().put("paused",queue.optBoolean("paused")).put("jobs",rows).put("export",VideoEditorEngine.get().status());
    }
    static JSONObject capabilities()throws Exception{return new JSONObject().put("studio_protocol",2).put("engine","android").put("generative_video",false)
        .put("image_formats",new JSONArray("[\"png\",\"jpg\",\"jpeg\",\"webp\"]")).put("image_duration_ms_min",1000).put("image_duration_ms_max",60000).put("max_clips",100)
        .put("timeline_operations",new JSONArray("[\"add\",\"update\",\"move\",\"delete\",\"settings\"]"))
        .put("subtitle_generation",new JSONObject().put("action","video_editor_subtitles_generate via android_action").put("expected_revision","required").put("languages","fr,en").put("timing","Vosk word timestamps").put("audio_uploaded",false))
        .put("tracks",new JSONArray("[\"clips\",\"audio\",\"layers\",\"subtitles\"]")).put("audio_layout","sequential").put("video_layout","sequential + 3 timed image/video PiP layers").put("max_layers",3).put("layer_parameters",new JSONObject().put("offset_ms","position dans le montage").put("scale","0.1..1, largeur relative").put("x / y","-1..1, centre=0").put("opacity","0..1").put("audio","muet"))
        .put("queue_actions",new JSONArray("[\"add\",\"status\",\"pause\",\"resume\"]"))
        .put("timeline_request",new JSONObject()
            .put("expected_revision","integer obligatoire, obtenu par video_editor_project_read")
            .put("operations",new JSONArray()
                .put(new JSONObject().put("op","add").put("track","clips, audio ou layers").put("index","entier facultatif ; insertion, sinon fin").put("clip",new JSONObject().put("path","chemin Android CHK").put("start_ms",0).put("duration_ms",8000)))
                .put(new JSONObject().put("op","update").put("track","clips, audio ou layers").put("index",0).put("values",new JSONObject().put("duration_ms",8000).put("filter","cinema").put("zoom",1).put("zoom_end",1.12)))
                .put(new JSONObject().put("op","move").put("track","clips, audio ou layers").put("index",0).put("to",1))
                .put(new JSONObject().put("op","delete").put("track","clips, audio ou layers").put("index",0))
                .put(new JSONObject().put("op","settings").put("values",new JSONObject().put("aspect_ratio","16:9").put("aspect_mode","fit").put("resolution",1080).put("fps",30).put("mute_original",true)))))
        .put("clip_parameters",new JSONObject().put("path","fichier Android existant").put("start_ms","entier >=0 ; 0 pour photo").put("duration_ms","photo 1000..60000 ; vidéo 500..1200000")
            .put("filter","aucun,noir,vintage,chaud,froid,cinema,contraste,nuit,vibrant,doux,desature,sepia")
            .put("fade_ms","0..min(1500,duration/3), passage par le noir")
            .put("volume","0..1 · vidéo et audio, aperçu et export").put("speed","0.25..4").put("rotation","-180..180").put("mute","boolean").put("text","500 caractères maximum")
            .put("grade_exposure / grade_exposure_end","-0.5..0.5").put("grade_warmth / grade_warmth_end","-0.5..0.5").put("grade_saturation / grade_saturation_end","0..2")
            .put("zoom / zoom_end","1..4").put("pan_x / pan_x_end / pan_y / pan_y_end","-1..1"))
        .put("queue_request",new JSONObject().put("request_id","identifiant stable, lettres/chiffres/tirets/soulignés, 1..80 caractères").put("projects",new JSONArray().put(new JSONObject().put("path","dossier/montage.json")).put(new JSONObject().put("project","objet projet complet, alternative à path"))))
        .put("verification","completed et verification.valid=true ; media_frame(path,time_ms) pour voir les images du MP4")
        .put("preview","mode source ; aperçu rendu 480p à la demande dans Studio, même composition que l’export");}
    static JSONObject read(File file)throws Exception{
        try(FileInputStream in=new AtomicFile(file).openRead()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>6000000)throw new IOException("Fichier Studio trop volumineux");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}
    }
    static void write(File file,JSONObject json)throws Exception{
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>6000000)throw new IOException("État Studio trop volumineux");AtomicFile safe=new AtomicFile(file);FileOutputStream out=null;try{out=safe.startWrite();out.write(bytes);safe.finishWrite(out);}catch(Exception ex){if(out!=null)safe.failWrite(out);throw ex;}
    }
}
