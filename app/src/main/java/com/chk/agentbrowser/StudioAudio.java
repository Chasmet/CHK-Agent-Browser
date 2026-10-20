package com.chk.agentbrowser;
import org.json.*;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;
import java.nio.ByteBuffer;

/** Shared timeline/volume policy used by preview and exported PCM audio. */
final class StudioAudio {
    static long at(JSONArray tracks,int index){long next=0;for(int i=0;i<=index;i++){JSONObject item=tracks.optJSONObject(i);if(item==null)continue;long at=item.optLong("at_ms",next);if(i==index)return at;next=at+item.optLong("duration_ms");}return next;}
    static float gain(JSONObject item,long local){long duration=item.optLong("duration_ms");if(local<0||local>=duration)return 0;double gain=item.optDouble("volume",1);long in=item.optLong("fade_in_ms"),out=item.optLong("fade_out_ms");if(in>0)gain*=Math.min(1,local/(double)in);if(out>0)gain*=Math.min(1,(duration-local)/(double)out);return (float)Math.max(0,Math.min(2,gain));}
    static float gain(JSONObject item,long local,JSONArray tracks,long absolute){float value=gain(item,local);if(item.optBoolean("duck_under_voice",false)&&tracks!=null)for(int i=0;i<tracks.length();i++){JSONObject voice=tracks.optJSONObject(i);long start=at(tracks,i);if(voice!=null&&"voice".equals(voice.optString("role"))&&absolute>=start&&absolute<start+voice.optLong("duration_ms"))return value*.25f;}return value;}
    static void validate(JSONObject item)throws Exception{double volume=item.optDouble("volume",1);long len=item.optLong("duration_ms");if((Double.isNaN(volume)||Double.isInfinite(volume))||volume<0||volume>1||item.optLong("at_ms",0)<0)throw new Exception("Volume (0–100 %) ou position audio invalide");for(String k:new String[]{"fade_in_ms","fade_out_ms"})if(item.optLong(k)<0||item.optLong(k)>len)throw new Exception("Fondu audio hors du plan");}
    static final class GainProcessor extends BaseAudioProcessor {
        private final JSONObject item;private final JSONArray tracks;private final long at;private long samples;
        GainProcessor(JSONObject item,JSONArray tracks,long at){this.item=item;this.tracks=tracks;this.at=at;}
        @Override protected AudioFormat onConfigure(AudioFormat format)throws AudioProcessor.UnhandledAudioFormatException{if(format.encoding!=C.ENCODING_PCM_16BIT&&format.encoding!=C.ENCODING_PCM_FLOAT)throw new AudioProcessor.UnhandledAudioFormatException(format);return format;}
        @Override public void queueInput(ByteBuffer input){int size=input.remaining();ByteBuffer output=replaceOutputBuffer(size);int channels=inputAudioFormat.channelCount;while(input.hasRemaining()){long local=(samples/channels)*1000/inputAudioFormat.sampleRate;float gain=gain(item,local,tracks,at+local);if(inputAudioFormat.encoding==C.ENCODING_PCM_FLOAT)output.putFloat(Math.max(-1,Math.min(1,input.getFloat()*gain)));else output.putShort((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(input.getShort()*gain))));samples++;}output.flip();}
        @Override protected void onFlush(){samples=0;}
    }
}
