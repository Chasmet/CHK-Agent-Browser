package com.chk.agentbrowser;
import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.*;
import java.util.*;

/** Prepare only currently audible tracks; inactive tracks never hold a decoder. */
final class StudioAudioPreview {
    private final Context context;private JSONArray tracks=new JSONArray();private final Map<Integer,ExoPlayer> players=new HashMap<>();
    StudioAudioPreview(Context c){context=c;}
    void project(JSONObject p){release();tracks=p.optJSONArray("audio");if(tracks==null)tracks=new JSONArray();}
    void sync(long position,boolean playing){
        Set<Integer> used=new HashSet<>();
        for(int i=0;i<tracks.length();i++){JSONObject track=tracks.optJSONObject(i);if(track==null)continue;long local=position-StudioAudio.at(tracks,i);if(local<0||local>=track.optLong("duration_ms"))continue;used.add(i);
            ExoPlayer player=players.get(i);try{
                if(player==null){player=new ExoPlayer.Builder(context).build();MediaItem media=new MediaItem.Builder().setUri(Uri.fromFile(new WorkspaceStore(context).file(track.getString("path")))).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(track.optLong("start_ms")).setEndPositionMs(track.optLong("start_ms")+track.getLong("duration_ms")).build()).build();player.setMediaItem(media);player.prepare();players.put(i,player);player.seekTo(local);}
                if(Math.abs(player.getCurrentPosition()-local)>250)player.seekTo(local);
                player.setVolume(Math.min(1,StudioAudio.gain(track,local,tracks,position)));player.setPlayWhenReady(playing);
            }catch(Exception ignored){if(player!=null){player.release();players.remove(i);}}
        }
        Iterator<Map.Entry<Integer,ExoPlayer>> it=players.entrySet().iterator();while(it.hasNext()){Map.Entry<Integer,ExoPlayer> row=it.next();if(!used.contains(row.getKey())){row.getValue().release();it.remove();}}
    }
    void pause(){for(ExoPlayer p:players.values())p.pause();}
    void release(){for(ExoPlayer p:players.values())p.release();players.clear();}
}
