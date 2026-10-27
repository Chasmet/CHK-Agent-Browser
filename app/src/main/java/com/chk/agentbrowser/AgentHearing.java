package com.chk.agentbrowser;

import android.content.Context;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;

/** Actual audio excerpts and measured signal, distinct from speech-to-text. No microphone capture. */
final class AgentHearing {
    private static final java.util.concurrent.ExecutorService IO=Executors.newFixedThreadPool(2);
    private AgentHearing(){}
    static JSONObject capabilities()throws Exception{
        return new JSONObject().put("version",1).put("operations",new JSONArray(java.util.Arrays.asList("capabilities","status","media","analyze","listen","transcribe")))
            .put("scope","CHK files and media source discovery in CHK tabs").put("listen_max_seconds",5).put("analysis_max_seconds",120)
            .put("transcribe_languages",new JSONArray(java.util.Arrays.asList("fr","en"))).put("audio_format","audio/wav; PCM16 mono 16000 Hz")
            .put("audio_transport","audio_base64 : forward as MCP AudioContent audio/wav, never print Base64 as text")
            .put("speech_engine","vosk_local; model installed over HTTPS on first use; no audio upload")
            .put("microphone_recording",false).put("speaker_identification",false).put("phoneme_lip_sync",false);
    }
    static void execute(Context app,JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            String op=p.optString("operation","status");
            if(op.equals("capabilities")||op.equals("status")){cb.finish(true,capabilities().put("speech_models",MediaTranscriber.modelStatus(app)).toString());return;}
            if(op.equals("media")){
                JSONObject target=new JSONObject(p.toString()).put("target","web");WebView web=AgentControl.resolveWeb(app,target,cb);AgentVision.target(target,web);String url=web.getUrl();long version=BrowserWebState.documentVersion(web);
                web.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('audio,video')).slice(0,24).map(function(m){return {kind:m.tagName.toLowerCase(),url:m.currentSrc||m.src||'',time_seconds:m.currentTime,duration_seconds:isFinite(m.duration)?m.duration:null,paused:m.paused,muted:m.muted,volume:m.volume,ready:m.readyState};}))",raw->{try{
                    if(!cb.isActive())return;if(!BrowserWebState.alive(web)||version!=BrowserWebState.documentVersion(web)||url==null||!url.equals(web.getUrl()))throw new IOException("Onglet modifié pendant la lecture des médias.");
                    cb.finish(true,new JSONObject().put("tab_id",BrowserTabIds.id(web)).put("url",url).put("media",new JSONArray(new JSONArray("["+raw+"]").getString(0))).put("audio_captured",false).put("next_step","Télécharger une source HTTP/HTTPS autorisée vers CHK puis listen/analyze. blob et DRM ne sont pas extraits automatiquement.").toString());
                }catch(Exception e){cb.finish(false,message(e));}});return;
            }
            if(!java.util.Arrays.asList("listen","analyze","transcribe").contains(op))throw new IOException("Opération d'écoute inconnue.");
            if(!AgentClient.get(app).isPreviewAllowed())throw new IOException("Active le partage des aperçus audio dans Réglages.");
            java.util.concurrent.ExecutorService executor=op.equals("transcribe")?MediaTranscriber.IO:IO;
            executor.execute(()->{try{
                if(!cb.isActive())return;JSONObject result=op.equals("transcribe")?MediaTranscriber.transcribe(app,p.getString("path"),p.optString("language","fr"),p.optInt("max_seconds",120),p.optLong("start_ms",0),cb):process(app,p,op.equals("listen"),cb);
                WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(true,result.toString());});
            }catch(Exception e){WorkspaceCommands.UI.post(()->{if(cb.isActive())cb.finish(false,message(e));});}});
        }catch(Exception e){cb.finish(false,message(e));}
    }
    static JSONObject process(Context app,JSONObject p,boolean listen,AgentClient.ResultCallback active)throws Exception{
        if(!AgentClient.get(app).isPreviewAllowed())throw new IOException("Partage des aperçus audio désactivé.");
        String path=p.getString("path");File file=new WorkspaceStore(app).file(path);if(!file.isFile())throw new IOException("Fichier audio absent dans CHK.");TransferLimits.checkSize(file.length());
        long size=file.length(),modified=file.lastModified(),start=p.optLong("start_ms",0);
        int seconds=p.optInt("max_seconds",listen?5:30);if(seconds<1||seconds>(listen?5:120))throw new IOException(listen?"Un extrait d'écoute dure de 1 à 5 secondes.":"Analyse de 1 à 120 secondes ; choisir un autre passage pour continuer.");
        double threshold=p.optDouble("silence_dbfs",-45);if(Double.isNaN(threshold)||threshold<-90||threshold>-15)throw new IOException("Seuil de silence attendu entre -90 et -15 dBFS.");
        Metrics metrics=new Metrics(start,seconds,threshold,listen);AudioDecoder.Info decoded=AudioDecoder.read(file,start,seconds,metrics::pcm,active);
        if(size!=file.length()||modified!=file.lastModified())throw new IOException("Le fichier a changé pendant l'écoute : résultat non confirmé.");
        JSONObject result=metrics.result(decoded).put("path",path).put("source_size",size).put("source_updated",modified).put("audio_uploaded",listen)
            .put("source_file_uploaded",false).put("audio_export_scope",listen?"requested_excerpt_only":"none")
            .put("transcription_performed",false).put("speaker_identification",false).put("lufs_measured",false).put("phoneme_alignment",false);
        if(listen){byte[] wav=wav(metrics.samples.toByteArray());result.put("audio_base64",android.util.Base64.encodeToString(wav,android.util.Base64.NO_WRAP)).put("mime_type","audio/wav")
            .put("audio_sample_rate",16000).put("audio_channels",1).put("audio_bytes",wav.length).put("sha256",hex(java.security.MessageDigest.getInstance("SHA-256").digest(wav))).put("audio_available",true);}
        return result;
    }
    private static final class Metrics {
        final long start;final int seconds;final double silence;final ByteArrayOutputStream samples;
        final double[] binsSum;final long[] binsCount;final double[] sums=new double[8],peaks=new double[8];final long[] clipped=new long[8];
        long frames;double nextSample=-1;
        Metrics(long start,int seconds,double silence,boolean listen){this.start=start;this.seconds=seconds;this.silence=silence;samples=listen?new ByteArrayOutputStream():null;binsSum=new double[seconds*10+1];binsCount=new long[binsSum.length];}
        void pcm(ByteBuffer data,int rate,int channels,long time)throws Exception{
            data.order(ByteOrder.LITTLE_ENDIAN);int index=0;
            while(data.remaining()>=channels*2){double square=0;int mono=0;for(int c=0;c<channels;c++){short raw=data.getShort();double value=raw/32768d;mono+=raw;sums[c]+=value*value;peaks[c]=Math.max(peaks[c],Math.abs(value));if(Math.abs((int)raw)>=32700)clipped[c]++;square+=value*value;}
                double timestamp=time+index*1000000d/rate;int bin=(int)((timestamp-start*1000d)/100000d);if(bin>=0&&bin<binsSum.length){binsSum[bin]+=square/channels;binsCount[bin]++;}
                if(samples!=null){if(nextSample<0)nextSample=timestamp;while(timestamp+0.01>=nextSample){if(samples.size()+2>160000)throw new IOException("Extrait audio supérieur à 5 secondes.");int value=mono/channels;samples.write(value&255);samples.write((value>>>8)&255);nextSample+=1000000d/16000;}}
                frames++;index++;
            }
        }
        JSONObject result(AudioDecoder.Info info)throws Exception{
            JSONArray channels=new JSONArray(),envelope=new JSONArray(),silences=new JSONArray();boolean saturation=false;double total=0,peak=0;long silentBins=0;int began=-1;
            for(int c=0;c<info.channels;c++){double rms=Math.sqrt(sums[c]/Math.max(1,frames));total+=sums[c];peak=Math.max(peak,peaks[c]);saturation|=clipped[c]>0;channels.put(new JSONObject().put("channel",c).put("rms_dbfs",db(rms)).put("peak_dbfs",db(peaks[c])).put("near_full_scale_samples",clipped[c]));}
            for(int i=0;i<binsCount.length;i++){if(binsCount[i]==0)continue;double rms=Math.sqrt(binsSum[i]/binsCount[i]);double level=db(rms);boolean silent=level<silence;envelope.put(new JSONObject().put("start_ms",start+i*100L).put("rms_dbfs",level));
                if(silent){silentBins++;if(began<0)began=i;}else if(began>=0){silences.put(new JSONObject().put("start_ms",start+began*100L).put("end_ms",start+i*100L));began=-1;}}
            if(began>=0)silences.put(new JSONObject().put("start_ms",start+began*100L).put("end_ms",info.lastUs/1000));
            return new JSONObject().put("format","measured_pcm_audio").put("source_start_ms",info.firstUs/1000).put("source_end_ms",info.lastUs/1000).put("source_duration_ms",info.durationUs<0?JSONObject.NULL:info.durationUs/1000)
                .put("analyzed_seconds",(info.lastUs-info.firstUs)/1000000d).put("sample_rate",info.rate).put("channels",info.channels).put("frames",frames)
                .put("rms_dbfs",db(Math.sqrt(total/Math.max(1,frames*info.channels)))).put("peak_dbfs",db(peak)).put("channel_levels",channels)
                .put("clipping_suspected",saturation).put("clipping_note","Échantillons proches du maximum numérique ; ne prouve pas seul une distorsion audible.")
                .put("silence_threshold_dbfs",silence).put("silence_fraction",envelope.length()==0?0:silentBins/(double)envelope.length()).put("silences",silences).put("envelope_100ms",envelope);
        }
    }
    private static double db(double amplitude){return amplitude<=0?-120:Math.max(-120,20*Math.log10(amplitude));}
    static byte[] wav(byte[] pcm)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream(pcm.length+44);out.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));little(out,pcm.length+36,4);out.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));little(out,16,4);little(out,1,2);little(out,1,2);little(out,16000,4);little(out,32000,4);little(out,2,2);little(out,16,2);out.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));little(out,pcm.length,4);out.write(pcm);return out.toByteArray();
    }
    private static void little(OutputStream out,int n,int bytes)throws IOException{for(int i=0;i<bytes;i++)out.write((n>>>(i*8))&255);}
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    private static String message(Exception e){return e.getMessage()==null?"Écoute indisponible.":e.getMessage();}
}
