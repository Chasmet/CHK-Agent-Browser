package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/**
 * A real Media3 rendered crop preview, independently decoded from the short
 * intermediate MP4. Never labels a source thumbnail as a rendered result.
 */
final class StudioCropPreview {
    private StudioCropPreview(){}
    static void render(Context app,JSONObject project,String id,VideoEditorEngine.Done callback){
        if(!AgentClient.get(app).isPreviewAllowed()){
            callback.completed(false,"Autorise le partage des aperçus CHK avant d'envoyer une image au connecteur.");
            return;
        }
        try{
            int index=StudioCropControl.clipIndex(project,id);
            JSONObject source=project.getJSONArray("clips").getJSONObject(index);
            JSONObject brief=new JSONObject(project.toString());
            JSONObject item=new JSONObject(source.toString());
            long length=item.optLong("duration_ms",2000);
            item.put("duration_ms",Math.min(2000,length));
            brief.put("clips",new JSONArray().put(item));
            brief.put("audio",new JSONArray()).put("mute_original",true)
                .put("layers",new JSONArray()).put("subtitles",new JSONArray())
                .put("resolution",480);
            StudioRenderPreview renderer=new StudioRenderPreview(app);
            renderer.start(brief,new StudioRenderPreview.Result(){
                public void ready(File mp4){
                    VideoEditorEngine.DISK.execute(()->{
                        MediaMetadataRetriever media=new MediaMetadataRetriever();
                        try{
                            media.setDataSource(mp4.getAbsolutePath());
                            long instant=Math.min(500000,item.optLong("duration_ms")*500);
                            // Scaled-frame decoding arrived in Android 8.1/API 27.
                            // Keep the existing minSdk 23 devices supported.
                            Bitmap frame=android.os.Build.VERSION.SDK_INT>=27
                                ?media.getScaledFrameAtTime(instant,MediaMetadataRetriever.OPTION_CLOSEST,480,480)
                                :media.getFrameAtTime(instant,MediaMetadataRetriever.OPTION_CLOSEST);
                            if(frame==null)throw new IllegalStateException("Aucune image décodable dans le MP4 Media3 de prévisualisation");
                            WorkspaceStore store=new WorkspaceStore(app);
                            if(!store.file("studio-previews").isDirectory())store.mkdir("studio-previews");
                            String path="studio-previews/crop-"+UUID.randomUUID()+".jpg";
                            File jpg=store.file(path);
                            try(FileOutputStream out=new FileOutputStream(jpg)){
                                if(!frame.compress(Bitmap.CompressFormat.JPEG,88,out))
                                    throw new IllegalStateException("Capture JPEG non encodée");
                            }finally{frame.recycle();}
                            if(!jpg.isFile()||jpg.length()<500)
                                throw new IllegalStateException("Capture vide");
                            callback.completed(true,new JSONObject()
                                .put("state","completed").put("rendered",true)
                                .put("capture_source","decoded_media3_preview_mp4")
                                .put("path",path).put("size_bytes",jpg.length())
                                .put("sha256",WorkspaceTransfer.hash(jpg))
                                .put("project_id",project.optString("project_id"))
                                .put("revision",project.optInt("revision"))
                                .put("clip_id",id)
                                .put("scope","2 premières secondes du clip sélectionné uniquement")
                                .put("read_with","media_frame ou files_preview")
                                .toString());
                        }catch(Exception e){
                            callback.completed(false,"Capture rendue non vérifiée : "+e.getMessage());
                        }finally{
                            try{media.release();}catch(Exception ignored){}renderer.cancel();
                        }
                    });
                }
                public void failed(String error){
                    renderer.cancel();
                    callback.completed(false,"Moteur de prévisualisation : "+error);
                }
            });
        }catch(Exception e){callback.completed(false,e.getMessage());}
    }
}