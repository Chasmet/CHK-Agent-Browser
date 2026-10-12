package com.chk.agentbrowser;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Speech-to-text on the phone: MediaExtractor/MediaCodec -> PCM16 mono 16 kHz -> Vosk.
 * No recording, screen audio capture, server API, or uploads of user recordings.
 * First use installs the small, Apache-2.0 Vosk language model over HTTPS.
 */
final class MediaTranscriber {
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final String FR="vosk-model-small-fr-0.22";
    private static final String EN="vosk-model-small-en-us-0.15";
    private static final long MAX_DOWNLOAD=65L*1024*1024;
    private static final long MAX_UNPACKED=300L*1024*1024;
    private static final int OUTPUT_RATE=16000;
    private MediaTranscriber(){}

    static JSONObject transcribe(Context app,String path,String language,int maxSeconds)throws Exception{
        String lang=language==null?"fr":language.trim().toLowerCase(Locale.ROOT);
        if(!lang.equals("fr")&&!lang.equals("en"))throw new IOException("Langue disponible: fr ou en.");
        int seconds=Math.max(1,Math.min(180,maxSeconds));
        if(!AgentClient.get(app).isPreviewAllowed())throw new IOException("Active le partage des aperçus audio dans les réglages.");
        File media=new WorkspaceStore(app).file(path);
        if(!media.isFile())throw new IOException("Média absent dans Fichiers CHK.");
        if(media.length()>WorkspaceStore.MAX_IMPORT)throw new IOException("Média trop volumineux.");
        File modelDir=ensureModel(app,lang);
        long start=SystemClock.elapsedRealtime();
        String transcript=decode(media,modelDir,seconds);
        return new JSONObject().put("path",path).put("format","speech_to_text").put("language",lang)
            .put("text",transcript).put("max_seconds",seconds).put("engine","vosk_local")
            .put("model",lang.equals("fr")?FR:EN).put("audio_uploaded",false)
            .put("elapsed_ms",SystemClock.elapsedRealtime()-start)
            .put("warning",transcript.isEmpty()?"Parole non détectée ou non intelligible.":"Transcription automatique à vérifier.");
    }
    static JSONObject modelStatus(Context app)throws Exception{
        File base=new File(app.getFilesDir(),"speech_models");
        return new JSONObject().put("model_fr_ready",ready(new File(base,FR)))
            .put("model_en_ready",ready(new File(base,EN)))
            .put("model_fr_download_bytes_approx",41000000)
            .put("model_en_download_bytes_approx",40000000)
            .put("offline_after_first_install",true).put("no_audio_upload",true);
    }
    private static boolean ready(File folder){
        return folder.isDirectory()&&new File(folder,"am").isDirectory()
            &&new File(folder,"conf").isDirectory()&&new File(folder,"graph").isDirectory();
    }
    private static File ensureModel(Context app,String lang)throws Exception{
        String name=lang.equals("fr")?FR:EN;
        File parent=new File(app.getFilesDir(),"speech_models");
        if(!parent.exists()&&!parent.mkdirs())throw new IOException("Stockage des modèles indisponible.");
        File target=new File(parent,name);
        if(ready(target))return target;
        if(target.exists())throw new IOException("Modèle incomplet; supprimer uniquement le dossier du modèle et réessayer.");
        if(parent.getUsableSpace()<340L*1024*1024)throw new IOException("Prévoir 340 Mo disponibles pour installer le modèle vocal.");
        File zip=new File(parent,name+".download-"+UUID.randomUUID()+".zip");
        File staging=new File(parent,name+".install-"+UUID.randomUUID());
        if(!staging.mkdir())throw new IOException("Préparation du modèle impossible.");
        try{
            String url="https://alphacephei.com/vosk/models/"+name+".zip";
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(20000);c.setReadTimeout(40000);
            c.setRequestProperty("Accept","application/zip");
            long downloaded=0;
            try{
                if(c.getResponseCode()!=200)throw new IOException("Modèle vocal non téléchargeable: HTTP "+c.getResponseCode());
                if(c.getContentLengthLong()>MAX_DOWNLOAD)throw new IOException("Modèle vocal trop volumineux.");
                try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(zip)){
                    byte[] b=new byte[32768];int n;
                    while((n=in.read(b))!=-1){
                        downloaded+=n;
                        if(downloaded>MAX_DOWNLOAD)throw new IOException("Dépassement de la limite du téléchargement du modèle.");
                        out.write(b,0,n);
                    }
                }
            }finally{c.disconnect();}
            long expanded=0;int entries=0;
            try(ZipInputStream in=new ZipInputStream(new FileInputStream(zip))){
                ZipEntry entry;byte[] b=new byte[32768];
                while((entry=in.getNextEntry())!=null){
                    if(++entries>10000)throw new IOException("Modèle contenant trop d'entrées.");
                    String z=entry.getName();
                    if(!z.startsWith(name+"/"))throw new IOException("Archive de modèle inattendue.");
                    String relative=z.substring(name.length()+1);
                    if(relative.isEmpty())continue;
                    File dst=new File(staging,relative);
                    String dir=staging.getCanonicalPath()+File.separator;
                    if(!dst.getCanonicalPath().startsWith(dir))throw new IOException("Archive invalide.");
                    if(entry.isDirectory()){
                        if(!dst.isDirectory()&&!dst.mkdirs())throw new IOException("Impossible de créer un dossier du modèle.");
                    }else{
                        if(!dst.getParentFile().isDirectory()&&!dst.getParentFile().mkdirs())
                            throw new IOException("Arborescence du modèle invalide.");
                        try(FileOutputStream out=new FileOutputStream(dst)){
                            int n;
                            while((n=in.read(b))!=-1){
                                expanded+=n;
                                if(expanded>MAX_UNPACKED)throw new IOException("Modèle décompressé trop volumineux.");
                                out.write(b,0,n);
                            }
                        }
                    }
                    in.closeEntry();
                }
            }
            if(!ready(staging))throw new IOException("Le modèle téléchargé est incomplet.");
            if(!staging.renameTo(target))throw new IOException("Impossible d'installer le modèle vocal.");
            return target;
        }finally{
            zip.delete();
            if(staging.exists())deleteTree(staging);
        }
    }
    private static void deleteTree(File f){
        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File child:a)deleteTree(child);}
        f.delete();
    }
    private static String decode(File media,File modelDir,int seconds)throws Exception{
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec codec=null;
        try{
            extractor.setDataSource(media.getAbsolutePath());
            int index=-1;
            MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat candidate=extractor.getTrackFormat(i);
                if(candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")){index=i;format=candidate;break;}
            }
            if(index<0||format==null)throw new IOException("Aucune piste audio décodable.");
            extractor.selectTrack(index);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format,null,null,0);
            codec.start();
            try(Model model=new Model(modelDir.getAbsolutePath());Recognizer recognizer=new Recognizer(model,OUTPUT_RATE)){
                return pump(extractor,codec,recognizer,format,seconds);
            }
        }finally{
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}
            extractor.release();
        }
    }
    private static String pump(MediaExtractor extractor,MediaCodec codec,Recognizer recognizer,MediaFormat initial,int maxSeconds)throws Exception{
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        StringBuilder all=new StringBuilder();
        int rate=initial.containsKey(MediaFormat.KEY_SAMPLE_RATE)?initial.getInteger(MediaFormat.KEY_SAMPLE_RATE):OUTPUT_RATE;
        int channels=initial.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?initial.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
        PcmResampler resampler=new PcmResampler(rate,channels);
        boolean inputDone=false,outputDone=false;
        long processedSamples=0;
        long deadline=SystemClock.elapsedRealtime()+145000L;
        int idle=0;
        while(!outputDone&&SystemClock.elapsedRealtime()<deadline){
            if(!inputDone){
                int slot=codec.dequeueInputBuffer(10000);
                if(slot>=0){
                    ByteBuffer input=codec.getInputBuffer(slot);
                    if(input==null)throw new IOException("Buffer d'entrée audio indisponible.");
                    input.clear();
                    int size=extractor.readSampleData(input,0);
                    if(size<0){
                        codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone=true;
                    }else{
                        codec.queueInputBuffer(slot,0,size,Math.max(0,extractor.getSampleTime()),0);
                        extractor.advance();
                    }
                }
            }
            int output=codec.dequeueOutputBuffer(info,10000);
            if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                MediaFormat decoded=codec.getOutputFormat();
                int encoding=decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)?decoded.getInteger(MediaFormat.KEY_PCM_ENCODING):2;
                if(encoding!=2)throw new IOException("Codec PCM non 16 bits.");
                rate=decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                channels=decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                resampler=new PcmResampler(rate,channels);
            }else if(output>=0){
                idle=0;
                try{
                    if(info.size>0){
                        ByteBuffer data=codec.getOutputBuffer(output);
                        if(data==null)throw new IOException("Buffer audio manquant.");
                        data.position(info.offset);data.limit(info.offset+info.size);
                        byte[] converted=resampler.convert(data);
                        if(converted.length>0){
                            processedSamples+=converted.length/2;
                            if(recognizer.acceptWaveForm(converted,converted.length)){
                                String segment=new JSONObject(recognizer.getResult()).optString("text","");
                                if(!segment.isEmpty())all.append(segment).append(' ');
                            }
                        }
                        if(processedSamples>=maxSeconds*OUTPUT_RATE)outputDone=true;
                    }
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outputDone=true;
                }finally{codec.releaseOutputBuffer(output,false);}
            }else if(++idle>200)throw new IOException("Décodage audio bloqué.");
        }
        if(SystemClock.elapsedRealtime()>=deadline)throw new IOException("Transcription trop longue; réduire la durée.");
        String tail=new JSONObject(recognizer.getFinalResult()).optString("text","");
        if(!tail.isEmpty())all.append(tail);
        return all.toString().trim();
    }
    private static final class PcmResampler{
        final int rate,channels;
        long position=0;
        double next=0;
        PcmResampler(int rate,int channels)throws IOException{
            if(rate<8000||rate>192000||channels<1||channels>8)throw new IOException("Fréquence/canaux audio non pris en charge.");
            this.rate=rate;this.channels=channels;
        }
        byte[] convert(ByteBuffer input)throws IOException{
            input.order(ByteOrder.LITTLE_ENDIAN);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            while(input.remaining()>=channels*2){
                int total=0;
                for(int c=0;c<channels;c++)total+=input.getShort();
                int sample=Math.max(-32768,Math.min(32767,total/channels));
                while(position>=next){
                    out.write(sample&255);out.write((sample>>>8)&255);
                    next+=(double)rate/OUTPUT_RATE;
                }
                position++;
            }
            return out.toByteArray();
        }
    }
}
