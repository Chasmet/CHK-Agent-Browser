"""Installable official Tor Browser for isolated emulator network tests; verify OpenPGP first."""
from pathlib import Path
import hashlib, subprocess, tempfile, urllib.request
version='15.0.26'
fingerprint='EF6E286DDA85EA2A4BA7DE684E2C6E8793298290'
root=Path('app/build/tor-browser');root.mkdir(parents=True,exist_ok=True)
name=f'tor-browser-android-x86_64-{version}.apk'
def download(url,path,limit):
    with urllib.request.urlopen(url,timeout=90) as response,path.open('wb') as out:
        size=0
        while True:
            block=response.read(1024*1024)
            if not block:break
            size+=len(block)
            if size>limit:raise RuntimeError('Tor fixture exceeded download limit')
            out.write(block)
download(f'https://dist.torproject.org/torbrowser/{version}/{name}',root/'tor.apk',160*1024*1024)
download(f'https://dist.torproject.org/torbrowser/{version}/{name}.asc',root/'tor.apk.asc',10000)
download(f'https://keys.openpgp.org/vks/v1/by-fingerprint/{fingerprint}',root/'developers.asc',1024*1024)
with tempfile.TemporaryDirectory(prefix='chk-tor-keyring-') as keyring:
    subprocess.run(['gpg','--homedir',keyring,'--batch','--import',str(root/'developers.asc')],check=True)
    listing=subprocess.check_output(['gpg','--homedir',keyring,'--batch','--with-colons','--fingerprint'],text=True)
    if fingerprint not in listing:raise RuntimeError('Unexpected Tor Developers public key')
    subprocess.run(['gpg','--homedir',keyring,'--batch','--verify',str(root/'tor.apk.asc'),str(root/'tor.apk')],check=True)
sha=hashlib.file_digest((root/'tor.apk').open('rb'),'sha256').hexdigest()
(root/'verified.txt').write_text(f'Official Tor Browser {version}\nOpenPGP primary fingerprint {fingerprint}\nAPK SHA256 {sha}\n')
print((root/'verified.txt').read_text())
