"""Local HTTPS companion for a single Android Studio AVD."""
import argparse
import hashlib
import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import secrets
import shlex
import shutil
import ssl
import struct
import subprocess
import tempfile
import threading
import time

MAX_MEDIA = 2 * 1024 ** 3
MAX_FRAME = 12 * 1024 ** 2
MEDIA = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.(mp4|mov|m4v|webm|jpg|jpeg|png|webp|mp3|m4a|wav)", re.I)
KEYS = {"home": "KEYCODE_HOME", "back": "KEYCODE_BACK", "recent": "KEYCODE_APP_SWITCH",
        "enter": "KEYCODE_ENTER", "delete": "KEYCODE_DEL"}


class DeviceError(Exception):
    pass


def video_size(png):
    if not 24 <= len(png) <= MAX_FRAME or png[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
        raise DeviceError("Capture Android indisponible.")
    width, height = struct.unpack(">II", png[16:24])
    if width < 1 or height < 1 or width * height > 10_000_000:
        raise DeviceError("Définition de l’écran Android trop élevée.")
    return width, height


class Emulator:
    def __init__(self, adb, serial):
        if not re.fullmatch(r"emulator-\d{4,5}", serial):
            raise ValueError("Choisis un AVD local (par exemple emulator-5554).")
        self.adb = [adb, "-H", "127.0.0.1", "-P", "5037", "-s", serial]
        self.serial = serial
        self.lock = threading.Lock()
        self.frames = {}

    def run(self, *args, timeout=12):
        try:
            return subprocess.run(self.adb + list(args), capture_output=True, check=True,
                                  timeout=timeout).stdout
        except (OSError, subprocess.SubprocessError) as error:
            raise DeviceError("Commande Android impossible. Vérifie que l’AVD est démarré et joignable par ADB.") from error

    def status(self):
        ready = (self.run("get-state").strip() == b"device" and
                 self.run("shell", "getprop", "sys.boot_completed").strip() == b"1")
        return {"ready": ready, "serial": self.serial}

    def frame(self):
        with self.lock:
            png = self.run("exec-out", "screencap", "-p")
            width, height = video_size(png)
            now = time.monotonic()
            self.frames = {key: value for key, value in self.frames.items() if now - value[2] < 30}
            frame_id = secrets.token_hex(12)
            self.frames[frame_id] = (width, height, now)
            return png, frame_id

    def control(self, data):
        with self.lock:
            op = data.get("operation")
            if op in ("tap", "swipe"):
                frame_id = data.get("frame_id")
                frame = self.frames.get(frame_id) if isinstance(frame_id, str) else None
                if frame is None or time.monotonic() - frame[2] >= 30:
                    raise ValueError("Capture Android expirée. Actualise l’écran.")
                width, height = frame[:2]
                if video_size(self.run("exec-out", "screencap", "-p")) != (width, height):
                    raise ValueError("L’écran a tourné. Actualise la capture.")
                names = ("x", "y") if op == "tap" else ("x", "y", "end_x", "end_y")
                coords = [data.get(key) for key in names]
                if any(type(p) is not int or not 0 <= p < limit
                       for p, limit in zip(coords, (width, height, width, height))):
                    raise ValueError("Geste hors de l’écran Android.")
                args = [op] + [str(p) for p in coords]
                if op == "swipe":
                    args.append("300")
            elif op == "key":
                key = data.get("key")
                if not isinstance(key, str) or key not in KEYS:
                    raise ValueError("Touche Android inconnue.")
                args = ["keyevent", KEYS[key]]
            elif op == "text":
                text = data.get("text")
                if (not isinstance(text, str) or not re.fullmatch(r"[ -~]{1,500}", text)
                        or "%s" in text):
                    raise ValueError("La saisie ADB accepte jusqu’à 500 caractères ASCII par envoi. Le clavier Android reste disponible.")
                args = ["text", text.replace(" ", "%s")]
            else:
                raise ValueError("Action Android inconnue.")
            self.run("shell", " ".join(shlex.quote(arg) for arg in ["input"] + args))
            self.frames.clear()
            return {"input_sent": True}

    def push(self, file, name, size, digest):
        folder = "/sdcard/Download/CHK"
        remote = f"{folder}/{secrets.token_hex(6)}-{name}"
        stage = remote + ".part"
        self.run("shell", "mkdir", "-p", folder)
        try:
            self.run("push", str(file), stage, timeout=300)
            actual = self.run("shell", "sha256sum", stage, timeout=180).split()
            if not actual or not hmac.compare_digest(actual[0], digest.encode("ascii")):
                raise DeviceError("L’empreinte du fichier Android ne correspond pas à l’original.")
            self.run("shell", "mv", stage, remote)
        except DeviceError:
            try:
                self.run("shell", "rm", "-f", stage)
            except DeviceError:
                pass
            raise
        try:
            self.run("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                     "-d", f"file://{remote}")
        except DeviceError:
            pass
        return {"verified": True, "path": remote, "size": size, "sha256": digest}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def respond(self, status, payload, content_type="application/json", frame_id=None):
        body = json.dumps(payload, ensure_ascii=False).encode() if content_type == "application/json" else payload
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if frame_id:
            self.send_header("X-CHK-Frame", frame_id)
        self.end_headers()
        self.wfile.write(body)

    def dispatch(self, method):
        auth = self.headers.get("Authorization", "").encode("utf-8")
        if not hmac.compare_digest(auth, self.server.authorization):
            self.respond(401, {"error": "Appairage Android refusé."})
            return
        device = self.server.device
        try:
            if (method, self.path) == ("GET", "/v1/status"):
                self.respond(200, device.status())
            elif (method, self.path) == ("GET", "/v1/frame"):
                png, frame_id = device.frame()
                self.respond(200, png, "image/png", frame_id)
            elif (method, self.path) == ("POST", "/v1/input"):
                size = self.length(8192)
                data = json.loads(self.rfile.read(size))
                if not isinstance(data, dict):
                    raise ValueError("Action Android invalide.")
                self.respond(200, device.control(data))
            elif (method, self.path) == ("POST", "/v1/media"):
                self.respond(200, self.media(device))
            else:
                self.respond(404, {"error": "Commande Android inconnue."})
        except DeviceError as error:
            self.respond(503, {"error": str(error)})
        except (ValueError, OverflowError) as error:
            self.respond(400, {"error": str(error)})
        except (OSError, TimeoutError):
            self.close_connection = True

    def length(self, limit):
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or self.headers.get("Transfer-Encoding"):
            raise ValueError("Taille du fichier ou de la commande requise.")
        size = int(lengths[0])
        if not 0 < size <= limit:
            raise ValueError("Taille du fichier ou de la commande hors limite.")
        return size

    def media(self, device):
        size = self.length(MAX_MEDIA)
        name = self.headers.get("X-CHK-Filename", "")
        if not MEDIA.fullmatch(name):
            raise ValueError("Choisis un fichier vidéo, image ou audio compatible.")
        if not self.server.media_slot.acquire(blocking=False):
            raise DeviceError("Un autre transfert Android est en cours.")
        try:
            with tempfile.TemporaryDirectory(prefix="chk-emulator-") as folder:
                file = Path(folder, name)
                digest = hashlib.sha256()
                with file.open("wb") as output:
                    remaining = size
                    while remaining:
                        chunk = self.rfile.read(min(remaining, 1024 * 1024))
                        if not chunk:
                            raise ValueError("Transfert incomplet.")
                        output.write(chunk)
                        digest.update(chunk)
                        remaining -= len(chunk)
                return device.push(file, name, size, digest.hexdigest())
        finally:
            self.server.media_slot.release()

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")


class TlsServer(ThreadingHTTPServer):
    def get_request(self):
        sock, addr = super().get_request()
        sock.settimeout(4)
        try:
            client = self.tls.wrap_socket(sock, server_side=True)
        except OSError:
            sock.close()
            raise
        client.settimeout(30)
        return client, addr


def certificate(folder, openssl):
    cert, key = folder / "cert.pem", folder / "key.pem"
    if not cert.is_file() or not key.is_file():
        if not openssl:
            raise SystemExit("OpenSSL est nécessaire au premier lancement. Indique son chemin avec --openssl.")
        with tempfile.TemporaryDirectory(dir=folder) as temp:
            source_cert, source_key = Path(temp, "cert.pem"), Path(temp, "key.pem")
            subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "825",
                            "-subj", "/CN=CHK Android Emulator", "-keyout", str(source_key), "-out", str(source_cert)],
                           check=True, capture_output=True)
            source_key.replace(key)
            source_cert.replace(cert)
    return cert, key


def find_adb():
    paths = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if os.environ.get("LOCALAPPDATA"):
        paths.append(Path(os.environ["LOCALAPPDATA"], "Android", "Sdk"))
    binary = "adb.exe" if os.name == "nt" else "adb"
    for root in paths:
        if root and Path(root, "platform-tools", binary).is_file():
            return str(Path(root, "platform-tools", binary))
    return shutil.which(binary) or binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="Adresse IPv4 locale de ce PC")
    parser.add_argument("--port", default=8769, type=int)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=find_adb(), help="Chemin de platform-tools/adb")
    parser.add_argument("--openssl", default=shutil.which("openssl"))
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".chk-android-emulator")
    cfg = parser.parse_args()
    cfg.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    cert, key = certificate(cfg.state_dir, cfg.openssl)
    fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
    token = secrets.token_urlsafe(32)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(cert, key)
    with TlsServer((cfg.host, cfg.port), Handler) as server:
        server.tls = tls
        server.device = Emulator(cfg.adb, cfg.serial)
        server.authorization = f"Bearer {token}".encode("ascii")
        server.media_slot = threading.Lock()
        print("Code d’appairage CHK (valable jusqu’à l’arrêt de ce compagnon) :", flush=True)
        print(f"https://{cfg.host}:{server.server_port}|{fingerprint}|{token}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
