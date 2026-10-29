"""Independent FFmpeg decode: identify both sources, bound levels, compare rendered crop pixels."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import re
import numpy as np

folder = Path(sys.argv[1])
def run(*args):
    return subprocess.check_output(args)
def metadata(name):
    return json.loads(run('ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(folder/name)))
def audio(name):
    return np.frombuffer(run('ffmpeg','-v','error','-i',str(folder/name),'-vn','-ac','1','-ar','48000','-f','f32le','-'), dtype='<f4')
def amplitude(x, freq):
    y=x[24000:96000].astype(np.float64)
    t=np.arange(len(y))/48000
    return 2*abs(np.sum(y*np.exp(-2j*np.pi*freq*t)))/len(y)
def frame(name):
    return np.frombuffer(run('ffmpeg','-v','error','-ss','1','-i',str(folder/name),'-frames:v','1','-vf','scale=240:270','-f','rawvideo','-pix_fmt','rgb24','-'),dtype=np.uint8).reshape(270,240,3)

mixed=audio('studio-mixed-proof.mp4')
voice=amplitude(mixed,440)
music=amplitude(mixed,880)
assert voice>.02, f'Original 440 Hz source lost: {voice}'
assert music>.02, f'Music 880 Hz source lost: {music}'
assert np.max(np.abs(mixed))<.9999, 'Full scale output sample'
meta=metadata('studio-mixed-proof.mp4')
assert abs(float(meta['format']['duration'])-3)<.15
crop=metadata('studio-crop-proof.mp4')
v=next(s for s in crop['streams'] if s['codec_type']=='video')
assert v['width']==480 and 500<v['height']<560, v
assert abs(float(crop['format']['duration'])-2)<.15
preview=frame('studio-crop-preview.mp4').astype(float)
final=frame('studio-crop-proof.mp4').astype(float)
error=float(np.mean(abs(preview-final)))
assert error<8, f'Preview/export pixel mismatch {error}'
# Top half of the portrait source contains red+green; crop must exclude blue+yellow.
assert np.mean(final[:,:,2])<25, 'Unexpected blue outside selected source rectangle'
assert np.max(np.mean(final[:,:,0],axis=1))>130, 'Red quadrant missing after transform'
assert np.max(np.mean(final[:,:,1],axis=1))>100, 'Green quadrant missing after transform'
# Regression on decoded human speech + the known independent music tone.
voice_source=audio('studio-human-source.mp4').astype(np.float64)
human_mix=audio('studio-human-mixed.mp4').astype(np.float64)
proof=json.loads((folder/'studio-human-proof.json').read_text())
regions=proof['voice_regions']['original']
assert len(regions)>3, 'No actual recognized speech intervals'
count=min(len(voice_source),len(human_mix),8*48000)
# AAC decoders may differ slightly in leading priming; search a bounded alignment.
reference=voice_source[:count]
lag=max(range(-256,257),key=lambda k: np.dot(reference[1024:-1024:8],human_mix[1024+k:count-1024+k:8]))
speech_amplitudes=[]
silence_amplitudes=[]
voice_gains=[]
for start in range(24000,count-6000,4800):
    end=start+4800
    ref=reference[start:end]
    out=human_mix[start+lag:end+lag]
    t=np.arange(start,end)/48000
    design=np.column_stack((ref,np.sin(2*np.pi*880*t),np.cos(2*np.pi*880*t),np.ones(len(t))))
    beta=np.linalg.lstsq(design,out,rcond=None)[0]
    ms=(start+2400)*1000/48000
    amp=float(np.hypot(beta[1],beta[2]))
    voiced=any(r['start_ms']+80<ms<r['end_ms']-80 for r in regions)
    silent=all(ms<r['start_ms']-400 or ms>r['end_ms']+400 for r in regions)
    if voiced:
        speech_amplitudes.append(amp)
        if np.std(ref)>.004: voice_gains.append(float(beta[0]))
    if silent: silence_amplitudes.append(amp)
assert voice_gains and .7<float(np.median(voice_gains))<1.3, voice_gains
assert speech_amplitudes and silence_amplitudes, 'Need voiced and silent test windows'
assert np.median(speech_amplitudes)<np.median(silence_amplitudes)*.6, (speech_amplitudes,silence_amplitudes)
assert np.max(abs(human_mix))<.9999, 'Human MP4 reaches digital full scale'
ebur=subprocess.run(['ffmpeg','-hide_banner','-i',str(folder/'studio-human-mixed.mp4'),'-af','ebur128=peak=true','-f','null','-'],capture_output=True,text=True,check=True)
summary=ebur.stderr.rsplit('Summary:',1)[-1]
lufs=re.search(r'I:\s*([-0-9.]+) LUFS',summary)
true_peak=re.search(r'Peak:\s*([-0-9.]+) dBFS',summary)
assert lufs and true_peak, summary
report={'status':'passed','original_tone_amplitude':voice,'music_tone_amplitude':music,'sample_peak':float(np.max(abs(mixed))), 'crop_width':v['width'],'crop_height':v['height'],'crop_preview_export_mean_absolute_error':error,'speech_tested':True,'human_voice_gain_median':float(np.median(voice_gains)),'music_amplitude_during_words':float(np.median(speech_amplitudes)),'music_amplitude_during_silence':float(np.median(silence_amplitudes)),'human_audio_alignment_samples':lag,'human_integrated_lufs':float(lufs.group(1)),'human_true_peak_dbfs':float(true_peak.group(1)),'tor_tested':False,'phone_local3_end_to_end_tested':False,'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.glob('*') if p.is_file()}}
(folder/'independent-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
