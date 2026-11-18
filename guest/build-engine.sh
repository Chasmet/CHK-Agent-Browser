#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
podroid=e3fc1befa3c135d28bf8d40e3c2264d93fea87ef
git -C "$work" init -q
git -C "$work" fetch --depth 1 https://github.com/ExTV/Podroid.git "$podroid"
git -C "$work" checkout -q --detach FETCH_HEAD

cat >> "$work/Dockerfile" <<'DOCKER'

FROM scratch AS chk-engine
COPY --from=qemu-builder /opt/qemu-out/libqemu-system-aarch64.so /libqemu-system-aarch64.so
COPY --from=qemu-builder /opt/qemu-out/libslirp.so /libslirp.so
COPY --from=qemu-builder /opt/qemu-out/libpodroid-launcher.so /libpodroid-launcher.so
COPY --from=qemu-builder /opt/qemu-out/share/qemu/edk2-aarch64-code.fd /edk2-aarch64-code.fd
COPY --from=qemu-builder /opt/qemu-out/share/qemu/efi-virtio.rom /efi-virtio.rom
DOCKER

docker buildx build --target chk-engine --output "type=local,dest=$work/engine" "$work"
libs="$root/app/src/main/jniLibs/arm64-v8a"
assets="$root/app/src/main/assets/android-guest"
install -d "$libs" "$assets"
install -m 644 "$work/engine/"*.so "$libs/"
install -m 644 "$work/engine/"*.fd "$work/engine/"*.rom "$assets/"
