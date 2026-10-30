"""Inspect the exact upstream Tor Android artifact before selecting a compatible integration."""
from pathlib import Path
import hashlib, io, json, struct, urllib.request, zipfile
commit='5fdb8dbcfe98d1b7e027e2f99bc086709870b005'
base=f'https://raw.githubusercontent.com/guardianproject/gpmaven/{commit}/info/guardianproject/tor-android/0.4.9.14/'
out=Path('tor-bundle-proof');out.mkdir(exist_ok=True)
for name,expected in [('tor-android-0.4.9.14.aar','28ff2bb6d4d42eabe57183dedd7a97fce77e8ce4b6f5af6fa03993b3ca054edd'),('tor-android-0.4.9.14-sources.jar','095118a41782a91b9002038461c4c6a65f5c41bc6f5a37323362eceaf0088a2b')]:
    with urllib.request.urlopen(base+name,timeout=40) as src: data=src.read(20000000)
    if hashlib.sha256(data).hexdigest()!=expected:raise RuntimeError('Tor artifact checksum mismatch: '+name)
    (out/name).write_bytes(data)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        if name.endswith('.aar'):
            for p in ['META-INF/com/android/build/gradle/aar-metadata.properties','AndroidManifest.xml']:
                print(p,z.read(p).decode() if p in z.namelist() else 'absent')
            print('Native libraries:',[p for p in z.namelist() if p.endswith('.so')])
            with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as classes:
                c=classes.read('org/torproject/jni/TorService.class');print('TorService Java major:',struct.unpack('>H',c[6:8])[0])
        else:
            print('Source entries:',z.namelist())
            source=next(p for p in z.namelist() if p.endswith('/TorService.java'))
            (out/'TorService.java').write_bytes(z.read(source))
print('Pinned Tor bundle inspected; no Tor connectivity is asserted by this probe')
