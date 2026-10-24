package com.chk.agentbrowser;

import android.content.Context;
import java.io.File;
import java.util.*;
import org.json.*;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.util.Size;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.transformer.*;

/** Timed, muted PiP layers. The first layer is the topmost layer. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioLayers {
    static final int MAX=3;
    private StudioLayers(){}
    static JSONArray items(JSONObject project){JSONArray a=project.optJSONArray("layers");return a==null?new JSONArray():a;}
    static void validate(Context context,JSONObject project,long total)throws Exception {
        if(project.has("layers")&&!(project.opt("layers") instanceof JSONArray))throw new IllegalArgumentException("La piste layers doit être une liste");
        JSONArray layers=items(project);if(layers.length()>MAX)throw new IllegalArgumentException("3 calques maximum");
        WorkspaceStore store=new WorkspaceStore(context);File output=store.file(project.getString("output"));
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i);String path=layer.getString("path");File source=store.file(path);
            if(!source.isFile()||source.length()==0)throw new IllegalArgumentException("Calque introuvable : "+path);
            if(source.equals(output))throw new IllegalArgumentException("La sortie écraserait un calque source");
            JSONObject info=MediaInspection.info(context,path);boolean image=info.optBoolean("image");
            boolean hasVideo=false;JSONArray tracks=info.optJSONArray("tracks");if(tracks!=null)for(int j=0;j<tracks.length();j++)if(tracks.optJSONObject(j).optString("mime_type").startsWith("video/"))hasVideo=true;
            if(!image&&!hasVideo)throw new IllegalArgumentException("Calque image ou vidéo requis");
            long at=layer.optLong("offset_ms"),start=layer.optLong("start_ms"),duration=layer.getLong("duration_ms");
            if(at<0||start<0||duration<(image?1000:500)||at>total-duration)throw new IllegalArgumentException("Le calque doit tenir dans le montage");
            if(image&&(start!=0||duration>60000))throw new IllegalArgumentException("Photo de 1 à 60 secondes requise");
            if(!image&&start+duration>info.optLong("duration_ms")+250)throw new IllegalArgumentException("Calque au-delà de la source");
            number(layer,"x",0,-1,1);number(layer,"y",0,-1,1);number(layer,"scale",.35,.1,1);number(layer,"opacity",1,0,1);
        }
    }
    private static void number(JSONObject layer,String key,double fallback,double min,double max){double v=layer.optDouble(key,fallback);if(Double.isNaN(v)||Double.isInfinite(v)||v<min||v>max)throw new IllegalArgumentException("Réglage de calque invalide : "+key);}
    static List<EditedMediaItemSequence> sequences(Context context,JSONObject project,long total)throws Exception {
        List<EditedMediaItemSequence> out=new ArrayList<>();WorkspaceStore store=new WorkspaceStore(context);JSONArray layers=items(project);
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i);long offset=layer.optLong("offset_ms"),duration=layer.optLong("duration_ms",3000),start=layer.optLong("start_ms");
            File file=store.file(layer.getString("path"));boolean image=StudioImages.isImage(file);
            MediaItem media=image?StudioImages.media(file,duration):new MediaItem.Builder().setUri(android.net.Uri.fromFile(file)).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(start+duration).build()).build();
            EditedMediaItem.Builder item=new EditedMediaItem.Builder(media).setFrameRate(project.optInt("fps",30)).setRemoveAudio(true);
            if(!image)item.setDurationUs(MediaInspection.info(context,layer.getString("path")).optLong("duration_ms")*1000);
            EditedMediaItemSequence.Builder sequence=new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_VIDEO));
            if(offset>0)sequence.addGap(offset*1000);sequence.addItem(item.build());if(total>offset+duration)sequence.addGap((total-offset-duration)*1000);out.add(sequence.build());
        }
        return out;
    }
    static VideoCompositorSettings compositor(JSONObject project,int width,int height)throws Exception {
        JSONArray copy=new JSONArray(items(project).toString());
        return new VideoCompositorSettings(){
            private List<Size> sizes=Collections.emptyList();
            public Size getOutputSize(List<Size> inputSizes){sizes=new ArrayList<>(inputSizes);return new Size(width,height);}
            public OverlaySettings getOverlaySettings(int inputId,long timeUs){
                if(inputId>=copy.length())return new StaticOverlaySettings.Builder().build();
                JSONObject layer=copy.optJSONObject(inputId);long ms=timeUs/1000,at=layer.optLong("offset_ms"),duration=layer.optLong("duration_ms",3000);
                float scale=(float)layer.optDouble("scale",.35);if(inputId<sizes.size())scale*=width/(float)sizes.get(inputId).getWidth();
                return new StaticOverlaySettings.Builder().setAlphaScale(ms>=at&&ms<at+duration?(float)layer.optDouble("opacity",1):0)
                    .setScale(scale,scale).setBackgroundFrameAnchor((float)layer.optDouble("x"),-(float)layer.optDouble("y")).build();
            }
        };
    }
}
