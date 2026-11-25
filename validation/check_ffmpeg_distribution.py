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
# The upstream AAR does not package a complete set of third-party notices.
# Retrieve them from the exact source pins used by the free-full build recipe.
# Include the non-GPL full recipe's dependencies conservatively, including
# transitive build libraries. Do not substitute a current upstream license.
import re
import os
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

pins_text = pathlib.Path("app/src/main/assets/licenses/ffmpeg/SOURCE-PINS.sh").read_text()
pins = {}
for name, body in re.findall(r"^  ([a-z0-9-]+)\)\s*\n(.*?)^    ;;", pins_text, re.M | re.S):
    repository = re.search(r'SOURCE_REPO_URL="https://github.com/([^"]+)"', body)
    revision = re.search(r'SOURCE_ID="([^"]+)"', body)
    if repository and revision:
        pins[name] = (repository.group(1), revision.group(1))
libraries = """ffmpeg cpu-features dav1d expat fontconfig freetype fribidi giflib gmp
harfbuzz jpeg kvazaar lame libaom libass libiconv libilbc libogg libpng
libsamplerate libsndfile libtheora libuuid libvorbis libvpx libwebp libxml2
nettle opencore-amr openh264 openssl opus sdl shine snappy soxr speex tiff
twolame vo-amrwbenc zimg""".split()
legal = re.compile(r"^(copying|copyright|licen[cs]e|notice|authors|patents|ftl)([._-].*)?$", re.I)
def get_json(url):
    headers = {"Accept":"application/vnd.github+json","User-Agent":"CHK-release-validation"}
    if os.getenv("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        return json.load(response)
def collect(name):
    repository, revision = pins[name]
    ref = urllib.parse.quote(revision, safe="")
    tree = get_json(f"https://api.github.com/repos/{repository}/git/trees/{ref}?recursive=1")
    if tree.get("truncated"):
        raise RuntimeError(f"{name}: truncated source inventory")
    entries = [e for e in tree["tree"] if e["type"] == "blob"
               and len(e["path"].split("/")) <= 4
               and legal.fullmatch(e["path"].rsplit("/",1)[-1])]
    if not entries or not any(re.match(r"^(copying|copyright|licen[cs]e|notice|ftl)", e["path"].rsplit("/",1)[-1], re.I) for e in entries):
        raise RuntimeError(f"{name}: license files not found at source pin {revision}")
    folder = out / "source-notices" / name
    folder.mkdir(parents=True, exist_ok=True)
    for e in entries:
        path = pathlib.PurePosixPath(e["path"])
        if path.is_absolute() or ".." in path.parts or e.get("size",0) > 2_000_000:
            raise RuntimeError(f"{name}: unexpected notice path or size")
        # Download by immutable Git blob ID, then verify its Git object hash.
        import base64
        blob = get_json(f"https://api.github.com/repos/{repository}/git/blobs/{e['sha']}")
        data = base64.b64decode(blob["content"])
        actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if actual != e["sha"]:
            raise RuntimeError(f"{name}: notice Git blob hash mismatch")
        target = folder.joinpath(*path.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return {"library":name,"repository":repository,"revision":revision,"tree_sha":tree["sha"],
            "source_archive":f"https://github.com/{repository}/archive/{revision}.tar.gz",
            "notices":[{"path":e["path"],"git_blob":e["sha"]} for e in entries]}
with ThreadPoolExecutor(max_workers=4) as pool:
    inventory = list(pool.map(collect, libraries))
(out / "SOURCE-NOTICE-INVENTORY.json").write_text(json.dumps(inventory, indent=2))
print(f"Collected pinned source notices for {len(inventory)} libraries.")
for p in pathlib.Path("app/src/main/assets/licenses/ffmpeg").iterdir():
    if p.is_file():
        (out / ("CHK-" + p.name)).write_bytes(p.read_bytes())
(out / "PROVENANCE.json").write_text(json.dumps({"maven":"dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9","sha256":digest,"upstream":matches[0]["browser_download_url"],"source":release["html_url"],"notice_entries":candidates}, indent=2))
print("FFmpeg upstream binary hash matched; notices exported for release.")
print(aar)
