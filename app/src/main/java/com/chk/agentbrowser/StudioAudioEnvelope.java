package com.chk.agentbrowser;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Streamed, timestamped PCM16 gain automation. Uses decoded audio frames, not
 * metadata-only effects. Does not change source files or composition duration.
 * Speech regions come from verified, saved subtitle timestamps (not an
 * unverified "voice detected" claim).
 */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioAudioEnvelope extends BaseAudioProcessor {
    private final float gain;
    private final long durationMs, fadeInMs, fadeOutMs;
    private final long[] keysMs, voiceStart, voiceEnd;
    private final float[] keysGain;
    private final boolean duck;
    private final float reduced;
    private final float attackMs, releaseMs;
    private int sampleRate,channels;
    private long frames;
    private float duckFactor=1f;

    StudioAudioEnvelope(JSONObject clip, JSONObject project, boolean music, long startMs){
        gain=(float)clip.optDouble("volume",1);
        durationMs=clip.optLong("duration_ms",10000);
        fadeInMs=clip.optLong("fade_in_ms",0);
        fadeOutMs=clip.optLong("fade_out_ms",0);
        JSONArray keys=clip.optJSONArray("volume_keyframes");
        if(keys==null)keys=new JSONArray();
        keysMs=new long[keys.length()];
        keysGain=new float[keys.length()];
        for(int i=0;i<keys.length();i++){
            JSONObject k=keys.optJSONObject(i);
            keysMs[i]=k==null?0:k.optLong("time_ms");
            keysGain[i]=k==null?1:(float)k.optDouble("gain",1);
        }
        JSONObject ducking=music?clip.optJSONObject("ducking"):null;
        duck=ducking!=null&&ducking.optBoolean("enabled",false);
        reduced=(float)Math.pow(10,-(ducking==null?0:ducking.optDouble("reduction_db",12))/20.0);
        attackMs=(float)(ducking==null?60:ducking.optDouble("attack_ms",60));
        releaseMs=(float)(ducking==null?350:ducking.optDouble("release_ms",350));
        JSONArray cues=duck?StudioSubtitles.items(project):new JSONArray();
        ArrayList<Long> starts=new ArrayList<>(),ends=new ArrayList<>();
        for(int i=0;i<cues.length();i++){
            JSONObject cue=cues.optJSONObject(i);
            if(cue==null)continue;
            long a=cue.optLong("start_ms",-1)-startMs,b=cue.optLong("end_ms",-1)-startMs;
            if(b>0&&a<durationMs){starts.add(Math.max(0,a));ends.add(Math.min(durationMs,b));}
        }
        voiceStart=new long[starts.size()];voiceEnd=new long[ends.size()];
        for(int i=0;i<voiceStart.length;i++){voiceStart[i]=starts.get(i);voiceEnd[i]=ends.get(i);}
    }
    @Override protected AudioFormat onConfigure(AudioFormat format) throws AudioProcessor.UnhandledAudioFormatException {
        if(format.encoding!=C.ENCODING_PCM_16BIT||format.channelCount<1||format.channelCount>8)
            throw new AudioProcessor.UnhandledAudioFormatException(format);
        sampleRate=format.sampleRate;channels=format.channelCount;
        return format;
    }
    @Override public void queueInput(ByteBuffer input) {
        int count=input.remaining();
        ByteBuffer output=replaceOutputBuffer(count).order(ByteOrder.LITTLE_ENDIAN);
        input.order(ByteOrder.LITTLE_ENDIAN);
        int frameBytes=channels*2;
        while(input.remaining()>=frameBytes){
            long ms=frames*1000L/sampleRate;
            float level=gain*keyGain(ms);
            if(fadeInMs>0&&ms<fadeInMs)level*=ms/(float)fadeInMs;
            if(fadeOutMs>0&&ms>durationMs-fadeOutMs)
                level*=Math.max(0f,(durationMs-ms)/(float)fadeOutMs);
            if(duck){
                boolean speaking=false;
                for(int i=0;i<voiceStart.length;i++)if(ms>=voiceStart[i]&&ms<=voiceEnd[i]){speaking=true;break;}
                float target=speaking?reduced:1f;
                float smooth=(speaking?attackMs:releaseMs)*sampleRate/1000f;
                duckFactor+= (target-duckFactor)/Math.max(1f,smooth);
                level*=duckFactor;
            }
            for(int c=0;c<channels;c++){
                short sample=input.getShort();
                float mixed=sample*level;
                // Bounded per-source peaks. Output-wide clipping is still
                // checked independently after rendering.
                output.putShort((short)Math.max(-32768,Math.min(32767,Math.round(mixed))));
            }
            frames++;
        }
        while(input.hasRemaining())output.put(input.get());
        output.flip();
    }
    private float keyGain(long ms){
        if(keysMs.length==0)return 1f;
        if(ms<=keysMs[0])return keysGain[0];
        for(int i=1;i<keysMs.length;i++)if(ms<=keysMs[i]){
            float t=(ms-keysMs[i-1])/(float)Math.max(1,keysMs[i]-keysMs[i-1]);
            return keysGain[i-1]+(keysGain[i]-keysGain[i-1])*t;
        }
        return keysGain[keysGain.length-1];
    }
    @Override protected void onFlush(){frames=0;duckFactor=1f;}
    @Override protected void onReset(){frames=0;duckFactor=1f;}
    static boolean needed(JSONObject clip){
        return clip.optLong("fade_in_ms")>0||clip.optLong("fade_out_ms")>0||
            clip.optJSONArray("volume_keyframes")!=null||
            (clip.optJSONObject("ducking")!=null&&clip.optJSONObject("ducking").optBoolean("enabled"));
    }
}