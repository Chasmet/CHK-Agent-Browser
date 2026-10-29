package com.chk.agentbrowser;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded disk-backed float summation, stereo linked peak limiter, PCM16 WAV. */
final class StudioPcmMix {
    interface Progress{void update(int value);}
    static final int RATE=48000,BLOCK=4096;
    private StudioPcmMix(){}
    static synchronized File render(Context app,JSONObject project,AgentClient.ResultCallback active,Progress progress)throws Exception{
        StudioMix.validate(project);JSONObject ducking=project.optJSONObject("audio_ducking");if(ducking!=null&&ducking.optBoolean("enabled")&&ducking.optString("detector").equals("vosk_local_word_timestamps")&&!ducking.optString("voice_timeline_signature").equals(StudioMix.voiceSignature(app,project)))throw new IOException("Les sources vocales ou leurs positions ont changé : relancer l’analyse du ducking");long duration=StudioEdits.total(project),frames=Math.round(duration*48d);if(frames<=0||duration>1200000)throw new IOException("Durée de mixage invalide");
        WorkspaceStore store=new WorkspaceStore(app);List<StudioMix.Source> sources=StudioMix.sources(project);StringBuilder identity=new StringBuilder(project.toString());
        for(StudioMix.Source s:sources){File f=store.file(s.clip.getString("path"));identity.append(f.getPath()).append(':').append(f.length()).append(':').append(f.lastModified());}
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:digest)key.append(String.format(Locale.ROOT,"%02x",b&255));
        File result=new File(app.getCacheDir(),"studio-mix-"+key+".wav");if(result.isFile()&&result.length()==44+frames*4&&new File(result+".json").isFile())return result;
        if(app.getCacheDir().getUsableSpace()<frames*12+64L*1024*1024)throw new IOException("Stockage insuffisant pour le mixage PCM temporaire");
        File floats=File.createTempFile("studio-sum-",".pcm",app.getCacheDir()),partial=File.createTempFile("studio-mix-",".partial",app.getCacheDir());
        java.util.Map<String,Boolean> audible=new java.util.HashMap<>();for(StudioMix.Source s:sources)audible.put(s.clip.optString("clip_id"),StudioMix.audible(project,s));float[] ducks=duckEnvelope(project,duration,audible);JSONObject proof=new JSONObject();JSONArray receipts=new JSONArray();
        try(RandomAccessFile sum=new RandomAccessFile(floats,"rw")){
            sum.setLength(frames*8);
            for(int index=0;index<sources.size();index++){
                check(active);StudioMix.Source source=sources.get(index);JSONObject receipt=new JSONObject().put("clip_id",source.clip.optString("clip_id")).put("track_id",source.track).put("offset_ms",source.offset).put("duration_ms",source.length);receipts.put(receipt);
                if(!StudioMix.audible(project,source)){receipt.put("included",false).put("reason","mute_or_solo");continue;}
                File input=store.file(source.clip.getString("path"));long originalSize=input.length(),originalModified=input.lastModified();JSONObject info=MediaInspection.info(app,source.clip.getString("path"));boolean audio=false;JSONArray tracks=info.optJSONArray("tracks");if(tracks!=null)for(int i=0;i<tracks.length();i++)audio|=tracks.getJSONObject(i).optString("mime_type").startsWith("audio/");
                if(!audio&&source.original){receipt.put("included",false).put("reason","source_without_audio");continue;}
                float[] gains=gains(project,source,ducks);Accumulator cache=new Accumulator(sum,frames);Resampler resampler=new Resampler(cache,source,gains);
                long start=source.clip.optLong("start_ms"),length=source.clip.optLong("duration_ms");
                for(long part=0;part<length;part+=180000){check(active);final long stop=(start+length)*1000;
                    AudioDecoder.read(input,start+part,(int)Math.min(180,(length-part+999)/1000),(data,rate,channels,time)->{
                        long n=0;while(data.remaining()>=channels*2){long us=time+n*1000000L/rate;n++;float left=0,right=0;int nl=0,nr=0;
                            for(int c=0;c<channels;c++){float v=data.getShort()/32768f;if(channels==1){left=right=v;nl=nr=1;}else if((c&1)==0){left+=v;nl++;}else{right+=v;nr++;}}
                            if(us<stop)resampler.sample(us,left/Math.max(1,nl),right/Math.max(1,nr));
                        }
                    },active);
                    if(progress!=null)progress.update((int)((index+(part+Math.min(180000,length-part))/(double)Math.max(1,length))*75/Math.max(1,sources.size())));
                }
                if(input.length()!=originalSize||input.lastModified()!=originalModified)throw new IOException("La source a changé pendant le mixage");
                cache.flush();receipt.put("included",true).put("source_size",input.length()).put("source_modified",input.lastModified()).put("mixed_frames",resampler.written);
            }
            sum.seek(0);double ceiling=Math.pow(10,project.optDouble("limiter_ceiling_db",-1)/20),gain=1,release=1-Math.exp(-1d/(RATE*.05)),peakBefore=0,peakAfter=0;long limited=0;
            try(FileOutputStream output=new FileOutputStream(partial)){wavHeader(output,frames);byte[] bytes=new byte[BLOCK*8],encoded=new byte[BLOCK*4];
                for(long cursor=0;cursor<frames;){check(active);int count=(int)Math.min(BLOCK,frames-cursor);sum.readFully(bytes,0,count*8);ByteBuffer pcm=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),out=ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<count;i++){double l=pcm.getFloat(),r=pcm.getFloat(),peak=Math.max(Math.abs(l),Math.abs(r));peakBefore=Math.max(peakBefore,peak);gain=Math.min(peak>0?ceiling/peak:1,Math.min(1,gain+(1-gain)*release));if(gain<.999999)limited++;
                        l*=gain;r*=gain;peakAfter=Math.max(peakAfter,Math.max(Math.abs(l),Math.abs(r)));out.putShort((short)Math.round(Math.max(-1,Math.min(1,l))*32767)).putShort((short)Math.round(Math.max(-1,Math.min(1,r))*32767));}
                    output.write(encoded,0,count*4);cursor+=count;if(progress!=null)progress.update(75+(int)(cursor*25/frames));
                }
                output.getFD().sync();
            }
            proof.put("sample_rate",RATE).put("channels",2).put("duration_ms",duration).put("sources",receipts).put("limiter","stereo_linked_instant_attack_50ms_release").put("limited_frames",limited).put("peak_before_dbfs",level(peakBefore)).put("peak_after_dbfs",level(peakAfter)).put("speed_audio","resampling_with_pitch_change").put("engine","disk_float_mix_v2");
            if(!partial.renameTo(result))throw new IOException("Finalisation du mixage impossible");StudioAutomation.write(new File(result+".json"),proof);return result;
        }finally{floats.delete();partial.delete();}
    }
    static Object level(double value){return value==0?"silence":20*Math.log10(value);}
    private static void check(AgentClient.ResultCallback cb)throws IOException{if(cb!=null&&!cb.isActive())throw new IOException("Mixage annulé ou commande expirée");}
    private static float[] gains(JSONObject project,StudioMix.Source source,float[] ducks)throws Exception{
        int count=(int)(source.length/10)+2;float[] out=new float[count];JSONObject track=StudioMix.track(project,source.track);boolean music=!source.original&&track.optString("role","music").equals("music");
        for(int i=0;i<count;i++){double local=Math.min(source.length,i*10d);double g=StudioMix.envelope(source.clip,local,source.length)*StudioMix.envelope(track,source.offset+local,StudioEdits.total(project));if(music)g*=ducks[Math.min(ducks.length-1,(int)((source.offset+local)/10))];out[i]=(float)g;}return out;
    }
    private static float[] duckEnvelope(JSONObject project,long duration,java.util.Map<String,Boolean> audible)throws Exception{
        float[] out=new float[(int)(duration/10)+2];Arrays.fill(out,1);JSONObject d=project.optJSONObject("audio_ducking");if(d==null||!d.optBoolean("enabled"))return out;
        JSONArray regions=d.optJSONArray("voice_regions");if(regions==null)throw new IOException("Ducking activé sans analyse vocale : analyser d'abord");double attack=d.optDouble("attack_ms",80),release=d.optDouble("release_ms",350),reduction=Math.pow(10,d.optDouble("reduction_db",-12)/20);
        for(int i=0;i<regions.length();i++){JSONObject r=regions.getJSONObject(i);if(r.has("source_clip_id")&&!Boolean.TRUE.equals(audible.get(r.optString("source_clip_id"))))continue;if(r.optDouble("rms_db",0)<d.optDouble("threshold_db",-40))continue;double start=r.getLong("start_ms"),end=r.getLong("end_ms");for(int k=Math.max(0,(int)((start-attack)/10));k<out.length&&k*10<end+release;k++){double ms=k*10,amount=ms<start?1-(start-ms)/attack:ms>end?1-(ms-end)/release:1;out[k]=Math.min(out[k],(float)(1-Math.max(0,amount)*(1-reduction)));}}return out;
    }
    private static final class Resampler {
        final Accumulator out;final StudioMix.Source source;final float[] gains;long next=-1,written;double previousTime;float previousL,previousR;
        Resampler(Accumulator a,StudioMix.Source s,float[] g){out=a;source=s;gains=g;}
        void sample(long absoluteUs,float left,float right)throws Exception{
            double position=(absoluteUs-source.clip.optLong("start_ms")*1000d)/source.speed*RATE/1000000d;
            if(position<0)return;if(next<0){next=(long)Math.ceil(position);previousTime=position;previousL=left;previousR=right;}
            while(next<=position&&next<Math.round(source.length*48d)){double t=position>previousTime?(next-previousTime)/(position-previousTime):1;t=Math.max(0,Math.min(1,t));double at=next/480d;int index=Math.min(gains.length-2,(int)at);float gain=(float)(gains[index]+(gains[index+1]-gains[index])*(at-index));
                out.add(Math.round(source.offset*48d)+next,(float)((previousL+(left-previousL)*t)*gain),(float)((previousR+(right-previousR)*t)*gain));next++;written++;}
            previousTime=position;previousL=left;previousR=right;
        }
    }
    private static final class Accumulator {
        final RandomAccessFile file;final long frames;long base=-1;int count;final byte[] bytes=new byte[BLOCK*8];final ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Accumulator(RandomAccessFile f,long n){file=f;frames=n;}
        void add(long frame,float l,float r)throws IOException{if(frame<0||frame>=frames)return;long wanted=frame/BLOCK*BLOCK;if(wanted!=base){flush();base=wanted;count=(int)Math.min(BLOCK,frames-base);file.seek(base*8);file.readFully(bytes,0,count*8);}int offset=(int)(frame-base)*8;buffer.putFloat(offset,buffer.getFloat(offset)+l);buffer.putFloat(offset+4,buffer.getFloat(offset+4)+r);}
        void flush()throws IOException{if(base<0)return;file.seek(base*8);file.write(bytes,0,count*8);base=-1;}
    }
    private static void wavHeader(OutputStream out,long frames)throws IOException{ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)(frames*4+36)).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)2).putInt(RATE).putInt(RATE*4).putShort((short)4).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)(frames*4));out.write(b.array());}
}
