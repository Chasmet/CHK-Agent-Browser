#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
BASE="$ROOT/.ci-real-guest"
mkdir -p "$BASE"
cd "$BASE"

echo "Downloading independently verified Google-services LineageOS guest..."
curl --fail --show-error --silent --location --retry 3 -o guest-image.zip \
  https://github.com/jqssun/android-ci/releases/download/lineage-22.2/UTM-VM-lineage-22.2-virtio_arm64only.zip
echo 'eecce6851943173facc640fc58588d9a6bd30d51e08abd67c03215453449a468  guest-image.zip' | sha256sum --check

python3 - <<'PY'
from zipfile import ZipFile
with ZipFile('guest-image.zip') as archive:
    found=set()
    for info in archive.infolist():
        name=info.filename.rsplit('/Data/',1)[-1] if '/Data/' in info.filename else ''
        if name not in ('vda.qcow2','vdb.qcow2','efi_vars.fd'):
            continue
        if name in found:
            raise SystemExit('Duplicate '+name)
        found.add(name)
        with archive.open(info) as src, open(name,'wb') as dst:
            import shutil
            shutil.copyfileobj(src,dst,1024*1024)
    assert found == {'vda.qcow2','vdb.qcow2','efi_vars.fd'}, found
print('LineageOS guest disks extracted and verified')
PY
rm guest-image.zip

FW=$(find /usr/share -type f \( -name 'QEMU_EFI.fd' -o -name 'edk2-aarch64-code.fd' -o -name 'AAVMF_CODE.fd' \) 2>/dev/null | head -n 1)
test -n "$FW"
cp "$FW" code.fd
truncate -s 67108864 code.fd
# The vendor EFI vars image enables Secure Boot using keys not trusted by
# the host Ubuntu EDK2 firmware. Preserve it and start with fresh variables.
mv efi_vars.fd vendor_efi_vars.qcow2
truncate -s 67108864 efi_vars.fd
echo "Firmware: $FW (fresh, unsigned/unenrolled UEFI variables)"

QEMU=qemu-system-aarch64
"$QEMU" -machine virt -cpu max,pauth-impdef=on -accel tcg,thread=multi,tb-size=256 \
  -m 2048 -smp 2 -display none -monitor none -serial file:serial.log \
  -qmp unix:qmp.sock,server=on,wait=off \
  -device virtio-gpu-pci,romfile= -device qemu-xhci,id=usb \
  -device usb-tablet,bus=usb.0 -device usb-kbd,bus=usb.0 \
  -device virtio-rng-pci -device virtio-serial \
  -device virtio-net-pci,netdev=net0,romfile= \
  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:15555-:5555 \
  -drive if=pflash,unit=0,format=raw,readonly=on,file=code.fd \
  -drive if=pflash,unit=1,format=raw,file=efi_vars.fd \
  -drive file=vda.qcow2,if=none,id=vda,format=qcow2 \
  -device virtio-blk-pci,drive=vda,bootindex=0,romfile= \
  -drive file=vdb.qcow2,if=none,id=vdb,format=qcow2 \
  -device virtio-blk-pci,drive=vdb,bootindex=1,romfile= \
  > qemu.log 2>&1 &
PID=$!
cleanup() { kill "$PID" 2>/dev/null || true; }
trap cleanup EXIT

python3 - <<'PY'
import json,socket,time,os
path='qmp.sock'
for _ in range(100):
    if os.path.exists(path):
        try:
            s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
            s.settimeout(12)
            s.connect(path)
            break
        except (ConnectionRefusedError,FileNotFoundError):
            s.close()
    time.sleep(.2)
else:
    print(open('qemu.log').read()[-2000:])
    raise SystemExit("QEMU never accepted a monitor connection")
def take():
    b=b''
    while not b.endswith(b'\n'):
        b+=s.recv(8192)
    return json.loads(b)
print('QMP greeting:',take())
s.sendall(b'{"execute":"qmp_capabilities","id":1}\n')
print('QMP capabilities:',take())
s.sendall(b'{"execute":"query-status","id":2}\n')
print('QMP status:',take())
s.close()
PY

echo "Boot probe: request actual Android property over the guest's ADB port."
DEADLINE=$((SECONDS+420))
BOOT=0
while ((SECONDS < DEADLINE)); do
    if grep -qiE "No bootable option or device|Security Violation" serial.log 2>/dev/null; then
        echo "UEFI firmware refused the guest loader:" >&2
        tail -n 45 serial.log || true
        break
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "QEMU process exited before guest boot" >&2
        tail -n 80 qemu.log || true
        exit 1
    fi
    timeout 10 adb connect 127.0.0.1:15555 >/dev/null 2>&1 || true
    PROP=$(timeout 12 adb -s 127.0.0.1:15555 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [ "$PROP" = "1" ]; then
        BOOT=1
        break
    fi
    echo "Guest not yet boot-completed; elapsed=${SECONDS}s; property='${PROP}'"
    sleep 12
done

python3 - <<'PY'
import json,os,socket
if not os.path.exists('qmp.sock'):raise SystemExit()
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.settimeout(20);s.connect('qmp.sock')
def read():
    value=b''
    while not value.endswith(b'\n'):
        value+=s.recv(8192)
    return json.loads(value)
read()
for i,command in enumerate(('qmp_capabilities','screendump'),1):
    args={} if i==1 else {'filename':'vm-frame.ppm','format':'ppm'}
    s.sendall((json.dumps({'execute':command,'arguments':args,'id':i})+'\n').encode())
    for _ in range(15):
        answer=read()
        if answer.get('id')==i:
            print(command,answer);break
s.close()
PY

if [ "$BOOT" != "1" ]; then
    echo "Android boot NOT verified. This VM image must not be promoted as working." >&2
    tail -n 80 serial.log || true
    exit 1
fi

echo "Genuine Android guest boot_completed=1."
PACKAGES=$(timeout 30 adb -s 127.0.0.1:15555 shell pm list packages 2>/dev/null)
echo "$PACKAGES" | grep -x 'package:com.google.android.gms'
echo "$PACKAGES" | grep -x 'package:com.android.vending'
echo "Booted Android contains BOTH Google Play Services and the genuine Play Store."
