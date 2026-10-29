"""Independent FFmpeg decode: identify both sources, bound levels, compare rendered crop pixels."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
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
report={'status':'passed','original_tone_amplitude':voice,'music_tone_amplitude':music,'sample_peak':float(np.max(abs(mixed))), 'crop_width':v['width'],'crop_height':v['height'],'crop_preview_export_mean_absolute_error':error,'speech_tested':False,'tor_tested':False,'phone_local3_end_to_end_tested':False,'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.glob('*') if p.is_file()}}
(folder/'independent-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
