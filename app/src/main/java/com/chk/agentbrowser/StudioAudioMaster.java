package com.chk.agentbrowser;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Final PCM peak guard after Media3's sequence mixer, not on each source.
 * Ceiling -1 dBFS, instantaneous transient safety with release smoothing.
 * A hard ceiling never claims to repair clipping already present in sources.
 */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class StudioAudioMaster extends BaseAudioProcessor {
    private static final float CEILING=(float)Math.pow(10,-1.0/20.0);
    private int rate,channels;
    private float gain=1;
    @Override protected AudioFormat onConfigure(AudioFormat format)throws AudioProcessor.UnhandledAudioFormatException{
        if(format.encoding!=C.ENCODING_PCM_16BIT||format.channelCount<1||format.channelCount>8)
            throw new AudioProcessor.UnhandledAudioFormatException(format);
        rate=format.sampleRate;channels=format.channelCount;return format;
    }
    @Override public void queueInput(ByteBuffer input){
        input.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output=replaceOutputBuffer(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        int stride=channels*2;
        int count=input.remaining()/stride;
        final float release=(float)Math.exp(-1.0/(Math.max(1,rate)*.09));
        for(int frame=0;frame<count;frame++){
            int pos=input.position();int peak=0;
            for(int c=0;c<channels;c++){int value=input.getShort();peak=Math.max(peak,Math.abs(value));}
            float wanted=peak==0?1:Math.min(1,CEILING*32767/peak);
            gain=wanted<gain?wanted:wanted+(gain-wanted)*release;
            input.position(pos);
            for(int c=0;c<channels;c++){
                float value=input.getShort()*gain;
                // A sample-accurate ceiling protects transient peaks even
                // before the smoothed gain has settled.
                int clipped=Math.max(-Math.round(CEILING*32768),Math.min(Math.round(CEILING*32767),Math.round(value)));
                output.putShort((short)clipped);
            }
        }
        while(input.hasRemaining())output.put(input.get());
        output.flip();
    }
    @Override protected void onFlush(){gain=1;}
    @Override protected void onReset(){gain=1;}
}