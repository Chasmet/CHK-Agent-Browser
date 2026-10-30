package com.chk.agentbrowser;

import android.content.Context;
import org.json.*;
import java.io.*;

/** Full decoded sample measurements, never infers source presence from a container audio flag. */
final class StudioAudioAnalysis {
    private StudioAudioAnalysis(){}
    static JSONObject measure(Context app,String path,long start,long duration,AgentClient.ResultCallback active)throws Exception{
        if(start<0||duration<1||duration>1200000)throw new IOException("ANALYSIS_RANGE_INVALID");
        File file=new WorkspaceStore(app).file(path);String before=WorkspaceTransfer.hash(file);
        final double[] square={0},peak={0};final long[] samples={0},clipped={0};final long[] bins=new long[(int)(duration/100)+2];final double[] energy=new double[bins.length];
        final StudioLoudness[] loudness={null};final boolean[] unsupported={false};
        int rate=0,channels=0;long first=-1,last=0;
        for(long at=0;at<duration;at+=180000){
            StudioAudioMixer.check(active);long from=start+at;int seconds=(int)Math.ceil(Math.min(180000,duration-at)/1000d);
            AudioDecoder.Info info=AudioDecoder.read(file,from,seconds,(data,sampleRate,count,time)->{
                if(count>2)unsupported[0]=true;else if(loudness[0]==null)loudness[0]=new StudioLoudness(sampleRate,count);else if(loudness[0].rate!=sampleRate||loudness[0].channels!=count)unsupported[0]=true;
                long frame=0;while(data.remaining()>=count*2){double sum=0,weighted=0;double timestamp=time/1000d+frame++*1000d/sampleRate;if(timestamp>=start+duration)break;
                    for(int c=0;c<count;c++){int raw=data.getShort();double v=raw/32768d;sum+=v*v;if(!unsupported[0])weighted+=loudness[0].channel(c,v);square[0]+=v*v;peak[0]=Math.max(peak[0],Math.abs(v));samples[0]++;if(Math.abs(raw)>=32767)clipped[0]++;}
                    if(!unsupported[0])loudness[0].frame(weighted);
                    int bin=(int)((timestamp-start)/100);if(bin>=0&&bin<bins.length){energy[bin]+=sum/count;bins[bin]++;}
                }
            },active);rate=info.rate;channels=info.channels;if(first<0)first=info.firstUs;last=info.lastUs;
        }
        if(!before.equals(WorkspaceTransfer.hash(file)))throw new IOException("SOURCE_CHANGED_DURING_ANALYSIS");
        JSONArray envelope=new JSONArray(),silences=new JSONArray();long began=-1;
        for(int i=0;i<bins.length;i++)if(bins[i]>0){double rms=Math.sqrt(energy[i]/bins[i]);double level=rms==0?-120:20*Math.log10(rms);long at=start+i*100L;
            envelope.put(new JSONObject().put("time_ms",at).put("rms_dbfs",level));if(level<-45){if(began<0)began=at;}else if(began>=0){silences.put(new JSONObject().put("start_ms",began).put("end_ms",at));began=-1;}
        }
        if(began>=0)silences.put(new JSONObject().put("start_ms",began).put("end_ms",Math.min(start+duration,last/1000)));
        return new JSONObject().put("state","completed").put("path",path).put("sha256",before).put("sample_rate",rate).put("channels",channels)
            .put("decoded_samples",samples[0]).put("source_start_ms",first/1000).put("source_end_ms",Math.min(start+duration,last/1000))
            .put("rms_dbfs",StudioAudio.db(Math.sqrt(square[0]/Math.max(1,samples[0])))).put("sample_peak_dbfs",StudioAudio.db(peak[0]))
            .put("full_scale_samples",clipped[0]).put("sample_clipping_detected",clipped[0]>0).put("silence_threshold_dbfs",-45)
            .put("silences",silences).put("envelope_100ms",envelope).put("lufs_measured",!unsupported[0]&&loudness[0]!=null&&loudness[0].sufficient()).put("integrated_lufs",!unsupported[0]&&loudness[0]!=null&&loudness[0].sufficient()?loudness[0].integrated():JSONObject.NULL).put("loudness_method","BS.1770 K weighting; mono/stereo; 400ms blocks/100ms hop, absolute and relative gates").put("true_peak_measured",false)
            .put("speech_presence_verified",false).put("lip_sync_verified",false);
    }
}
