"""Public, attributed speech fixture only. Never upload owner's voice/video to CI."""
from pathlib import Path
import hashlib, json, subprocess, urllib.request

root = Path('app/src/androidTest/assets/pro-studio')
root.mkdir(parents=True, exist_ok=True)
url = 'https://download.pytorch.org/torchaudio/tutorial-assets/Lab41-SRI-VOiCES-src-sp0307-ch127535-sg0042.wav'
speech = root / 'speech-source.wav'
if not speech.exists():
    with urllib.request.urlopen(url, timeout=60) as response:
        data=response.read(5_000_001)
    if len(data)>5_000_000: raise RuntimeError('Speech fixture unexpectedly large')
    speech.write_bytes(data)
digest = hashlib.sha256(speech.read_bytes()).hexdigest()
if digest != 'c65fcd726d6b08c82c1e5dc7558f863cd8d483e3ed2f4a7bcf271dc1865ada14':
    raise RuntimeError('Public speech fixture SHA256 changed; review the upstream source')
print('Public speech SHA256', digest)

def ffmpeg(*args):
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y',*args], check=True)

ffmpeg('-i',str(speech),'-af','atrim=duration=6,asetpts=PTS-STARTPTS,volume=0.45,adelay=1000,apad',
       '-t','8','-ar','48000','-ac','1',str(root/'voice.wav'))
ffmpeg('-f','lavfi','-i','color=red:s=180x320:r=30:d=8','-i',str(root/'voice.wav'),
       '-vf','drawbox=x=90:y=0:w=90:h=160:color=lime:t=fill,drawbox=x=0:y=160:w=90:h=160:color=blue:t=fill,drawbox=x=90:y=160:w=90:h=160:color=yellow:t=fill',
       '-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac','-b:a','128k','-t','8',str(root/'voice-video.mp4'))
ffmpeg('-f','lavfi','-i','aevalsrc=0.4*sin(2*PI*440*t)+0.2*sin(2*PI*997*t):s=44100:d=8',
       '-ac','2','-c:a','pcm_s16le',str(root/'music.wav'))
ffmpeg('-f','lavfi','-i','aevalsrc=0.08*sin(2*PI*1600*t):s=48000:d=8',
       '-ac','1','-c:a','pcm_s16le',str(root/'fx.wav'))
(root/'ATTRIBUTION.txt').write_text('VOiCES Lab41-SRI-VOiCES-src-sp0307-ch127535-sg0042, from the PyTorch torchaudio tutorial assets.\n'
    'Corpus: Voices Obscured in Complex Environmental Settings (VOiCES), Colleen Richey et al. (2018), Lab41 / SRI International.\n'
    'License: Creative Commons Attribution 4.0, https://creativecommons.org/licenses/by/4.0/\n'
    'Corpus and license: https://iqtlabs.github.io/voices/\nSource: '+url+'\n'
    'Modified here: first six seconds, gain 0.45, silence padding, mono 48kHz; embedded in a generated test video.\n')
print('PASS: generated public human voice, 44.1kHz stereo music, 48kHz mono FX and vertical quadrant video')
