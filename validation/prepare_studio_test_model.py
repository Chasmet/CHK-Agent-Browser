"""Fetch the public Vosk model once on the CI host, independently of emulator networking."""
from pathlib import Path
import time
import urllib.request
import zipfile

name = 'vosk-model-small-en-us-0.15'
folder = Path('.ci-test-models')
model = folder / name
if all((model / part).is_dir() for part in ('am', 'conf', 'graph')):
    print('English speech test model already prepared')
else:
    folder.mkdir(exist_ok=True)
    archive = folder / (name + '.zip')
    started = time.monotonic()
    try:
        with urllib.request.urlopen('https://alphacephei.com/vosk/models/' + name + '.zip', timeout=40) as src, archive.open('wb') as dst:
            count = 0
            while chunk := src.read(65536):
                count += len(chunk)
                if count > 65 * 1024 * 1024 or time.monotonic() - started > 240:
                    raise RuntimeError('Speech model download exceeded CI bounds')
                dst.write(chunk)
        with zipfile.ZipFile(archive) as src:
            entries = src.infolist()
            if len(entries) > 10000 or sum(e.file_size for e in entries) > 300 * 1024 * 1024:
                raise RuntimeError('Unexpected speech model archive size')
            root = folder.resolve()
            for entry in entries:
                if not entry.filename.startswith(name + '/') or not (root / entry.filename).resolve().is_relative_to(root):
                    raise RuntimeError('Unexpected speech model archive path')
            src.extractall(folder)
        if not all((model / part).is_dir() for part in ('am', 'conf', 'graph')):
            raise RuntimeError('Speech test model incomplete')
        print('English speech test model prepared')
    finally:
        archive.unlink(missing_ok=True)
