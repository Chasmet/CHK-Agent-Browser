"""Independent FFmpeg/Numpy checks of actual Android renders, not project flags."""
from pathlib import Path
import json, subprocess, sys
import numpy as np
from PIL import Image

root=Path(sys.argv[1])
def probe(path):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(path)]))
def pcm(path):
    raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-vn','-ac','1','-ar','48000','-f','f32le','-'])
    return np.frombuffer(raw,dtype='<f4').astype(np.float64)
def frame(path,at=4):
    p=probe(path);v=next(s for s in p['streams'] if s['codec_type']=='video');w,h=v['width'],v['height']
    raw=subprocess.check_output(['ffmpeg','-v','error','-ss',str(at),'-i',str(path),'-frames:v','1','-pix_fmt','rgb24','-f','rawvideo','-'])
    return np.frombuffer(raw,np.uint8).reshape(h,w,3)
def require(test,message):
    if not test:raise AssertionError(message)

project=json.loads((root/'audio-project.json').read_text())
audio=pcm(root/'audio-mix.mp4');voice=pcm(root/'voice-source.mp4');music=pcm(root/'music.wav');fx=pcm(root/'fx.wav')
n=min(len(audio),len(voice),len(music),len(fx));audio,voice,music,fx=[v[:n] for v in [audio,voice,music,fx]]
fft_size=1<<(2*n-1).bit_length();cross=np.fft.irfft(np.fft.rfft(audio,fft_size)*np.conj(np.fft.rfft(voice,fft_size)),fft_size)
lags=np.arange(-4800,4801);lag=int(lags[np.argmax(cross[lags%fft_size])]);require(abs(lag)<=960,f'Audio/video offset exceeds 20ms: {lag/48}ms')
if lag>=0:audio=audio[lag:];voice,music,fx=[v[:len(audio)] for v in [voice,music,fx]]
else:voice,music,fx=[v[-lag:] for v in [voice,music,fx]];audio=audio[:len(voice)]
def coefficients(start,end):
    a,b=int(start*48000),int(end*48000);x=np.column_stack([voice[a:b],music[a:b],fx[a:b]])
    return np.linalg.lstsq(x,audio[a:b],rcond=None)[0]
regions=project['audio_ducking']['voice_regions'];require(len(regions)>0,'No recognized human speech region')
region=max(regions,key=lambda r:r['end_ms']-r['start_ms']);center=(region['start_ms']+region['end_ms'])/2000
half=min(.075,(region['end_ms']-region['start_ms'])/2000-.01);require(half>.01,'Speech intervals too short to measure')
spoken=coefficients(center-half,center+half);before=coefficients(.35,.75);after=coefficients(7.35,7.75)
keys=project['audio'][0]['gain_keyframes'];base=float(np.interp(center*1000,[k['time_ms'] for k in keys],[k['volume'] for k in keys]))
require(.75<spoken[0]<1.25,f'Original voice gain missing/changed: {spoken[0]}')
require(.12<before[1]<.18,f'Music 15% not present before speech: {before[1]}')
require(.1<spoken[1]/base<.5,f'Music was not attenuated during recognized speech: {spoken[1]/base}')
require(.06<after[1]<.10,f'Music keyframe/restoration to 8% missing: {after[1]}')
require(.28<spoken[2]<.42,f'FX 35% missing or accidentally ducked: {spoken[2]}')
require(np.max(np.abs(audio))<.999,'Clipped exported audio')
limited=pcm(root/'limiter.wav');limiter=json.loads((root/'limiter.json').read_text());require(np.max(np.abs(limited))<.90,'PCM limiter ceiling failed');require(limiter['limited_frames']>0,'Limiter never engaged in the overload test')

crop=frame(root/'crop.mp4');meta=probe(root/'crop.mp4');require(abs(float(meta['format']['duration'])-8)<.1,'Crop changed the duration')
require(crop.shape[0]>crop.shape[1],'Portrait MP4 is not physically upright')
mask=np.max(crop,axis=2)>60;y,x=np.where(mask);require(len(x)>1000,'Crop contains no source image');bbox=(int(x.min()),int(y.min()),int(x.max()),int(y.max()))
x0,y0,x1,y1=bbox
colors=[]
for u,v in [(0.25,0.25),(0.75,0.25),(0.25,0.75),(0.75,0.75)]:
    px,py=int(x0+(x1-x0)*u),int(y0+(y1-y0)*v);colors.append(np.median(crop[py-3:py+4,px-3:px+4],axis=(0,1)))
expected=[np.array(v) for v in [(255,0,0),(0,0,255),(0,255,0),(255,255,0)]]
for index,(actual,wanted) in enumerate(zip(colors,expected)):require(np.max(np.abs(actual-wanted))<55,f'Wrong crop/flip/90-degree geometry in quadrant {index}: {actual}')
preview=np.asarray(Image.open(root/'crop-preview.png').convert('RGB').resize((crop.shape[1],crop.shape[0])))
difference=float(np.mean(np.abs(preview.astype(float)-crop.astype(float))));require(difference<12,f'Preview and final MP4 differ: mean RGB error {difference}')
tor=json.loads((root/'tor-network.json').read_text());require(tor['tor_diagnostic_verified'] and tor['onion_verified'],'Real external Tor/onion tests did not succeed')
report={'verified':True,'independent_tools':['FFmpeg decode/ffprobe','Numpy least squares and FFT','Pillow PNG decode'],
    'audio_offset_ms':lag/48,'voice_gain_during_speech':float(spoken[0]),'music_gain_before_speech':float(before[1]),
    'music_gain_during_speech':float(spoken[1]),'duck_ratio':float(spoken[1]/base),'music_gain_after_speech_and_keyframe':float(after[1]),
    'fx_gain_during_speech':float(spoken[2]),'export_sample_peak':float(np.max(np.abs(audio))),'limiter_peak':float(np.max(np.abs(limited))),
    'crop_duration_seconds':float(meta['format']['duration']),'crop_dimensions':[crop.shape[1],crop.shape[0]],'crop_quadrants':[c.tolist() for c in colors],
    'preview_export_mean_rgb_difference':difference,'tor':tor,'scope':'public fixtures on Android emulator; not owner phone or subjective listening; no integrated LUFS/lip-sync claim'}
(root/'independent-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
print('PASS independent MP4: original human voice, music ducking/automation, FX, limiter, sync, crop geometry and actual preview/export comparison')
