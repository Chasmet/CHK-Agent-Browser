package com.chk.agentbrowser;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded PCM16 windows from local WAV or Android-supported compressed audio/video. */
final class AudioDecoder {
    interface Sink{void pcm(ByteBuffer data,int rate,int channels,long timeUs)throws Exception;}
    static final class Info {int rate,channels;long frames,firstUs=-1,lastUs,durationUs=-1;}
    private AudioDecoder(){}
    static Info read(File source,long startMs,int seconds,Sink sink,AgentClient.ResultCallback active)throws Exception{
        if(startMs<0||startMs>31536000000L||seconds<1||seconds>180)throw new IOException("Fenêtre audio invalide (1 à 180 secondes).");
        long start=startMs*1000,end=start+seconds*1000000L;Info info=new Info();
        if(wav(source,start,end,sink,info,active))return checked(info);
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try{
            extractor.setDataSource(source.getAbsolutePath());int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}}
            if(track<0)throw new IOException("Aucune piste audio décodable.");
            info.durationUs=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):-1;
            if(info.durationUs>=0&&start>=info.durationUs)throw new IOException("Début situé après la fin de l'audio.");
            extractor.selectTrack(track);extractor.seekTo(start,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=2;
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
            boolean inputDone=false,done=false;long deadline=SystemClock.elapsedRealtime()+145000L;MediaCodec.BufferInfo buffer=new MediaCodec.BufferInfo();
            while(!done){
                if(active!=null&&!active.isActive())throw new IOException("Écoute interrompue.");
                if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Décodage audio trop long : réduire le passage.");
                if(!inputDone){int slot=codec.dequeueInputBuffer(10000);if(slot>=0){ByteBuffer input=codec.getInputBuffer(slot);if(input==null)throw new IOException("Buffer audio absent.");input.clear();int count=extractor.readSampleData(input,0);
                    if(count<0){codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                    else{codec.queueInputBuffer(slot,0,count,Math.max(0,extractor.getSampleTime()),0);extractor.advance();}}}
                int output=codec.dequeueOutputBuffer(buffer,10000);
                if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):2;}
                else if(output>=0){try{
                    if(buffer.size>0){ByteBuffer data=codec.getOutputBuffer(output);if(data==null||buffer.size>2*1024*1024)throw new IOException("Bloc PCM invalide ou trop grand.");data.position(buffer.offset);data.limit(buffer.offset+buffer.size);
                        ByteBuffer pcm=data.slice().order(ByteOrder.LITTLE_ENDIAN);
                        if(encoding==4){ByteBuffer converted=ByteBuffer.allocate(pcm.remaining()/2).order(ByteOrder.LITTLE_ENDIAN);while(pcm.remaining()>=4){float value=pcm.getFloat();if(Float.isNaN(value)||Float.isInfinite(value))throw new IOException("Échantillon audio non fini.");converted.putShort((short)Math.max(-32768,Math.min(32767,Math.round(value*32768f))));}converted.flip();pcm=converted;}
                        else if(encoding!=2)throw new IOException("Format PCM non pris en charge.");
                        emit(pcm,rate,channels,buffer.presentationTimeUs,start,end,sink,info);
                        if(buffer.presentationTimeUs+(long)buffer.size*1000000/(Math.max(1,rate)*Math.max(1,channels)*(encoding==4?4:2))>=end)done=true;
                    }
                    if((buffer.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)done=true;
                }finally{codec.releaseOutputBuffer(output,false);}}
            }
            return checked(info);
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}extractor.release();}
    }
    private static Info checked(Info info)throws IOException{if(info.frames==0)throw new IOException("Aucun échantillon audio dans ce passage.");return info;}
    private static void emit(ByteBuffer pcm,int rate,int channels,long time,long start,long end,Sink sink,Info info)throws Exception{
        if(rate<8000||rate>192000||channels<1||channels>8)throw new IOException("Fréquence ou canaux audio non pris en charge.");
        int frames=pcm.remaining()/(channels*2);long skip=Math.max(0,(long)Math.ceil((start-time)*rate/1000000d));long until=Math.min(frames,(long)Math.ceil((end-time)*rate/1000000d));
        if(until<=skip)return;int base=pcm.position();pcm.limit(base+(int)until*channels*2);pcm.position(base+(int)skip*channels*2);
        long from=time+skip*1000000L/rate;int count=(int)(until-skip);info.rate=rate;info.channels=channels;if(info.firstUs<0)info.firstUs=from;info.lastUs=Math.min(end,from+count*1000000L/rate);info.frames+=count;
        sink.pcm(pcm.slice().order(ByteOrder.LITTLE_ENDIAN),rate,channels,from);
    }
    private static boolean wav(File file,long start,long end,Sink sink,Info info,AgentClient.ResultCallback active)throws Exception{
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            if(in.length()<12)return false;byte[] tag=new byte[4];in.readFully(tag);String container=new String(tag,java.nio.charset.StandardCharsets.US_ASCII);in.skipBytes(4);in.readFully(tag);
            if(!container.equals("RIFF")||!new String(tag,java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE"))return false;
            int rate=0,channels=0,bits=0,type=0;long offset=-1,length=0;
            for(int part=0;part<128&&in.getFilePointer()+8<=in.length();part++){
                in.readFully(tag);String chunk=new String(tag,java.nio.charset.StandardCharsets.US_ASCII);long size=((long)Integer.reverseBytes(in.readInt()))&0xffffffffL,next=in.getFilePointer()+size+(size&1);
                if(next<0||next>in.length()+1)throw new IOException("WAV tronqué ou taille invalide.");
                if(chunk.equals("fmt ")){if(size<16)throw new IOException("Format WAV invalide.");type=Short.reverseBytes(in.readShort())&0xffff;channels=Short.reverseBytes(in.readShort())&0xffff;rate=Integer.reverseBytes(in.readInt());in.skipBytes(6);bits=Short.reverseBytes(in.readShort())&0xffff;if(type==65534&&size>=40){in.skipBytes(8);type=Short.reverseBytes(in.readShort())&0xffff;}}
                if(chunk.equals("data")){offset=in.getFilePointer();length=size;if(rate>0)break;}
                in.seek(next);
            }
            if(!(type==1&&(bits==8||bits==16||bits==24||bits==32)||type==3&&bits==32))return false;
            if(offset<0||channels<1||channels>8||rate<8000||rate>192000)throw new IOException("PCM WAV non pris en charge.");
            int sampleBytes=bits/8;long frameBytes=channels*(long)sampleBytes,total=length/frameBytes;info.durationUs=total*1000000L/rate;
            long first=start*rate/1000000L,last=Math.min(total,(long)Math.ceil(end*rate/1000000d));
            if(first>=last)throw new IOException("Passage hors de l'audio.");in.seek(offset+first*frameBytes);byte[] buffer=new byte[65536];int block=(buffer.length/(int)frameBytes)*(int)frameBytes;
            for(long frame=first;frame<last;){if(active!=null&&!active.isActive())throw new IOException("Écoute interrompue.");int count=(int)Math.min(block,(last-frame)*frameBytes);in.readFully(buffer,0,count);
                ByteBuffer pcm=ByteBuffer.wrap(buffer,0,count).order(ByteOrder.LITTLE_ENDIAN);
                if(type!=1||bits!=16){ByteBuffer converted=ByteBuffer.allocate(count/sampleBytes*2).order(ByteOrder.LITTLE_ENDIAN);while(pcm.remaining()>=sampleBytes){int value;
                    if(type==3){float f=pcm.getFloat();if(Float.isNaN(f)||Float.isInfinite(f))throw new IOException("WAV contenant un échantillon non fini.");value=Math.max(-32768,Math.min(32767,Math.round(f*32768f)));}
                    else if(bits==8)value=((pcm.get()&255)-128)<<8;
                    else if(bits==24){int n=(pcm.get()&255)|((pcm.get()&255)<<8)|(pcm.get()<<16);value=n>>8;}
                    else value=pcm.getInt()>>16;
                    converted.putShort((short)value);
                }converted.flip();pcm=converted;}
                emit(pcm,rate,channels,frame*1000000L/rate,start,end,sink,info);frame+=count/frameBytes;}
            return true;
        }
    }
}
