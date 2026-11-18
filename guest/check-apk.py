"""Fail the build if Gradle drops any required Android ARM64 runtime component."""
import struct
import sys
from zipfile import ZipFile


def check(path):
    with ZipFile(path) as apk:
        for name in ("libqemu-system-aarch64.so", "libpodroid-launcher.so", "libslirp.so"):
            with apk.open(f"lib/arm64-v8a/{name}") as component:
                elf = component.read(20)
            if elf[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", elf, 18)[0] != 183:
                raise ValueError(f"{name} is not an ARM64 ELF")
        for name in ("edk2-aarch64-code.fd", "efi-virtio.rom"):
            if apk.getinfo(f"assets/android-guest/{name}").file_size == 0:
                raise ValueError(f"{name} is empty")
    print(f"ARM64 engine, launcher, networking and firmware present in {path}")


if __name__ == "__main__":
    check(sys.argv[1])
