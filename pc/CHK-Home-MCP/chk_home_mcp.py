"""CHK Home MCP — Windows local server, no Render and no OpenAI API key.

Python standard library, stateless MCP Streamable HTTP JSON responses.
Binds to 127.0.0.1:8766; a separately configured Tailscale Funnel can expose
the MCP on dedicated HTTPS port 8443 without changing IIS port 443.

Keep public write tools confined to the owner-selected workspace, and NEVER
execute arbitrary shell/PowerShell instructions received through MCP.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import logging
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

APP_VERSION = "1.0.0"
PORT = 8766
MAX_POST = 96 * 1024
MAX_TEXT = 64 * 1024
MAX_RESPONSE_TEXT = 24_000
MAX_PEERS = threading.BoundedSemaphore(12)
MAX_LIST = 200

def user_storage() -> Path:
    return Path(os.environ.get("LOCALAPPDATA") or Path.home() / ".local" / "share") / "CHKHomeMCP"

def config_file() -> Path:
    return user_storage() / "config.json"

def init_config() -> dict:
    folder = user_storage()
    folder.mkdir(parents=True, exist_ok=True)
    file = config_file()
    if file.exists():
        data = json.loads(file.read_text("utf-8"))
        if not (isinstance(data, dict) and isinstance(data.get("secret"), str)
                and len(data["secret"]) == 64 and all(c in "0123456789abcdef" for c in data["secret"])
                and isinstance(data.get("workspace"), str)):
            raise ValueError("Configuration privée invalide : ne pas la réinitialiser automatiquement.")
    else:
        workspace = Path.home() / "Documents" / "CHK-Workspace"
        data = {"version": 1, "secret": secrets.token_hex(32), "workspace": str(workspace)}
        # Atomic create: avoid overwriting an existing secret under concurrent launches.
        with file.open("x", encoding="utf-8") as stream:
            json.dump(data, stream)
        try: os.chmod(file, 0o600)
        except OSError: pass
    root = Path(data["workspace"]).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    data["root"] = root
    return data

def workspace_path(root: Path, name: str, *, allow_root: bool = False) -> Path:
    if not isinstance(name, str) or len(name) > 800 or "\x00" in name:
        raise ValueError("Chemin invalide.")
    # Windows absolute paths and drive-relative paths are forbidden.
    requested = Path(name.replace("/", os.sep))
    if requested.is_absolute() or requested.drive:
        raise ValueError("Chemin absolu interdit.")
    candidate = (root / requested).resolve()
    common = os.path.commonpath((str(root), str(candidate)))
    if os.path.normcase(common) != os.path.normcase(str(root)):
        raise ValueError("Chemin hors de l'espace CHK.")
    if not allow_root and candidate == root:
        raise ValueError("Dossier racine protégé.")
    if candidate == root / ".trash" or root / ".trash" in candidate.parents:
        raise ValueError("Corbeille protégée.")
    return candidate

def relative(root: Path, path: Path) -> str:
    return str(path.relative_to(root)).replace("\\", "/")

def read_json_object(s: str) -> dict:
    obj = json.loads(s)
    if not isinstance(obj, dict):
        raise ValueError("Objet JSON obligatoire.")
    return obj

def safe_tool_action(name: str, arguments: dict, cfg: dict) -> object:
    if not isinstance(arguments, dict):
        raise ValueError("Arguments invalides.")
    root = cfg["root"]
    path_name = arguments.get("path", "")
    if name == "server_info":
        return {"name": "CHK Home MCP", "version": APP_VERSION,
                "execution": "Windows PC", "hosting": "local", "render": False,
                "workspace": str(root), "tools": len(tool_catalog())}

    if name == "files_list":
        folder = workspace_path(root, path_name, allow_root=True)
        if not folder.is_dir():
            raise ValueError("Dossier introuvable.")
        entries = []
        for file in sorted(folder.iterdir(), key=lambda p: (not p.is_dir(), p.name.casefold())):
            if file.name == ".trash" or file.is_symlink():
                continue
            entries.append({"name": file.name, "path": relative(root, file),
                            "type": "folder" if file.is_dir() else "file",
                            "bytes": file.stat().st_size if file.is_file() else None})
            if len(entries) >= MAX_LIST:
                break
        return {"directory": relative(root, folder), "items": entries, "max": MAX_LIST}

    if name == "files_read_text":
        file = workspace_path(root, path_name)
        if not file.is_file() or file.stat().st_size > MAX_RESPONSE_TEXT:
            raise ValueError("Fichier absent ou supérieur à 24 000 octets.")
        return {"path": relative(root, file), "content": file.read_text("utf-8")}

    if name == "files_write_text":
        content = arguments.get("content")
        if not isinstance(content, str) or len(content.encode("utf-8")) > MAX_TEXT:
            raise ValueError("Texte absent ou supérieur à 64 Ko.")
        target = workspace_path(root, path_name)
        if not target.parent.is_dir():
            raise ValueError("Dossier parent introuvable.")
        if target.exists() and not arguments.get("overwrite", False):
            raise ValueError("Fichier déjà présent ; préciser overwrite=true.")
        if target.is_dir():
            raise ValueError("Destination est un dossier.")
        # Never write directly; keep old content if the operation is interrupted.
        fd, tmp = tempfile.mkstemp(prefix=".chk-", dir=target.parent)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(content.encode("utf-8"))
            if target.exists() and not arguments.get("overwrite", False):
                raise ValueError("Destination occupée.")
            os.replace(tmp, target)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
        return {"ok": True, "path": relative(root, target), "bytes": len(content.encode("utf-8"))}

    if name == "files_mkdir":
        folder = workspace_path(root, path_name)
        folder.mkdir(parents=True, exist_ok=False)
        return {"ok": True, "path": relative(root, folder)}

    if name == "files_move":
        src = workspace_path(root, arguments.get("source", ""))
        dst = workspace_path(root, arguments.get("destination", ""))
        if not src.exists() or dst.exists() or not dst.parent.is_dir():
            raise ValueError("Source absente, destination présente ou dossier parent absent.")
        if src in dst.parents:
            raise ValueError("Déplacement récursif interdit.")
        src.rename(dst)
        return {"ok": True, "new_path": relative(root, dst)}

    if name == "files_trash":
        src = workspace_path(root, path_name)
        if not src.exists():
            raise ValueError("Élément introuvable.")
        trash = root / ".trash"
        trash.mkdir(exist_ok=True)
        entry = secrets.token_hex(10)
        target = trash / entry
        src.rename(target)
        metadata = trash / (entry + ".json")
        metadata.write_text(json.dumps({"original": relative(root, src), "name": src.name},
                                       ensure_ascii=False), "utf-8")
        return {"ok": True, "trash_id": entry, "recoverable": True}

    if name == "files_restore":
        ident = arguments.get("trash_id", "")
        if not isinstance(ident, str) or len(ident) != 20 or any(ch not in "0123456789abcdef" for ch in ident):
            raise ValueError("Identifiant de corbeille invalide.")
        trash = root / ".trash"
        file = trash / ident
        meta = trash / (ident + ".json")
        if not file.exists() or not meta.is_file():
            raise ValueError("Élément supprimé introuvable.")
        original = read_json_object(meta.read_text("utf-8"))["original"]
        restored = workspace_path(root, original)
        if restored.exists() or not restored.parent.is_dir():
            raise ValueError("Destination de restauration occupée ou absente.")
        file.rename(restored)
        meta.unlink()
        return {"ok": True, "restored": relative(root, restored)}

    if name == "video_probe":
        src = workspace_path(root, path_name)
        if not src.is_file(): raise ValueError("Vidéo absente.")
        ffprobe = shutil.which("ffprobe")
        if not ffprobe:
            raise ValueError("FFprobe n'est pas installé sur Windows.")
        result = subprocess.run([ffprobe, "-v", "error", "-show_format", "-show_streams",
                                 "-of", "json", str(src)], capture_output=True, text=True,
                                timeout=30, check=False)
        if result.returncode:
            raise ValueError("FFprobe a refusé ce média.")
        return json.loads(result.stdout[:30_000])

    if name == "video_cut":
        src = workspace_path(root, path_name)
        dst = workspace_path(root, arguments.get("output", ""))
        if not src.is_file() or dst.exists() or not dst.parent.is_dir() or dst.suffix.lower() != ".mp4":
            raise ValueError("Vidéo source ou destination MP4 invalide.")
        start = arguments.get("start_seconds", -1)
        duration = arguments.get("duration_seconds", -1)
        if type(start) not in (int, float) or type(duration) not in (int, float):
            raise ValueError("Temps numérique requis.")
        if not (0 <= start <= 86400 and 0 < duration <= 3600):
            raise ValueError("Intervalle de découpe invalide.")
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg: raise ValueError("FFmpeg n'est pas installé sur Windows.")
        # No shell=True, no arbitrary filter or executable controlled through MCP.
        cmd = [ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
               "-ss", str(start), "-i", str(src), "-t", str(duration),
               "-map", "0:v:0", "-map", "0:a?", "-c:v", "libx264",
               "-preset", "fast", "-crf", "23", "-c:a", "aac",
               "-movflags", "+faststart", str(dst)]
        try:
            job = subprocess.run(cmd, capture_output=True, text=True, timeout=300,
                                 check=False)
            if job.returncode or not dst.is_file() or dst.stat().st_size < 100:
                dst.unlink(missing_ok=True)
                raise ValueError("FFmpeg n'a pas pu exporter cette vidéo.")
        except subprocess.TimeoutExpired:
            dst.unlink(missing_ok=True)
            raise ValueError("Découpage trop long (maximum 5 minutes).")
        return {"ok": True, "output": relative(root, dst), "bytes": dst.stat().st_size}

    raise ValueError("Outil MCP non autorisé.")

def tool_catalog() -> list[dict]:
    def schema(properties=None, required=()):
        return {"type": "object", "properties": properties or {},
                "required": list(required), "additionalProperties": False}
    def text(description): return {"type": "string", "description": description}
    def number(description): return {"type": "number", "description": description}
    items = [
        ("server_info", "État du MCP Windows, sans Render.", {}),
        ("files_list", "Liste un dossier autorisé dans CHK-Workspace.", {"path": text("Chemin relatif, vide pour la racine.")}),
        ("files_read_text", "Lit un fichier texte UTF-8 de 24 Ko maximum.", {"path": text("Chemin relatif.")}),
        ("files_write_text", "Écrit un document texte, sans remplacement par défaut.",
         {"path": text("Chemin relatif."), "content": text("UTF-8"), "overwrite": {"type": "boolean"}}),
        ("files_mkdir", "Crée un nouveau dossier CHK.", {"path": text("Chemin relatif.")}),
        ("files_move", "Déplace ou renomme un élément sans écraser.", {
            "source": text("Source relative."), "destination": text("Destination relative.")}),
        ("files_trash", "Envoie un élément à la corbeille récupérable du MCP.", {"path": text("Chemin relatif.")}),
        ("files_restore", "Restaure un élément supprimé par le MCP.", {"trash_id": text("Identifiant de corbeille.")}),
        ("video_probe", "Analyse les pistes d'un média avec FFprobe local.", {"path": text("Chemin vidéo.")}),
        ("video_cut", "Découpe et exporte une vidéo MP4 avec FFmpeg local.", {
            "path": text("Vidéo source."), "output": text("MP4 de sortie relatif."),
            "start_seconds": number("Début en secondes."),
            "duration_seconds": number("Durée en secondes.")}),
    ]
    required = {
        "files_read_text": ("path",),
        "files_write_text": ("path", "content"),
        "files_mkdir": ("path",),
        "files_move": ("source", "destination"),
        "files_trash": ("path",),
        "files_restore": ("trash_id",),
        "video_probe": ("path",),
        "video_cut": ("path", "output", "start_seconds", "duration_seconds"),
    }
    return [{"name": name, "description": desc, "inputSchema": schema(props, required.get(name, ()))}
            for name, desc, props in items]

def error(code: int, message: str, identifier=None) -> dict:
    return {"jsonrpc": "2.0", "id": identifier,
            "error": {"code": code, "message": message}}

def dispatch(request: dict, cfg: dict) -> dict | None:
    identifier = request.get("id")
    if request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
        return error(-32600, "Requête JSON-RPC invalide.", identifier)
    method = request["method"]
    if identifier is None:
        return None  # JSON-RPC notifications
    params = request.get("params") or {}
    if not isinstance(params, dict):
        return error(-32602, "Paramètres invalides.", identifier)
    if method == "initialize":
        asked = params.get("protocolVersion", "2025-06-18")
        version = asked if asked in ("2025-03-26", "2025-06-18", "2025-11-25") else "2025-06-18"
        result = {"protocolVersion": version, "capabilities": {"tools": {"listChanged": False}},
                  "serverInfo": {"name": "CHK Home MCP", "version": APP_VERSION}}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": tool_catalog()}
    elif method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        try:
            answer = safe_tool_action(name, args, cfg)
            result = {"content": [{"type": "text", "text": json.dumps(answer, ensure_ascii=False)}],
                      "isError": False}
        except (ValueError, OSError, UnicodeError, subprocess.SubprocessError) as e:
            result = {"content": [{"type": "text", "text": str(e)[:250]}], "isError": True}
    else:
        return error(-32601, "Méthode MCP inconnue.", identifier)
    return {"jsonrpc": "2.0", "id": identifier, "result": result}

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CHKHomeMCP"
    def log_message(self, fmt, *args):
        # Request paths include a private capability; NEVER log them.
        pass

    def response(self, status: int, data: dict | None):
        raw = json.dumps(data or {}, ensure_ascii=False).encode("utf-8") if data is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        if raw: self.wfile.write(raw)

    def do_GET(self):
        if urlsplit(self.path).path == "/health":
            self.response(200, {"ok": True, "server": "CHK Home MCP",
                                "version": APP_VERSION, "render": False,
                                "authenticated_tools": True})
        else:
            self.response(405, {"error": "Only POST MCP operations"})

    def do_POST(self):
        if not MAX_PEERS.acquire(blocking=False):
            self.response(503, {"error": "Serveur occupé"})
            return
        try: self._handle_post()
        finally: MAX_PEERS.release()

    def _handle_post(self):
        parsed = urlsplit(self.path)
        path = unquote(parsed.path).rstrip("/")
        capability = self.server.cfg["secret"]
        # Some Tailscale versions remove the --set-path mount prefix.
        allowed_paths = ("/mcp/" + capability, "/" + capability)
        bearer = self.headers.get("Authorization", "")
        authenticated = hmac.compare_digest(path, "/mcp") and hmac.compare_digest(bearer, "Bearer " + capability)
        authenticated |= any(hmac.compare_digest(path, item) for item in allowed_paths)
        if not authenticated:
            self.response(401, {"error": "MCP authentication required"})
            return
        if self.headers.get("Origin"):
            self.response(403, {"error": "Browser Origin not permitted"})
            return
        if self.headers.get("Transfer-Encoding"):
            self.response(400, {"error": "Chunked upload not supported"})
            return
        try: size = int(self.headers.get("Content-Length", "-1"))
        except ValueError: size = -1
        if size < 0 or size > MAX_POST:
            self.response(413, {"error": "Payload exceeds limit"})
            return
        if not self.headers.get("Content-Type", "").split(";", 1)[0].lower() == "application/json":
            self.response(415, {"error": "application/json required"})
            return
        data = self.rfile.read(size)
        if len(data) != size:
            self.response(400, {"error": "Incomplete HTTP body"})
            return
        try: request = read_json_object(data.decode("utf-8"))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            self.response(400, error(-32700, "Invalid JSON"))
            return
        reply = dispatch(request, self.server.cfg)
        if reply is None: self.response(202, None)
        else: self.response(200, reply)

class LocalMcpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

def main() -> None:
    cli = argparse.ArgumentParser(description="CHK Home MCP, self-hosted on Windows")
    cli.add_argument("--show-local-status", action="store_true")
    cli.add_argument("--show-private-url", action="store_true", help="Prints SECRET URL to owner console only.")
    cli.add_argument("--once-test", action="store_true", help="Run internal handler smoke test.")
    options = cli.parse_args()
    cfg = init_config()
    if options.show_private_url:
        print("PRIVATE PATH: /mcp/" + cfg["secret"])
        print("Use HTTPS 8443 URL on pc-salon only after Tailscale Funnel is enabled.")
        return
    if options.show_local_status:
        print(json.dumps(safe_tool_action("server_info", {}, cfg), ensure_ascii=False))
        return
    if options.once_test:
        result = dispatch({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, cfg)
        assert len(result["result"]["tools"]) == 10
        assert safe_tool_action("server_info", {}, cfg)["render"] is False
        print("OK — 10 MCP tools; no Render client or polling.")
        return
    server = LocalMcpServer(("127.0.0.1", PORT), Handler)
    server.cfg = cfg
    print("CHK Home MCP running on localhost:" + str(PORT) + " (no Render).", flush=True)
    try: server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == "__main__":
    main()
