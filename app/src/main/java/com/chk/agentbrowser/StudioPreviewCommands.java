package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import java.io.*;
import java.util.UUID;
import org.json.*;

final class StudioPreviewCommands {
    private StudioPreviewCommands(){}
    static void crop(Context app,JSONObject project,JSONObject args,AgentClient.ResultCallback cb)throws Exception{
        if(!AgentClient.get(app).isPreviewAllowed())throw new IOException("Partage des aperçus désactivé");
        JSONObject selected=StudioCommands.clip(project,args.getString("clip_id"));long offset=0;JSONArray clips=project.getJSONArray("clips");boolean found=false;for(int i=0;i<clips.length();i++){JSONObject clip=clips.getJSONObject(i);if(clip==selected){found=true;break;}offset+=StudioEdits.duration(clip);}if(!found)throw new IOException("Le recadrage cible un clip vidéo");
        final long at=Math.max(offset,Math.min(offset+StudioEdits.duration(selected)-1,args.optLong("time_ms",offset+StudioEdits.duration(selected)/2)));
        JSONObject snapshot=new JSONObject(project.toString());snapshot.put("audio",new JSONArray()).put("mute_original",true);snapshot.remove("audio_mix_version");
        WorkspaceCommands.UI.post(()->{if(!cb.isActive())return;StudioRenderPreview preview=new StudioRenderPreview(app);preview.start(snapshot,new StudioRenderPreview.Result(){
            public void ready(File file){WorkspaceCommands.IO.execute(()->{MediaMetadataRetriever decoder=new MediaMetadataRetriever();try{
                if(!cb.isActive())return;decoder.setDataSource(file.getAbsolutePath());Bitmap frame=decoder.getFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST);if(frame==null)throw new IOException("Le moteur n'a fourni aucune image");
                String folder="StudioPreviews";File dir=new WorkspaceStore(app).file(folder);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Dossier d'aperçus inaccessible");String path=folder+"/crop-"+UUID.randomUUID()+".png";File png=new WorkspaceStore(app).file(path);
                try(FileOutputStream out=new FileOutputStream(png)){if(!frame.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Encodage PNG impossible");out.getFD().sync();}finally{frame.recycle();}
                JSONObject proof=StudioCrop.describe(app,project,selected).put("state","completed").put("preview_path",path).put("preview_time_ms",at).put("engine","media3_transformer_rendered_mp4_frame").put("resolution",480).put("preview_png_bytes",png.length());reply(cb,true,proof.toString());
            }catch(Exception e){reply(cb,false,e.getMessage());}finally{try{decoder.release();}catch(Exception ignored){}WorkspaceCommands.UI.post(preview::cancel);}});}
            public void failed(String error){reply(cb,false,error);}
        });WorkspaceCommands.UI.postDelayed(new Runnable(){public void run(){if(!cb.isActive()){preview.cancel();return;}if(preview.busy())WorkspaceCommands.UI.postDelayed(this,500);}},500);});
    }
    private static void reply(AgentClient.ResultCallback cb,boolean ok,String text){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(ok,text);});}
}
