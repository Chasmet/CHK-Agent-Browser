package com.chk.agentbrowser;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Disk-backed floating-point summing, then a linked stereo sample-peak limiter.
 * Decode uses Android codecs; resampling/speed interpolation is linear (pitch changes with speed).
 * Source files and project gain values are never rewritten by this renderer.
 */
final class StudioAudioMixer {
    static final int RATE=48000;
    private StudioAudioMixer(){}
    interface Progress {void update(int percent);}
    static final class Render {final File wav;final JSONObject proof;Render(File f,JSONObject p){wav=f;proof=p;}}
    static synchronized Render render(Context app,JSONObject p,AgentClient.ResultCallback active,Progress progress)throws Exception{
        StudioAudio.validate(p);long duration=StudioEdits.total(p),frames=duration*RATE/1000;
        if(frames<1)throw new IOException("EMPTY_TIMELINE");
        JSONArray sources=StudioAudio.sources(p),fingerprints=new JSONArray();WorkspaceStore store=new WorkspaceStore(app);
        for(int i=0;i<sources.length();i++){
            check(active);JSONObject c=sources.getJSONObject(i);File f=store.file(c.getString("path"));
            fingerprints.put(new JSONObject().put("path",c.getString("path")).put("sha256",WorkspaceTransfer.hash(f)));
        }
        String key=AgentVision.digest("pcm-renderer-r3|"+StudioMcp.canonical(audioSignature(p))+fingerprints.toString());
        File folder=new File(app.getCacheDir(),"studio-audio-v2");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("MIX_STORAGE_UNAVAILABLE");
        File wav=new File(folder,key+".wav"),receipt=new File(folder,key+".json");
        if(wav.isFile()&&receipt.isFile()){
            JSONObject proof=StudioAutomation.read(receipt);
            if(proof.optString("wav_sha256").equals(WorkspaceTransfer.hash(wav)))return new Render(wav,proof.put("project_id",p.optString("project_id")).put("revision",p.optInt("revision")));
            wav.delete();receipt.delete();
        }
        if(folder.getUsableSpace()<frames*12+32*1024*1024L)throw new IOException("MIX_STORAGE_LOW: espace PCM temporaire insuffisant");
        File raw=new File(folder,key+".float-part"),partial=new File(folder,key+".wav-part");
        try{
            Map<String,JSONArray> speech=new HashMap<>();JSONArray tracks=p.getJSONArray("audio_tracks");
            for(int i=0;i<tracks.length();i++){
                JSONObject d=tracks.getJSONObject(i).optJSONObject("ducking");
                if(d!=null&&d.optBoolean("enabled")){
                    String voice=d.optString("voice_track_id","original");StudioAudio.track(p,voice);
                    if(!speech.containsKey(voice))speech.put(voice,speech(app,p,voice,active));
                }
            }
            JSONArray contributions=new JSONArray();
            try(RandomAccessFile master=new RandomAccessFile(raw,"rw")){
                master.setLength(frames*8);
                for(int i=0;i<sources.length();i++){
                    check(active);JSONObject c=sources.getJSONObject(i);File file=store.file(c.getString("path"));
                    JSONObject info=MediaInspection.info(app,c.getString("path"));
                    if(StudioImages.isImage(file)||!hasAudio(info)||!StudioAudio.audible(p,c))continue;
                    JSONObject track=StudioAudio.track(p,c.getString("track_id")),duck=track.optJSONObject("ducking");
                    JSONArray regions=duck==null?null:speech.get(duck.optString("voice_track_id","original"));
                    Accumulator accumulator=new Accumulator(master,frames,c.getLong("timeline_ms")*RATE/1000);
                    Sampler sampler=new Sampler(c,track,duration,regions,duck,accumulator);
                    long start=c.optLong("start_ms"),length=c.optLong("duration_ms",10000);
                    for(long at=0;at<length;at+=180000){check(active);int seconds=(int)Math.ceil(Math.min(180000,length-at)/1000d);sampler.observed(AudioDecoder.read(file,start+at,seconds,sampler::pcm,active));}
                    sampler.finish();accumulator.flush();
                    contributions.put(new JSONObject().put("clip_id",c.optString("studio_id")).put("track_id",c.getString("track_id"))
                        .put("source",c.getString("path")).put("mixed_frames",sampler.count).put("padded_silence_frames",sampler.padded).put("decoded_source_end_ms",sampler.decodedEndUs/1000d).put("source_eos_reached",sampler.eos).put("contribution_rms",sampler.count==0?0:Math.sqrt(sampler.energy/(sampler.count*2)))
                        .put("volume_percent",c.optDouble("volume",1)*100).put("track_volume_percent",track.optDouble("volume",1)*100));
                    progress.update((i+1)*80/Math.max(1,sources.length()));
                }

                double ceiling=Math.pow(10,-1d/20),limiterGain=1,release=Math.exp(-1d/(RATE*.1)),peakBefore=0,peakAfter=0,energy=0;long limited=0;
                master.seek(0);
                DataInputStream mixedInput=new DataInputStream(new BufferedInputStream(new FileInputStream(raw),65536));
                try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(partial),65536)){
                    header(out,frames);for(long n=0;n<frames;n++){
                        if((n&32767)==0){check(active);progress.update(80+(int)(n*19/frames));}
                        float l=mixedInput.readFloat(),r=mixedInput.readFloat();double peak=Math.max(Math.abs(l),Math.abs(r));peakBefore=Math.max(peakBefore,peak);
                        double required=peak<=ceiling?1:ceiling/peak;limiterGain=Math.min(required,1-(1-limiterGain)*release);if(limiterGain<.9999)limited++;
                        double left=l*limiterGain,right=r*limiterGain;peakAfter=Math.max(peakAfter,Math.max(Math.abs(left),Math.abs(right)));energy+=left*left+right*right;
                        little(out,Math.max(-32768,Math.min(32767,Math.round(left*32767))),2);little(out,Math.max(-32768,Math.min(32767,Math.round(right*32767))),2);
                    }
                }
                mixedInput.close();
                JSONObject proof=new JSONObject().put("state","completed").put("engine","chk_pcm_float_v2").put("renderer_revision",3).put("duration_ms",duration).put("sample_rate",RATE).put("channels",2)
                    .put("project_id",p.optString("project_id")).put("revision",p.optInt("revision")).put("source_fingerprints",fingerprints)
                    .put("contributions",contributions).put("peak_before_dbfs",StudioAudio.db(peakBefore)).put("peak_after_dbfs",StudioAudio.db(peakAfter))
                    .put("rms_dbfs",StudioAudio.db(Math.sqrt(energy/(frames*2)))).put("limiter_ceiling_dbfs",-1).put("limiter_affected_frames",limited)
                    .put("voice_regions",new JSONObject(speech)).put("speech_engine",speech.isEmpty()?"disabled":"vosk_words_and_pcm_threshold").put("true_peak_verified",false).put("pitch_preserved_on_speed_change",false);
                // Only reusable, closed files become cache entries.
                if(!partial.renameTo(wav))throw new IOException("MIX_FINALIZE_FAILED");proof.put("wav_sha256",WorkspaceTransfer.hash(wav));StudioAutomation.write(receipt,proof);progress.update(100);return new Render(wav,proof);
            }
        }finally{raw.delete();partial.delete();}
    }
    static JSONObject audioSignature(JSONObject p)throws Exception{
        JSONObject out=new JSONObject().put("audio_model_version",2).put("mute_original",p.optBoolean("mute_original",false)).put("speech_language",p.optString("speech_language","fr")).put("audio_tracks",p.getJSONArray("audio_tracks"));
        for(String lane:new String[]{"clips","audio"}){JSONArray input=p.optJSONArray(lane),items=new JSONArray();if(input!=null)for(int i=0;i<input.length();i++){JSONObject c=input.getJSONObject(i),item=new JSONObject();for(String key:new String[]{"studio_id","path","start_ms","duration_ms","speed","volume","mute","track_id","offset_ms","gain_keyframes","audio_fade_in_ms","audio_fade_out_ms"})if(c.has(key))item.put(key,c.get(key));items.put(item);}out.put(lane,items);}return out;
    }
    static boolean hasAudio(JSONObject info){
        JSONArray a=info.optJSONArray("tracks");if(a!=null)for(int i=0;i<a.length();i++)if(a.optJSONObject(i).optString("mime_type").startsWith("audio/"))return true;return info.optBoolean("has_audio");
    }
    static void check(AgentClient.ResultCallback active)throws IOException{if(active!=null&&!active.isActive())throw new IOException("CANCELLED: mixage interrompu");}
    /** Fresh speech timings on the selected voice track; no text is persisted. */
    static JSONArray speech(Context app,JSONObject p,String track,AgentClient.ResultCallback active)throws Exception{
        JSONArray spans=new JSONArray(),sources=StudioAudio.sources(p);
        for(int i=0;i<sources.length();i++){
            JSONObject c=sources.getJSONObject(i);if(!track.equals(c.getString("track_id")))continue;
            File f=new WorkspaceStore(app).file(c.getString("path"));if(StudioImages.isImage(f)||!hasAudio(MediaInspection.info(app,c.getString("path"))))continue;
            long start=c.optLong("start_ms"),length=c.optLong("duration_ms",10000);double speed=c.optDouble("speed",1);
            for(long at=0;at<length;at+=180000){
                check(active);int seconds=(int)Math.ceil(Math.min(180000,length-at)/1000d);
                JSONArray words=MediaTranscriber.words(app,c.getString("path"),p.optString("speech_language","fr"),seconds,start+at,active);
                // Measure 100 ms RMS alongside actual recognized speech, never label all signal as voice.
                final double[] sum=new double[seconds*10+1];final long[] count=new long[sum.length];final long from=start+at;
                AudioDecoder.read(f,from,seconds,(data,rate,channels,time)->{long n=0;while(data.remaining()>=channels*2){double square=0;for(int ch=0;ch<channels;ch++){double v=data.getShort()/32768d;square+=v*v;}int bin=(int)((time/1000d+n++*1000d/rate-from)/100);if(bin>=0&&bin<sum.length){sum[bin]+=square/channels;count[bin]++;}}},active);
                for(int j=0;j<words.length();j++){
                    JSONObject w=words.getJSONObject(j);long a=Math.max(start,w.getLong("start_ms")),b=Math.min(start+length,w.getLong("end_ms"));if(b<=a)continue;
                    double rms=0;int bins=0;for(int k=Math.max(0,(int)((a-from)/100));k<=Math.min(sum.length-1,(int)((b-from)/100));k++)if(count[k]>0){rms+=sum[k]/count[k];bins++;}
                    spans.put(new JSONObject().put("start_ms",c.getLong("timeline_ms")+Math.round((a-start)/speed)).put("end_ms",c.getLong("timeline_ms")+Math.round((b-start)/speed))
                        .put("rms_dbfs",bins==0?-120:Math.max(-120,20*Math.log10(Math.max(1e-6,Math.sqrt(rms/bins))))));
                }
            }
        }return spans;
    }
    static double duckGain(JSONArray regions,JSONObject d,long ms)throws Exception{
        if(d==null||!d.optBoolean("enabled")||regions==null)return 1;
        double reduction=Math.pow(10,d.optDouble("reduction_db",-18)/20),amount=0,attack=d.optDouble("attack_ms",100),release=d.optDouble("release_ms",500);
        for(int i=0;i<regions.length();i++){
            JSONObject r=regions.getJSONObject(i);if(r.optDouble("rms_dbfs",-120)<d.optDouble("threshold_dbfs",-40))continue;
            long a=r.getLong("start_ms"),b=r.getLong("end_ms");double x=ms<a?Math.max(0,1-(a-ms)/attack):ms<=b?1:Math.max(0,1-(ms-b)/release);amount=Math.max(amount,x);
        }return 1-(1-reduction)*amount;
    }
    static double[] curve(JSONArray regions,JSONObject d,long total)throws Exception{
        if(d==null||!d.optBoolean("enabled")||regions==null)return null;
        double[] out=new double[(int)(total/10)+2];Arrays.fill(out,1);double attack=d.optDouble("attack_ms",100),release=d.optDouble("release_ms",500),reduction=Math.pow(10,d.optDouble("reduction_db",-18)/20);
        for(int i=0;i<regions.length();i++){
            JSONObject r=regions.getJSONObject(i);if(r.optDouble("rms_dbfs",-120)<d.optDouble("threshold_dbfs",-40))continue;long a=r.getLong("start_ms"),b=r.getLong("end_ms");
            int first=Math.max(0,(int)((a-attack)/10)),last=Math.min(out.length-1,(int)Math.ceil((b+release)/10));
            for(int j=first;j<=last;j++){long ms=j*10L;double amount=ms<a?Math.max(0,1-(a-ms)/attack):ms<=b?1:Math.max(0,1-(ms-b)/release);out[j]=Math.min(out[j],1-(1-reduction)*amount);}
        }return out;
    }
    private static final class Sampler {
        final JSONObject c,track,duck;final JSONArray regions;final long total;final Accumulator out;
        final long start,length,outputFrames;final double speed;final double[] duckCurve;long lastGainMs=-1;double cachedGain;double prevTime=Double.NaN,prevL,prevR;long next,count,padded,decodedEndUs;int lastRate;boolean eos;double energy;
        Sampler(JSONObject c,JSONObject track,long total,JSONArray regions,JSONObject duck,Accumulator out)throws Exception{this.c=c;this.track=track;this.total=total;this.regions=regions;this.duck=duck;this.out=out;duckCurve=curve(regions,duck,total);start=c.optLong("start_ms");length=c.optLong("duration_ms",10000);speed=c.optDouble("speed",1);outputFrames=Math.round(length*RATE/(1000d*speed));}
        void observed(AudioDecoder.Info info){eos|=info.endOfStreamReached;decodedEndUs=Math.max(decodedEndUs,info.lastUs);}
        void pcm(ByteBuffer data,int rate,int channels,long time)throws Exception{
            lastRate=rate;long frame=0;while(data.remaining()>=channels*2){double l=0,r=0;for(int ch=0;ch<channels;ch++){double v=data.getShort()/32768d;if(channels==1){l=v;r=v;}else if(ch%2==0)l+=v/((channels+1)/2);else r+=v/(channels/2);}
                double t=(time/1000d+frame++*1000d/rate-start);if(t<0||t>length)continue;
                if(Double.isNaN(prevTime)){prevTime=t;prevL=l;prevR=r;}
                while(next<outputFrames){double target=next*1000d*speed/RATE;if(target>t+1e-7)break;
                    if(target<prevTime-1e-7||(t-prevTime>2500d/rate&&target>prevTime+1000d/rate&&target<t-1000d/rate)){emit(0,0);padded++;continue;}
                    double blend=t==prevTime?1:Math.max(0,Math.min(1,(target-prevTime)/(t-prevTime)));emit(prevL+(l-prevL)*blend,prevR+(r-prevR)*blend);
                }
                prevTime=t;prevL=l;prevR=r;
            }
        }
        void emit(double l,double r)throws Exception{
            long local=next*1000/RATE,timeline=c.getLong("timeline_ms")+local;
            if(timeline>=total){next=outputFrames;return;}
            if(lastGainMs!=local){cachedGain=StudioAudio.gain(c,local,Math.round(length/speed))*StudioAudio.gain(track,timeline,total);lastGainMs=local;}
            double gain=cachedGain;if(duckCurve!=null){int index=(int)Math.min(duckCurve.length-2,timeline/10);double fraction=(timeline%10)/10d;gain*=duckCurve[index]+(duckCurve[index+1]-duckCurve[index])*fraction;}
            l*=gain;r*=gain;out.add(next,l,r);energy+=l*l+r*r;next++;count++;
        }
        void finish()throws Exception{
            long interpolationFrames=Math.max(1,(long)Math.ceil(RATE/(Math.max(1,lastRate)*speed)));
            if(Double.isNaN(prevTime)||(!eos&&outputFrames-next>interpolationFrames+1))throw new IOException("SOURCE_AUDIO_TRUNCATED: source="+c.optString("path")+", requested_frames="+outputFrames+", rendered_frames="+next+", last_source_ms="+prevTime+", start_ms="+start+", length_ms="+length);
            while(next<outputFrames){double sourceTime=next*1000d*speed/RATE;if(sourceTime<prevTime+1000d/Math.max(1,lastRate)+1e-7)emit(prevL,prevR);else{emit(0,0);padded++;}}
        }
    }
    private static final class Accumulator {
        final RandomAccessFile file;final long limit,offset;final float[] block=new float[2048];final byte[] bytes=new byte[8192];long blockAt=-1;int size;
        Accumulator(RandomAccessFile f,long frames,long offset){file=f;limit=frames;this.offset=offset;}
        void add(long frame,double left,double right)throws IOException{
            long at=frame+offset;if(at>=limit)return;long base=at/1024*1024;
            if(base!=blockAt){flush();blockAt=base;size=(int)Math.min(1024,limit-base);file.seek(base*8);file.readFully(bytes,0,size*8);ByteBuffer buffer=ByteBuffer.wrap(bytes);for(int i=0;i<size*2;i++)block[i]=buffer.getFloat();}
            int slot=(int)(at-base)*2;block[slot]+=(float)left;block[slot+1]+=(float)right;
        }
        void flush()throws IOException{if(blockAt<0)return;file.seek(blockAt*8);ByteBuffer buffer=ByteBuffer.wrap(bytes);for(int i=0;i<size*2;i++)buffer.putFloat(block[i]);file.write(bytes,0,size*8);blockAt=-1;}
    }
    private static void header(OutputStream out,long frames)throws IOException{
        long bytes=frames*4;out.write("RIFF".getBytes("US-ASCII"));little(out,bytes+36,4);out.write("WAVEfmt ".getBytes("US-ASCII"));little(out,16,4);little(out,1,2);little(out,2,2);little(out,RATE,4);little(out,RATE*4,4);little(out,4,2);little(out,16,2);out.write("data".getBytes("US-ASCII"));little(out,bytes,4);
    }
    private static void little(OutputStream out,long value,int bytes)throws IOException{for(int i=0;i<bytes;i++)out.write((int)(value>>>(i*8))&255);}
}
