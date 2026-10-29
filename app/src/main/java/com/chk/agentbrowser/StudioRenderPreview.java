package com.chk.agentbrowser;

import android.content.Context;
import android.os.*;
import androidx.media3.transformer.*;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Owner-requested low-resolution render of the same composition as the final MP4. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioRenderPreview {
    interface Result {void ready(File file);void failed(String error);}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final Context app;private final Handler main=new Handler(Looper.getMainLooper());
    private Transformer transformer;private volatile long generation;private boolean busy;private File output,ready;
    StudioRenderPreview(Context context){app=context.getApplicationContext();}
    boolean busy(){return busy;}
    int progress(){if(transformer==null)return 0;ProgressHolder h=new ProgressHolder();transformer.getProgress(h);return h.progress;}
    void start(JSONObject project,Result result){
        cancel();long ticket=generation;busy=true;
        IO.execute(()->{try{
            JSONObject copy=new JSONObject(project.toString());VideoEditorEngine.get().validate(app,copy);copy.put("resolution",480);
            Composition composition=VideoEditorEngine.get().composition(app,copy,new AgentClient.ResultCallback(){public void finish(boolean ok,String text){}public boolean isActive(){return ticket==generation;}});
            main.post(()->{if(ticket!=generation)return;try{
                output=new File(app.getCacheDir(),"studio-preview-"+UUID.randomUUID()+".mp4");File file=output;
                transformer=new Transformer.Builder(app).addListener(new Transformer.Listener(){
                    public void onCompleted(Composition c,ExportResult proof){if(ticket!=generation){file.delete();return;}transformer=null;busy=false;output=null;
                        if(!file.isFile()||file.length()<500){file.delete();result.failed("Aperçu vide");return;}
                        ready=file;result.ready(file);
                    }
                    public void onError(Composition c,ExportResult proof,ExportException error){if(ticket!=generation){file.delete();return;}transformer=null;busy=false;output=null;file.delete();result.failed(error.getMessage());}
                }).build();transformer.start(composition,file.getAbsolutePath());
            }catch(Exception e){busy=false;result.failed(e.getMessage());}});
        }catch(Exception e){main.post(()->{if(ticket==generation){busy=false;result.failed(e.getMessage());}});}});
    }
    void cancel(){generation++;busy=false;if(transformer!=null){transformer.cancel();transformer=null;}if(output!=null){output.delete();output=null;}if(ready!=null){ready.delete();ready=null;}}
}
