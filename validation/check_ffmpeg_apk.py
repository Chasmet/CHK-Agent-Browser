#!/usr/bin/env python3
"""Validate the FFmpeg libraries actually packaged in the APK (not just the AAR)."""
import struct
import sys
import zipfile

required = {"libffmpegkit.so", "libavcodec.so", "libavformat.so", "libavutil.so",
            "libavfilter.so", "libswresample.so", "libswscale.so"}
optional = {"libavdevice.so", "libffmpegkit_abidetect.so"}
with zipfile.ZipFile(sys.argv[1]) as apk:
    for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
        prefix = f"lib/{abi}/"
        names = set(apk.namelist())
        missing = required - {n[len(prefix):] for n in names if n.startswith(prefix)}
        if missing:
            raise SystemExit(f"{abi}: missing {sorted(missing)}")
        for name in sorted(required | optional):
            path = prefix + name
            if path not in names:
                continue
            data = apk.read(path)
            if data[:6] != b"\x7fELF\x02\x01":
                raise SystemExit(f"{path}: not little-endian ELF64")
            if struct.unpack_from("<H", data, 18)[0] != machine:
                raise SystemExit(f"{path}: wrong CPU architecture")
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phentsize, phnum = struct.unpack_from("<HH", data, 54)
            loads = []
            for i in range(phnum):
                typ, flags, offset, vaddr, paddr, filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, phoff + i * phentsize)
                if typ == 1:
                    loads.append((offset, vaddr, align))
            if not loads or any(a < 16384 or (o-v) % 16384 for o,v,a in loads):
                raise SystemExit(f"{path}: invalid 16 KiB PT_LOAD alignment")
            print(f"OK {path}: ELF64, CPU {machine}, 16 KiB aligned")
