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
    channels=next(s['channels'] for s in metadata(name)['streams'] if s['codec_type']=='audio')
    assert channels in (1,2), channels
    pcm=np.frombuffer(run('ffmpeg','-v','error','-i',str(folder/name),'-vn','-ar','48000','-f','f32le','-'), dtype='<f4')
    # Preserve per-channel amplitude; FFmpeg's default -ac 1 uses constant-power downmix.
    return pcm.reshape(-1,channels).mean(axis=1)
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
assert .08<music<.10, f'Music at 15 percent has unexpected amplitude: {music}'
reference_tone=amplitude(audio(Path('app/src/androidTest/assets/editor-fixture.mp4').resolve()),440)
assert .97<voice/reference_tone<1.03, f'Original gain is not 100 percent: {voice/reference_tone}'
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
upper=final[20:110,30:210].mean(axis=(0,1))
lower=final[160:250,30:210].mean(axis=(0,1))
assert upper[0]>upper[1]+100 and lower[1]>lower[0]+100, 'Rotation/flip orientation differs from the known source quadrants'
# Regression on decoded human speech + the known independent music tone.
voice_source=audio('studio-human-source.mp4').astype(np.float64)
human_mix=audio('studio-human-mixed.mp4').astype(np.float64)
proof=json.loads((folder/'studio-human-proof.json').read_text())
regions=proof['voice_regions']['original']
assert len(regions)>3, 'No actual recognized speech intervals'
count=min(len(voice_source),len(human_mix),8*48000)
# AAC priming can shift the decoded waveform; measure it and reject offsets over 20 ms.
reference=voice_source[:count]
lag=max(range(-2048,2049),key=lambda k: np.dot(reference[4096:-4096:8],human_mix[4096+k:count-4096+k:8]))
assert abs(lag)<=960, f'Audio alignment exceeds 20 ms: {lag / 48} ms'
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
assert voice_gains and .9<float(np.median(voice_gains))<1.1, voice_gains
assert speech_amplitudes and silence_amplitudes, 'Need voiced and silent test windows'
assert np.median(speech_amplitudes)<np.median(silence_amplitudes)*.6, (speech_amplitudes,silence_amplitudes)
assert np.max(abs(human_mix))<.9999, 'Human MP4 reaches digital full scale'
ebur=subprocess.run(['ffmpeg','-hide_banner','-i',str(folder/'studio-human-mixed.mp4'),'-af','ebur128=peak=true','-f','null','-'],capture_output=True,text=True,check=True)
summary=ebur.stderr.rsplit('Summary:',1)[-1]
lufs=re.search(r'I:\s*([-0-9.]+) LUFS',summary)
true_peak=re.search(r'Peak:\s*([-0-9.]+) dBFS',summary)
assert lufs and true_peak, summary
assert float(true_peak.group(1))<=0, f'True peak exceeds digital full scale: {true_peak.group(1)}'
native=json.loads((folder/'studio-human-native-analysis.json').read_text())
assert native['lufs_measured'] and abs(native['integrated_lufs']-float(lufs.group(1)))<.3, (native['integrated_lufs'],lufs.group(1))
report={'status':'passed','original_tone_amplitude':voice,'music_tone_amplitude':music,'sample_peak':float(np.max(abs(mixed))), 'crop_width':v['width'],'crop_height':v['height'],'crop_preview_export_mean_absolute_error':error,'speech_tested':True,'human_voice_gain_median':float(np.median(voice_gains)),'music_amplitude_during_words':float(np.median(speech_amplitudes)),'music_amplitude_during_silence':float(np.median(silence_amplitudes)),'human_audio_alignment_samples':int(lag),'human_audio_alignment_ms':float(lag/48),'alignment_tolerance_ms':20,'lip_sync_verified':False,'human_integrated_lufs':float(lufs.group(1)),'human_true_peak_dbfs':float(true_peak.group(1)),'tor_tested':False,'phone_local3_end_to_end_tested':False,'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.glob('*') if p.is_file() and p.name!='independent-verification.json'}}
(folder/'independent-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
