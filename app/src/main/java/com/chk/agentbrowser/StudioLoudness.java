package com.chk.agentbrowser;

import java.util.ArrayList;

/** Mono/stereo integrated loudness: K weighting, 400 ms blocks every 100 ms,
 * -70 LUFS absolute and -10 LU relative gates. No true-peak claim.
 * Filter design follows the published BS.1770 design used by libebur128.
 */
final class StudioLoudness {
    final int rate,channels;private final Biquad[] shelf,highpass;
    private final double[] ring;private final int hop;private int cursor;private long frames;private double sum;
    private final ArrayList<Double> blocks=new ArrayList<>();
    StudioLoudness(int rate,int channels){
        if(channels<1||channels>2||rate<8000||rate>192000)throw new IllegalArgumentException("LUFS mono/stéréo uniquement");
        this.rate=rate;this.channels=channels;ring=new double[(int)Math.round(rate*.4)];hop=(int)Math.round(rate*.1);shelf=new Biquad[channels];highpass=new Biquad[channels];
        double k=Math.tan(Math.PI*1681.974450955533/rate),vh=Math.pow(10,3.999843853973347/20),vb=Math.pow(vh,.4996667741545416),a0=1+k/.7071752369554196+k*k;
        double b0=(vh+vb*k/.7071752369554196+k*k)/a0,b1=2*(k*k-vh)/a0,b2=(vh-vb*k/.7071752369554196+k*k)/a0,a1=2*(k*k-1)/a0,a2=(1-k/.7071752369554196+k*k)/a0;
        k=Math.tan(Math.PI*38.13547087602444/rate);double h0=1+k/.5003270373238773+k*k,h1=2*(k*k-1)/h0,h2=(1-k/.5003270373238773+k*k)/h0;
        for(int c=0;c<channels;c++){shelf[c]=new Biquad(b0,b1,b2,a1,a2);highpass[c]=new Biquad(1,-2,1,h1,h2);}
    }
    double channel(int channel,double sample){double value=highpass[channel].filter(shelf[channel].filter(sample));return value*value;}
    void frame(double weightedEnergy){sum+=weightedEnergy-ring[cursor];ring[cursor]=weightedEnergy;cursor=(cursor+1)%ring.length;frames++;if(frames>=ring.length&&(frames-ring.length)%hop==0)blocks.add(Math.max(0,sum/ring.length));}
    Object integrated(){
        double absolute=Math.pow(10,(-70+.691)/10),sum=0;int count=0;
        for(double energy:blocks)if(energy>=absolute){sum+=energy;count++;}
        if(count==0)return "-Infinity";double gate=Math.max(absolute,sum/count*.1);sum=0;count=0;
        for(double energy:blocks)if(energy>=gate){sum+=energy;count++;}
        return count==0?"-Infinity":-.691+10*Math.log10(sum/count);
    }
    boolean sufficient(){return !blocks.isEmpty();}
    private static final class Biquad {
        final double b0,b1,b2,a1,a2;double z1,z2;
        Biquad(double b0,double b1,double b2,double a1,double a2){this.b0=b0;this.b1=b1;this.b2=b2;this.a1=a1;this.a2=a2;}
        double filter(double x){double y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}
    }
}
