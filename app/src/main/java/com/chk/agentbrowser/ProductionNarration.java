package com.chk.agentbrowser;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/** Real French speech synthesized by the installed Android engine, with durable per-segment timings. */
final class ProductionNarration {
    private static volatile Job current;
    private ProductionNarration(){}
    interface EngineReady {void ready(TextToSpeech engine)throws Exception;}
    static void engine(Context app,EngineReady ready,AgentClient.ResultCallback callback){
        WorkspaceCommands.UI.post(()->{
            final TextToSpeech[] engine=new TextToSpeech[1];
            engine[0]=new TextToSpeech(app,status->WorkspaceCommands.UI.post(()->{
                if(!callback.isActive()){engine[0].shutdown();return;}
                try{
                    if(status!=TextToSpeech.SUCCESS)throw new IOException("Moteur de voix Android indisponible");
                    ready.ready(engine[0]);
                }catch(Exception error){engine[0].shutdown();callback.finish(false,error.getMessage());}
            }));
        });
    }
    static void voices(Context app,AgentClient.ResultCallback callback){
        engine(app,tts->{
            JSONArray list=new JSONArray();Set<Voice> voices=tts.getVoices();
            if(voices!=null)for(Voice voice:voices)if("fr".equals(voice.getLocale().getLanguage()))
                list.put(new JSONObject().put("id",voice.getName()).put("locale",voice.getLocale().toLanguageTag())
                    .put("quality",voice.getQuality()).put("requires_network",voice.isNetworkConnectionRequired())
                    .put("features",voice.getFeatures()==null?new JSONArray():new JSONArray(voice.getFeatures())));
            String name=tts.getDefaultEngine();tts.shutdown();
            callback.finish(true,new JSONObject().put("engine",name).put("provider","android_text_to_speech")
                .put("voices",list).put("ready",list.length()>0).toString());
        },callback);
    }
    static String folder(JSONObject args)throws Exception {
        return ProductionProject.root(args)+"/Narrations/"+ProductionProject.id(args);
    }
    static JSONObject status(Context app,JSONObject args)throws Exception {
        JSONObject job=AgentFiles.read(new WorkspaceStore(app).file(folder(args)+"/job.json"));
        if("running".equals(job.optString("state"))&&(current==null||!folder(args).equals(current.folder)))
            job.put("state","interrupted").put("resume_operation","narration_resume");
        return job;
    }
    static void start(Context app,JSONObject args,AgentClient.ResultCallback callback){
        WorkspaceCommands.IO.execute(()->{
            try{
                String project=ProductionProject.root(args),folder=folder(args);
                ProductionProject.read(app,project);
                WorkspaceStore store=new WorkspaceStore(app);File receipt=store.file(folder+"/job.json");
                JSONObject job;
                String fingerprint=AgentVision.digest(StudioMcp.canonical(args));
                if(receipt.isFile()){
                    job=AgentFiles.read(receipt);
                    if(!fingerprint.equals(job.getString("fingerprint")))throw new IOException("request_id déjà utilisé pour une autre narration");
                    if("completed".equals(job.optString("state"))){callback.finish(true,job.toString());return;}
                }else{
                    JSONArray input=segments(args);
                    ProductionProject.mkdir(store,folder);
                    job=new JSONObject().put("request_id",ProductionProject.id(args)).put("project_path",project)
                        .put("fingerprint",fingerprint).put("args",args).put("input",input).put("segments",new JSONArray())
                        .put("created_at",System.currentTimeMillis());
                }
                final JSONObject data=job;
                WorkspaceCommands.UI.post(()->{
                    try{
                        if(current!=null){
                            if(current.folder.equals(folder)){callback.finish(true,current.data.toString());return;}
                            throw new IOException("Une narration est déjà en cours");
                        }
                        Job next=new Job(app.getApplicationContext(),args,data,folder);
                        current=next;next.data.put("state","running");next.save();
                        WorkspaceCommands.UI.postDelayed(next.timeout,30000);
                        engine(app,tts->{next.tts=tts;next.configure();next.next();},new AgentClient.ResultCallback(){
                            public boolean isActive(){return current==next&&!next.ended;}
                            public void finish(boolean ok,String message){if(!ok)next.fail(message);}
                        });
                        callback.finish(true,next.data.toString());
                    }catch(Exception error){callback.finish(false,error.getMessage());}
                });
            }catch(Exception error){callback.finish(false,error.getMessage());}
        });
    }
    static JSONArray segments(JSONObject args)throws Exception {
        JSONArray input=args.optJSONArray("segments");
        if(input==null){
            input=new JSONArray();String text=args.getString("text").trim();
            for(String paragraph:text.split("\\n\\s*\\n")){
                String line="";
                for(String word:paragraph.trim().split("\\s+")){
                    if(!line.isEmpty()&&line.length()+word.length()+1>110){input.put(new JSONObject().put("text",line).put("pause_ms",80));line="";}
                    line=line.isEmpty()?word:line+" "+word;
                }
                if(!line.isEmpty())input.put(new JSONObject().put("text",line));
            }
        }
        if(input.length()<1||input.length()>80)throw new IOException("1 à 80 segments de narration requis");
        int total=0;
        for(int i=0;i<input.length();i++){
            JSONObject segment=input.getJSONObject(i);String text=segment.getString("text").trim();
            if(text.isEmpty()||text.length()>3000)throw new IOException("Un segment contient entre 1 et 3000 caractères");
            total+=text.length();
            if(segment.optLong("pause_ms",300)<0||segment.optLong("pause_ms",300)>10000)throw new IOException("Pause entre 0 et 10000 ms");
        }
        if(total>40000)throw new IOException("Narration limitée à 40000 caractères");
        for(String key:new String[]{"rate","pitch"}){
            double value=args.optDouble(key,1);
            if(!Double.isFinite(value)||value<.5||value>2)throw new IOException(key+" entre 0,5 et 2 requis");
        }
        return new JSONArray(input.toString());
    }
    static void resume(Context app,JSONObject args,AgentClient.ResultCallback callback){
        try{start(app,status(app,args).getJSONObject("args"),callback);}
        catch(Exception error){callback.finish(false,error.getMessage());}
    }
    static void cancel(Context app,JSONObject args,AgentClient.ResultCallback callback){
        WorkspaceCommands.UI.post(()->{
            try{
                if(current!=null&&current.folder.equals(folder(args)))current.fail("Annulé par l'agent");
                callback.finish(true,status(app,args).toString());
            }catch(Exception error){callback.finish(false,error.getMessage());}
        });
    }
    private static final class Job {
        final Context app;final JSONObject args,data;final String folder;
        TextToSpeech tts;volatile boolean ended;File partial,output;String utterance;long cursor;int index;
        final Runnable timeout=()->fail("Le moteur vocal ne répond plus ; la narration peut être reprise");
        Job(Context app,JSONObject args,JSONObject data,String folder){this.app=app;this.args=args;this.data=data;this.folder=folder;}
        void save()throws Exception {
            data.put("updated_at",System.currentTimeMillis());
            StudioAutomation.write(new WorkspaceStore(app).file(folder+"/job.json"),data);
        }
        void configure()throws Exception {
            if(tts.setLanguage(Locale.FRANCE)<TextToSpeech.LANG_AVAILABLE)throw new IOException("Installe une voix française dans les réglages vocaux Android");
            String requested=args.optString("voice","");
            if(!requested.isEmpty()){
                Voice chosen=null;Set<Voice> voices=tts.getVoices();
                if(voices!=null)for(Voice voice:voices)if(requested.equals(voice.getName())&&"fr".equals(voice.getLocale().getLanguage()))chosen=voice;
                if(chosen==null||tts.setVoice(chosen)!=TextToSpeech.SUCCESS)throw new IOException("Voix française indisponible : "+requested);
            }
            Voice voice=tts.getVoice();
            if(voice==null)throw new IOException("Aucune voix française prête");
            if(voice.isNetworkConnectionRequired()&&!args.optBoolean("allow_network",true))throw new IOException("Cette voix demande Internet ; choisis une voix hors ligne");
            tts.setSpeechRate((float)args.optDouble("rate",1));tts.setPitch((float)args.optDouble("pitch",1));
            data.put("engine",tts.getDefaultEngine()).put("voice",voice.getName())
                .put("requires_network",voice.isNetworkConnectionRequired()).put("provider","android_text_to_speech");
            JSONArray completed=data.getJSONArray("segments");index=completed.length();cursor=0;
            for(int i=0;i<completed.length();i++){
                JSONObject item=completed.getJSONObject(i);File source=new WorkspaceStore(app).file(item.getString("path"));
                if(!source.isFile()||!item.getString("sha256").equals(WorkspaceTransfer.hash(source)))throw new IOException("Un segment sauvegardé a été modifié");
                cursor=item.getLong("end_ms")+item.optLong("pause_ms",300);
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                public void onStart(String id){}
                public void onDone(String id){WorkspaceCommands.UI.post(()->{if(!ended&&id.equals(utterance))verify();});}
                public void onError(String id){onError(id,TextToSpeech.ERROR_SYNTHESIS);}
                public void onError(String id,int code){WorkspaceCommands.UI.post(()->{if(!ended&&id.equals(utterance))fail("Synthèse vocale interrompue : "+code);});}
            });
            save();
        }
        void next(){
            if(ended)return;
            try{
                JSONArray input=data.getJSONArray("input");
                if(index==input.length()){
                    data.put("state","completed").put("duration_ms",cursor).put("srt_path",folder+"/narration.srt");
                    StringBuilder srt=new StringBuilder();JSONArray segments=data.getJSONArray("segments");
                    for(int i=0;i<segments.length();i++){
                        JSONObject item=segments.getJSONObject(i);
                        srt.append(i+1).append('\n').append(time(item.getLong("start_ms"))).append(" --> ")
                            .append(time(item.getLong("end_ms"))).append('\n').append(item.getString("text")).append("\n\n");
                    }
                    new WorkspaceStore(app).writeText(folder+"/narration.srt",srt.toString(),true);
                    save();finish();return;
                }
                JSONObject item=input.getJSONObject(index);
                long requested=item.optLong("offset_ms",cursor);
                if(requested<cursor)throw new IOException("Les segments de narration ne doivent pas se chevaucher");
                cursor=requested;
                String name=String.format(Locale.ROOT,"part-%03d",index+1);
                WorkspaceStore store=new WorkspaceStore(app);
                partial=store.file(folder+"/"+name+".partial.wav");output=store.file(folder+"/"+name+".wav");
                if(output.exists()&&!output.renameTo(store.file(folder+"/"+name+"-interrompu-"+System.currentTimeMillis()+".wav")))
                    throw new IOException("Segment précédent verrouillé");
                utterance=args.getString("request_id")+"-"+index;
                WorkspaceCommands.UI.removeCallbacks(timeout);WorkspaceCommands.UI.postDelayed(timeout,90000);
                if(tts.synthesizeToFile(item.getString("text"),new Bundle(),partial,utterance)!=TextToSpeech.SUCCESS)
                    throw new IOException("Le moteur a refusé la synthèse");
            }catch(Exception error){fail(error.getMessage());}
        }
        void verify(){
            WorkspaceCommands.UI.removeCallbacks(timeout);
            final File source=partial,target=output;final int part=index;
            WorkspaceCommands.IO.execute(()->{
                try{
                    if(!source.isFile()||source.length()<45)throw new IOException("Aucun fichier vocal produit");
                    MediaMetadataRetriever probe=new MediaMetadataRetriever();long duration;
                    try{probe.setDataSource(source.getAbsolutePath());duration=Long.parseLong(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}
                    finally{probe.release();}
                    if(duration<100)throw new IOException("Fichier vocal vide ou illisible");
                    String temporary=folder+"/"+source.getName();
                    JSONObject measured=StudioAudioAnalysis.measure(app,temporary,0,duration,new AgentClient.ResultCallback(){
                        public boolean isActive(){return !ended;}
                        public void finish(boolean ok,String text){}
                    });
                    if(measured.optLong("decoded_samples")==0||!Double.isFinite(measured.optDouble("sample_peak_dbfs",Double.NaN)))
                        throw new IOException("Le fichier vocal ne contient aucun son décodable");
                    long trimStart=0,trimEnd=duration;
                    if(args.optBoolean("trim_silence",true)){
                        JSONArray silences=measured.getJSONArray("silences");
                        for(int i=0;i<silences.length();i++){
                            JSONObject silence=silences.getJSONObject(i);
                            if(silence.getLong("start_ms")<=100)trimStart=Math.max(0,silence.getLong("end_ms")-80);
                            if(silence.getLong("end_ms")>=duration-100)trimEnd=Math.min(duration,silence.getLong("start_ms")+80);
                        }
                    }
                    if(trimEnd-trimStart<100)throw new IOException("Le moteur a produit une narration silencieuse");
                    long spokenDuration=trimEnd-trimStart;
                    if(ended)return;
                    if(target.exists()||!source.renameTo(target))throw new IOException("Finalisation vocale impossible");
                    String path=folder+"/"+target.getName();String hash=WorkspaceTransfer.hash(target);
                    JSONObject input=data.getJSONArray("input").getJSONObject(part);
                    JSONObject result=new JSONObject().put("path",path).put("text",input.getString("text"))
                        .put("start_ms",cursor).put("end_ms",cursor+spokenDuration).put("duration_ms",spokenDuration)
                        .put("source_start_ms",trimStart).put("trimmed_silence_ms",duration-spokenDuration)
                        .put("pause_ms",input.optLong("pause_ms",300)).put("sha256",hash);
                    ProductionProject.asset(app,args.getString("project_path"),path,"narration",result);
                    WorkspaceCommands.UI.post(()->{
                        if(ended)return;
                        try{data.getJSONArray("segments").put(result);cursor=result.getLong("end_ms")+result.getLong("pause_ms");index++;save();next();}
                        catch(Exception error){fail(error.getMessage());}
                    });
                }catch(Exception error){WorkspaceCommands.UI.post(()->fail(error.getMessage()));}
            });
        }
        void fail(String reason){
            if(ended)return;
            try{data.put("state","interrupted").put("error",reason).put("resume_operation","narration_resume");save();}
            catch(Exception ignored){}
            finish();
        }
        void finish(){ended=true;WorkspaceCommands.UI.removeCallbacks(timeout);if(tts!=null){tts.stop();tts.shutdown();}if(current==this)current=null;}
    }
    static String time(long ms){return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000);}
}
