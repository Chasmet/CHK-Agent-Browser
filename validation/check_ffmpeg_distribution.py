#!/usr/bin/env python3
"""Fail closed before public release if FFmpeg provenance or notices are missing."""
import hashlib
import json
import pathlib
import sys
import urllib.request
import zipfile

root = pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1/dev.ffmpegkit-maintained/ffmpeg-kit-full/8.1.9"
aars = list(root.rglob("ffmpeg-kit-full-8.1.9.aar"))
if len(aars) != 1:
    raise SystemExit(f"Expected one resolved FFmpeg full AAR, found {len(aars)}")
aar = aars[0]
digest = hashlib.sha256(aar.read_bytes()).hexdigest()
url = "https://api.github.com/repos/ffmpegkit-maintained/ffmpeg/releases/tags/v8.1.9-lts-android"
req = urllib.request.Request(url, headers={"Accept":"application/vnd.github+json","User-Agent":"CHK-release-validation"})
with urllib.request.urlopen(req, timeout=60) as response:
    release = json.load(response)
matches = [a for a in release["assets"] if a.get("digest") == "sha256:" + digest and a["name"].endswith(".aar") and "full" in a["name"] and "gpl" not in a["name"].lower()]
if not matches:
    raise SystemExit("Published Maven AAR is not proven identical to upstream non-GPL full release asset; no public release")
out = pathlib.Path("ffmpeg-release-notices")
out.mkdir(exist_ok=True)
with zipfile.ZipFile(aar) as z:
    candidates = [n for n in z.namelist() if not n.endswith("/") and any(t in n.rsplit("/",1)[-1].lower() for t in ("license","licence","notice","copying","copyright"))]
    combined = []
    for i,n in enumerate(candidates):
        info = z.getinfo(n)
        if info.file_size > 2_000_000:
            raise SystemExit(f"Unexpected notice size: {n}")
        data = z.read(n)
        (out / f"{i:03d}-{pathlib.PurePosixPath(n).name}").write_bytes(data)
        combined.append(data.decode("utf-8",errors="replace").lower())
    content = "\n".join(combined)
    if not candidates or not all(t in content for t in ("ffmpeg","lame","vorbis","opus","libvpx")):
        raise SystemExit("Complete third-party notices for key codecs not established; no public release")
for p in pathlib.Path("app/src/main/assets/licenses/ffmpeg").iterdir():
    if p.is_file():
        (out / ("CHK-" + p.name)).write_bytes(p.read_bytes())
(out / "PROVENANCE.json").write_text(json.dumps({"maven":"dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9","sha256":digest,"upstream":matches[0]["browser_download_url"],"source":release["html_url"],"notice_entries":candidates}, indent=2))
print("FFmpeg upstream binary hash matched; notices exported for release.")
print(aar)
