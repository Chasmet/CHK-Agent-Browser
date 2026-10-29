package com.chk.agentbrowser;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

/** Measurements from decoded samples; RMS dBFS is explicitly not integrated LUFS. */
final class StudioAudioAnalysis {
    private StudioAudioAnalysis(){}
    static JSONObject levels(File file,long start,long duration,AgentClient.ResultCallback cb)throws Exception{
        long[] count={0},clipped={0};double[] sum={0},peak={0};TreeMap<Long,double[]> bins=new TreeMap<>();int[] format={0,0};
        for(long part=0;part<duration;part+=180000){final long end=(start+duration)*1000;
            AudioDecoder.read(file,start+part,(int)Math.min(180,(duration-part+999)/1000),(pcm,rate,channels,time)->{format[0]=rate;format[1]=channels;int n=0;while(pcm.remaining()>=channels*2){long us=time+n++*1000000L/rate;if(us>=end){pcm.position(pcm.position()+channels*2);continue;}long bin=(us/1000-start)/100;double[] stats=bins.get(bin);if(stats==null){stats=new double[2];bins.put(bin,stats);}for(int c=0;c<channels;c++){double v=pcm.getShort()/32768d;sum[0]+=v*v;peak[0]=Math.max(peak[0],Math.abs(v));count[0]++;if(Math.abs(v)>=.999)clipped[0]++;stats[0]+=v*v;stats[1]++;}}},cb);
        }
        JSONArray silence=new JSONArray();long from=-1;for(Map.Entry<Long,double[]> entry:bins.entrySet()){double[] b=entry.getValue();boolean quiet=b[1]==0||10*Math.log10(Math.max(1e-15,b[0]/b[1]))<-50;long ms=entry.getKey()*100;if(quiet&&from<0)from=ms;if(!quiet&&from>=0){if(ms-from>=200&&silence.length()<200)silence.put(new JSONObject().put("start_ms",start+from).put("end_ms",start+ms));from=-1;}}
        if(from>=0&&duration-from>=200&&silence.length()<200)silence.put(new JSONObject().put("start_ms",start+from).put("end_ms",start+duration));
        return new JSONObject().put("sample_rate",format[0]).put("channels",format[1]).put("sample_count",count[0]).put("peak_dbfs",StudioPcmMix.level(peak[0])).put("rms_dbfs",StudioPcmMix.level(count[0]>0?Math.sqrt(sum[0]/count[0]):0)).put("clipped_samples",clipped[0]).put("silences",silence).put("loudness_metric","unweighted RMS dBFS; not BS.1770 integrated LUFS").put("speech_detected",JSONObject.NULL).put("speech_detector","not_run");
    }
    static JSONArray voices(Context app,JSONObject p,String language,AgentClient.ResultCallback cb)throws Exception{
        JSONArray regions=new JSONArray();WorkspaceStore store=new WorkspaceStore(app);
        for(StudioMix.Source s:StudioMix.sources(p)){
            if(!StudioMix.audible(p,s)||!s.original&&!StudioMix.track(p,s.track).optString("role","music").equals("voice"))continue;
            JSONObject info=MediaInspection.info(app,s.clip.getString("path"));boolean has=false;JSONArray streams=info.optJSONArray("tracks");if(streams!=null)for(int i=0;i<streams.length();i++)has|=streams.getJSONObject(i).optString("mime_type").startsWith("audio/");if(!has)continue;
            long start=s.clip.optLong("start_ms"),length=s.clip.optLong("duration_ms");JSONObject measure=levels(store.file(s.clip.getString("path")),start,length,cb);double rms=measure.optDouble("rms_dbfs",-120);
            for(long part=0;part<length;part+=180000){JSONArray words=MediaTranscriber.words(app,s.clip.getString("path"),language,(int)Math.min(180,(length-part+999)/1000),start+part,cb);
                for(int i=0;i<words.length();i++){JSONObject w=words.getJSONObject(i);long a=s.offset+Math.round((Math.max(start,w.getLong("start_ms"))-start)/s.speed),b=Math.min(s.offset+s.length,s.offset+Math.round((w.getLong("end_ms")-start)/s.speed));if(b<=a)continue;
                    if(regions.length()>=2000)throw new IOException("Plus de 2000 régions vocales : découper le projet");regions.put(new JSONObject().put("start_ms",a).put("end_ms",b).put("rms_db",rms).put("source_clip_id",s.clip.getString("clip_id")));}
            }
        }return regions;
    }
    static float[] samples(File file,long start,int seconds,AgentClient.ResultCallback cb)throws Exception{
        float[] out=new float[seconds*8000];int[] counts=new int[out.length];
        AudioDecoder.read(file,start,seconds,(pcm,rate,channels,time)->{int frame=0;while(pcm.remaining()>=channels*2){long index=Math.round((time+frame++*1000000d/rate-start*1000)/125d);double v=0;for(int c=0;c<channels;c++)v+=pcm.getShort()/32768d/channels;if(index>=0&&index<out.length){out[(int)index]+=v;counts[(int)index]++;}}},cb);
        for(int i=0;i<out.length;i++)if(counts[i]>0)out[i]/=counts[i];return out;
    }
    static JSONObject correlation(float[] expected,float[] actual)throws Exception{
        double best=-1;int offset=0;
        for(int lag=-800;lag<=800;lag+=8){double xy=0,xx=0,yy=0;for(int i=800;i<Math.min(expected.length,actual.length)-800;i+=8){double a=expected[i],b=actual[i+lag];xy+=a*b;xx+=a*a;yy+=b*b;}double c=xx>1e-9&&yy>1e-9?xy/Math.sqrt(xx*yy):0;if(c>best){best=c;offset=lag;}}
        return new JSONObject().put("correlation",best).put("audio_offset_ms",offset/8d).put("matches_reference",best>=.85&&Math.abs(offset)<=160).put("scope","sampled decoded audio, +/-100ms lag search, 1ms resolution");
    }
}
