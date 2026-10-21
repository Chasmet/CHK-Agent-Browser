"""CHK Home MCP GUI Installer — one click, no CMD/PowerShell window.

This GUI installs the separate 127.0.0.1 Windows MCP service. It does not
modify IIS on 443, the old Android Render connector, or any OpenAI API key.
GitHub Actions packages this GUI and the MCP backend as two real Windows EXEs.
"""
from __future__ import annotations

import hashlib
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import tkinter as tk
import urllib.error
import urllib.request
import webbrowser
import time
import winreg
from pathlib import Path
from tkinter import messagebox, ttk

APP = "CHK Home MCP"
BACKEND_NAME = "CHK-Home-MCP.exe"
WINDOWS_PORT = 8766
PUBLIC_PORT = 8443
VERSION = "1.6"
CLOUDFLARED_URL = "https://github.com/cloudflare/cloudflared/releases/download/2026.9.0/cloudflared-windows-amd64.exe"
CLOUDFLARED_SHA256 = "547057326266f0e1c7d50d102dbd22ff283d740c055bd61e94f10e2c606f89af"
TAILSCALE_CONSOLE = "https://console.tailscale.com/admin/acls"
APP_DIR = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "CHKHomeMCP"
CF_HOST_PATTERN = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com", re.I)
CF_LOG = APP_DIR / "cloudflared-test.log"
CF_STATE = APP_DIR / "cloudflared-test.json"


def hidden_kwargs() -> dict:
    return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)}


def run_quiet(args: list[str], *, timeout: int = 20) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=True, errors="replace",
                          timeout=timeout, check=False, **hidden_kwargs())


def find_tailscale() -> str:
    found = shutil.which("tailscale.exe")
    if found:
        return found
    guesses = [
        Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Tailscale" / "tailscale.exe",
        Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Tailscale" / "tailscale.exe",
        Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "Tailscale" / "tailscale.exe",
    ]
    for name in guesses:
        if name.is_file():
            return str(name)
    raise RuntimeError("Tailscale introuvable sur le PC. Vérifie que Tailscale est installé et connecté.")


def private_config() -> dict:
    config = APP_DIR / "config.json"
    if not config.is_file():
        raise RuntimeError("Configuration MCP privée absente après démarrage.")
    data = json.loads(config.read_text(encoding="utf-8"))
    key = data.get("secret", "")
    if not isinstance(key, str) or not re.fullmatch("[0-9a-f]{64}", key):
        raise RuntimeError("Clé privée locale invalide. Aucun remplacement automatique.")
    return data


def fetch_health() -> dict | None:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{WINDOWS_PORT}/health", timeout=2) as res:
            result = json.load(res)
            if result.get("ok") is True and result.get("server") == APP and result.get("render") is False:
                return result
    except (OSError, ValueError, urllib.error.URLError):
        return None
    return None


def verify_mcp_tools(secret: str) -> int:
    data = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}).encode()
    request = urllib.request.Request(
        f"http://127.0.0.1:{WINDOWS_PORT}/mcp/{secret}",
        data=data, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as res:
            body = json.load(res)
    except (OSError, ValueError) as err:
        raise RuntimeError("Les outils MCP ne répondent pas en local.") from err
    tools = body.get("result", {}).get("tools", [])
    names = {item.get("name") for item in tools if isinstance(item, dict)}
    required = {"server_info", "android_status", "android_command", "android_result",
                "files_list", "files_read_text", "files_write_text", "files_mkdir",
                "files_move", "files_trash", "files_restore", "video_probe", "video_cut"}
    if not required.issubset(names):
        raise RuntimeError("MCP Windows ancien ou incomplet : commandes Android absentes.")
    return len(names)


def same_binary(src: Path, dst: Path) -> bool:
    if not dst.is_file() or src.stat().st_size != dst.stat().st_size:
        return False
    def checksum(path):
        h = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(chunk)
        return h.digest()
    return checksum(src) == checksum(dst)


def upgrade_managed_backend(src: Path, target: Path, progress) -> bool:
    # A running Windows EXE cannot be overwritten. Only stop the known named
    # CHK process if its binary has changed, never any IIS or Tailscale service.
    if same_binary(src, target):
        return False
    if fetch_health() is not None:
        progress("Nouvelle version MCP détectée : arrêt du seul serveur CHK local…")
        result = run_quiet(["taskkill.exe", "/F", "/IM", BACKEND_NAME], timeout=12)
        if result.returncode != 0:
            raise RuntimeError("Impossible d'arrêter l'ancien CHK Home MCP. Ferme son processus puis réessaie.")
        for _ in range(25):
            if fetch_health() is None:
                break
            time.sleep(0.3)
        if fetch_health() is not None:
            raise RuntimeError("Ancien serveur MCP toujours actif : mise à jour interrompue.")
    shutil.copy2(src, target)
    progress("Serveur MCP Windows mis à jour, configuration et clé conservées.")
    return True


def verify_funnel_mcp(dns: str, capability: str, progress) -> None:
    # Verify a full authenticated MCP POST through the configured HTTPS route.
    # This is stronger than the browser GET /mcp/ test. Do not log this URL.
    uri = "https://" + dns + ":8443/mcp/" + capability
    payload = json.dumps({
        "jsonrpc": "2.0", "id": 11, "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "CHK GUI secure diagnostic", "version": VERSION}
        }
    }).encode("utf-8")
    request = urllib.request.Request(uri, data=payload, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"
    })
    progress("Test réel du protocole MCP par HTTPS Funnel (POST)…")
    try:
        with urllib.request.urlopen(request, timeout=13) as res:
            reply = json.load(res)
    except urllib.error.HTTPError as failure:
        raise RuntimeError(
            "Funnel répond mais refuse le protocole MCP POST (HTTP " +
            str(failure.code) + "). Pas encore utilisable dans ChatGPT.") from None
    except (OSError, ValueError, urllib.error.URLError):
        raise RuntimeError(
            "La requête MCP POST ne passe pas sur HTTPS 8443. "
            "Le simple affichage GET dans Chrome ne suffit pas.") from None
    if not isinstance(reply, dict) or reply.get("result", {}).get("serverInfo", {}).get("name") != APP:
        raise RuntimeError(
            "La route HTTPS répond mais n'a pas renvoyé l'initialisation MCP attendue.")
    progress("Initialisation MCP HTTPS vérifiée. Test depuis ChatGPT encore nécessaire.")


def ensure_local_mcp(progress) -> str:
    source = Path(__file__).resolve().parent / BACKEND_NAME
    # When frozen by PyInstaller, sys.executable is GUI in the extracted ZIP.
    import sys
    if getattr(sys, "frozen", False):
        source = Path(sys.executable).resolve().parent / BACKEND_NAME
    if not source.is_file():
        raise RuntimeError("Fichier CHK-Home-MCP.exe introuvable. Extrais le ZIP complet, pas seulement l'installateur.")

    APP_DIR.mkdir(parents=True, exist_ok=True)
    target = APP_DIR / BACKEND_NAME
    progress("Vérification du service Windows local…")
    changed = upgrade_managed_backend(source, target, progress)
    if fetch_health() is None:
        progress("Démarrage du serveur MCP, sans fenêtre de commande…")
        flags = hidden_kwargs()
        subprocess.Popen([str(target)], cwd=str(APP_DIR), stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         close_fds=True, **flags)
        for _ in range(25):
            if fetch_health() is not None:
                break
            time.sleep(0.4)
        if fetch_health() is None:
            raise RuntimeError("Le serveur ne démarre pas sur le port 8766. Vérifie les protections Windows.")
    elif not changed:
        progress("Serveur CHK Home déjà actif : conservation de sa configuration.")

    cfg = private_config()
    count = verify_mcp_tools(cfg["secret"])
    progress(f"{count} outils MCP testés, dont les commandes Android. Render préservé.")
    return cfg["secret"]


def register_autostart() -> None:
    target = APP_DIR / BACKEND_NAME
    with winreg.CreateKeyEx(
        winreg.HKEY_CURRENT_USER,
        r"Software\Microsoft\Windows\CurrentVersion\Run",
        0, winreg.KEY_SET_VALUE
    ) as key:
        # Runs only when the current Windows user logs in; never as SYSTEM.
        winreg.SetValueEx(key, "CHKHomeMCP", 0, winreg.REG_SZ, f'"{target}"')


def tailscale_status() -> tuple[str, str]:
    tailscale = find_tailscale()
    state = run_quiet([tailscale, "status", "--json"], timeout=12)
    if state.returncode != 0:
        raise RuntimeError("Tailscale n'est pas connecté sur ce PC.")
    try:
        payload = json.loads(state.stdout)
        host = payload.get("Self", {}).get("DNSName", "").strip().rstrip(".")
        mode = payload.get("BackendState", "")
    except (ValueError, AttributeError) as err:
        raise RuntimeError("Impossible de lire la connexion Tailscale.") from err
    if not host.endswith(".ts.net") or mode != "Running":
        raise RuntimeError("Tailscale non actif ou nom HTTPS de ce PC introuvable.")
    return tailscale, host


def funnel_state(tailscale: str) -> str:
    # status (plain text) is sufficient for an installer-side safety check.
    result = run_quiet([tailscale, "funnel", "status"], timeout=12)
    if result.returncode != 0:
        raise RuntimeError("Impossible de lire l'état Tailscale Funnel.")
    return result.stdout + result.stderr


def ensure_funnel(tailscale: str, progress) -> None:
    report = funnel_state(tailscale)
    if "8443" in report:
        # An existing 8443 listener must not be overwritten.
        if "8766" in report and "/mcp" in report:
            progress("Tailscale Funnel 8443 semble déjà relié au MCP Windows.")
            return
        raise RuntimeError("Le port Funnel 8443 est déjà utilisé. Aucun réglage existant ne sera écrasé.")
    progress("Publication HTTPS 8443 via Tailscale Funnel (IIS 443 préservé)…")
    # Explicit --yes avoids command prompts. Tailscale must allow Funnel.
    # Funnel's first launch can pause until the tailnet owner approves it in the
    # Tailscale web console; do not display raw subprocess command on timeout.
    progress("Première activation ? Utilise le bouton AUTORISER TAILSCALE, section Funnel.")
    timeout = False
    operation = None
    try:
        operation = run_quiet([tailscale, "funnel", "--bg", "--yes", "--https=8443",
                               "--set-path=/mcp", "http://127.0.0.1:8766"], timeout=70)
    except subprocess.TimeoutExpired:
        timeout = True

    # Approval can finish just before a timeout, so always re-check the actual
    # active route before declaring failure or asking to retry.
    after = ""
    for _ in range(4):
        try:
            after = funnel_state(tailscale)
        except (RuntimeError, subprocess.TimeoutExpired):
            after = ""
        if "8443" in after and "8766" in after and "/mcp" in after:
            break
        time.sleep(1)

    if not ("8443" in after and "8766" in after and "/mcp" in after):
        if timeout:
            raise RuntimeError(
                "Funnel attend trop longtemps. Clique sur AUTORISER TAILSCALE, "
                "puis dans Access controls > Funnel > Add Funnel to policy. "
                "Ensuite reviens cliquer sur INSTALLER ET ACTIVER.")
        if operation is not None and operation.returncode != 0:
            info = (operation.stderr or operation.stdout).strip()
            # Do not expose complete command lines, URLs or credentials in GUI.
            if "permission" in info.lower() or "enable" in info.lower() or "funnel" in info.lower():
                raise RuntimeError(
                    "Funnel doit être autorisé. Clique sur AUTORISER TAILSCALE, "
                    "puis sur Add Funnel to policy et réessaie.")
            raise RuntimeError("Funnel refuse la publication (code " +
                               str(operation.returncode) + "). Vérifie les autorisations Tailscale.")
        raise RuntimeError(
            "Funnel non confirmé sur le port 8443. Clique sur AUTORISER TAILSCALE "
            "pour vérifier les droits, puis réessaie.")
    progress("Tailscale Funnel 8443 configuré. Vérification finale dans ChatGPT nécessaire.")



def checksum_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def get_official_cloudflared(progress) -> Path:
    """Download a pinned Cloudflare binary only after explicit GUI consent."""
    APP_DIR.mkdir(parents=True, exist_ok=True)
    binary = APP_DIR / "cloudflared.exe"
    if binary.is_file() and checksum_file(binary) == CLOUDFLARED_SHA256:
        progress("Client Cloudflare officiel vérifié (SHA-256).")
        return binary
    progress("Téléchargement du client officiel Cloudflare (55 Mo, uniquement pour ce test)…")
    temp = APP_DIR / "cloudflared-download.tmp"
    digest = hashlib.sha256()
    total = 0
    try:
        with urllib.request.urlopen(CLOUDFLARED_URL, timeout=35) as source, temp.open("wb") as output:
            while True:
                block = source.read(1024 * 1024)
                if not block:
                    break
                total += len(block)
                if total > 60 * 1024 * 1024:
                    raise RuntimeError("Le téléchargement Cloudflare dépasse la limite attendue.")
                digest.update(block)
                output.write(block)
        if digest.hexdigest() != CLOUDFLARED_SHA256:
            raise RuntimeError("Empreinte Cloudflare incorrecte. Exécutable non installé.")
        temp.replace(binary)
    except (OSError, urllib.error.URLError) as e:
        raise RuntimeError("Impossible de télécharger le client officiel Cloudflare. Vérifie Internet.") from e
    finally:
        temp.unlink(missing_ok=True)
    progress("Client Cloudflare officiel vérifié et installé dans CHKHomeMCP.")
    return binary


def verify_public_mcp(endpoint: str) -> None:
    """A complete MCP initialize POST through a public HTTPS 443 or 8443 route."""
    rpc = {
        "jsonrpc": "2.0", "id": 110, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "CHK Local GUI", "version": VERSION}}
    }
    request = urllib.request.Request(endpoint, method="POST",
        data=json.dumps(rpc).encode("utf-8"),
        headers={"Accept": "application/json, text/event-stream",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=13) as response:
            data = json.loads(response.read(32768))
    except urllib.error.HTTPError as err:
        raise RuntimeError(
            "Adresse publique joignable mais initialisation MCP refusée (HTTP " +
            str(err.code) + "). Aucun jeton n'a été affiché.") from None
    except (OSError, ValueError):
        raise RuntimeError(
            "Échec de la négociation MCP publique (POST). "
            "Un simple test GET dans Chrome ne garantit pas ChatGPT.") from None
    if not isinstance(data, dict) or data.get("result", {}).get("serverInfo", {}).get("name") != APP:
        raise RuntimeError("La passerelle HTTPS ne répond pas avec le MCP CHK attendu.")


def wait_public_mcp(endpoint: str, progress, *, duration_seconds: int = 115) -> None:
    """Cloudflare quick tunnel public DNS may require >30s after local registration.

    Never log the endpoint: its final path segment is the private MCP capability.
    """
    deadline = time.monotonic() + duration_seconds
    attempts = 0
    last_message = ""
    while True:
        attempts += 1
        try:
            verify_public_mcp(endpoint)
            progress("Initialisation MCP complète validée par HTTPS 443.")
            return
        except RuntimeError as err:
            last_message = str(err)
            if time.monotonic() >= deadline:
                break
            if attempts % 4 == 0:
                progress("Attente de la propagation DNS Cloudflare… (" +
                         str(int(duration_seconds - max(0, deadline - time.monotonic()))) +
                         " secondes)")
            time.sleep(3)
    raise RuntimeError("La validation MCP HTTPS 443 a échoué après la propagation. " +
                       last_message)


def cloudflared_process_alive(pid: object) -> bool:
    """Check only an exact saved PID; never kill or reconfigure other tunnels."""
    if not isinstance(pid, int) or pid <= 0:
        return False
    try:
        result = run_quiet(["tasklist.exe", "/FI", "PID eq " + str(pid),
                            "/FO", "CSV", "/NH"], timeout=7)
        if result.returncode != 0:
            return False
        return bool(re.search(r'"cloudflared\.exe"\s*,\s*"' + str(pid) + r'"',
                              result.stdout, re.I))
    except (OSError, subprocess.TimeoutExpired):
        return False


def start_cloudflare_quick_test(secret: str, progress) -> str:
    """Isolated temporary 443 test, without changing Tailscale Serve or IIS."""
    if fetch_health() is None:
        raise RuntimeError("Démarre d'abord le serveur MCP local.")
    verify_mcp_tools(secret)
    binary = get_official_cloudflared(progress)

    # Previous installer used just 15 seconds to validate a hostname that
    # frequently needs 30-60 seconds of public DNS propagation. Reuse the
    # same running tunnel rather than creating a second one on each retry.
    if CF_STATE.is_file():
        try:
            saved = json.loads(CF_STATE.read_text(encoding="utf-8"))
            base = saved.get("base", "")
            pid = saved.get("pid")
            if CF_HOST_PATTERN.fullmatch(base) and cloudflared_process_alive(pid):
                progress("Passerelle Cloudflare existante détectée : vérification en cours…")
                endpoint = base + "/mcp/" + secret
                wait_public_mcp(endpoint, progress)
                progress("Passerelle temporaire réutilisée sans recréer de tunnel.")
                return endpoint
        except (ValueError, OSError, RuntimeError) as err:
            progress("La précédente passerelle ne répond plus : nouveau test isolé.")
            # Never log any exception containing the full private endpoint.

    progress("Démarrage d'une passerelle temporaire HTTPS 443, indépendante d'IIS…")
    CF_LOG.parent.mkdir(parents=True, exist_ok=True)
    CF_LOG.write_bytes(b"")
    with CF_LOG.open("ab", buffering=0) as output:
        child = subprocess.Popen(
            [str(binary), "tunnel", "--no-autoupdate",
             "--url", "http://127.0.0.1:8766", "--loglevel", "info"],
            cwd=str(APP_DIR), stdin=subprocess.DEVNULL,
            stdout=output, stderr=subprocess.STDOUT,
            close_fds=True, **hidden_kwargs()
        )
    hostname = None
    for _ in range(75):
        if child.poll() is not None:
            raise RuntimeError(
                "La passerelle Cloudflare s'est arrêtée. "
                "Ce mode peut être indisponible sur ton réseau.")
        try:
            logs = CF_LOG.read_bytes()[-25000:].decode("utf-8", errors="replace")
            found = CF_HOST_PATTERN.search(logs)
            if found:
                hostname = found.group(0)
                break
        except OSError:
            pass
        time.sleep(1)
    if not hostname:
        raise RuntimeError("Cloudflare n'a pas fourni d'adresse temporaire après 75 secondes.")

    # Save even before the DNS name works. If this first test times out,
    # retrying the GUI will reuse the same running tunnel instead of spawning
    # an unlimited number of cloudflared processes.
    CF_STATE.write_text(json.dumps({"base": hostname, "pid": child.pid}), encoding="utf-8")
    endpoint = hostname + "/mcp/" + secret
    progress("Adresse HTTPS 443 obtenue. Propagation DNS puis vraie requête MCP POST…")
    wait_public_mcp(endpoint, progress)
    progress("Test MCP HTTPS 443 réussi. URL TEMPORAIRE : elle change si Cloudflare redémarre.")
    return endpoint


class Gui:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("CHK HOME MCP — Sans CMD, sans Render")
        self.root.geometry("760x640")
        self.root.minsize(660, 560)
        self.root.configure(bg="#111827")
        self.messages = queue.Queue()
        self.is_busy = False
        self.url = ""
        self.status_var = tk.StringVar(value="Prêt à installer le second connecteur.")
        self.make_widgets()
        self.root.after(120, self.consume_messages)

    def make_widgets(self):
        tk.Label(self.root, text="CHK  /  HOME MCP", font=("Segoe UI", 21, "bold"),
                 fg="#f8fafc", bg="#111827").pack(anchor="w", padx=28, pady=(24, 2))
        tk.Label(self.root, text="Serveur privé sur ton PC Windows • Aucune fenêtre CMD • 0 heure Render",
                 fg="#cbd5e1", bg="#111827", font=("Segoe UI", 11)).pack(anchor="w", padx=28, pady=(0, 16))
        panel = tk.Frame(self.root, bg="#1f2937", highlightbackground="#374151", highlightthickness=1)
        panel.pack(fill="both", expand=True, padx=28, pady=4)
        tk.Label(panel, text="INSTALLATION EN UN CLIC", bg="#1f2937", fg="#f8fafc",
                 font=("Segoe UI", 13, "bold")).pack(anchor="w", padx=18, pady=(19, 9))
        tk.Label(panel, text="Le programme crée le serveur MCP local, teste ses 10 outils puis\n"
                            "configure Tailscale HTTPS sur le port 8443. IIS 443 et le premier\n"
                            "connecteur CHK Agent Browser/Render restent inchangés.",
                 bg="#1f2937", fg="#cbd5e1", justify="left", font=("Segoe UI", 10)).pack(anchor="w", padx=18)
        row = tk.Frame(panel, bg="#1f2937")
        row.pack(fill="x", padx=18, pady=(18, 12))
        self.install_button = tk.Button(row, text="INSTALLER ET ACTIVER", relief="flat",
                                       bg="#3b82f6", fg="#ffffff", activebackground="#2563eb",
                                       font=("Segoe UI", 11, "bold"), padx=16, pady=11,
                                       command=self.confirm_install)
        self.install_button.pack(side="left")
        self.diagnostic_button = tk.Button(row, text="VÉRIFIER", relief="flat",
                                          bg="#374151", fg="white", padx=14, pady=11,
                                          font=("Segoe UI", 10, "bold"),
                                          command=self.diagnose)
        self.diagnostic_button.pack(side="left", padx=12)
        self.copy_button = tk.Button(row, text="COPIER MON LIEN MCP", relief="flat",
                                     bg="#059669", fg="white", padx=14, pady=11,
                                     state="disabled", font=("Segoe UI", 10, "bold"),
                                     command=self.copy_url)
        self.copy_button.pack(side="left")
        auth_row = tk.Frame(panel, bg="#1f2937")
        auth_row.pack(fill="x", padx=18, pady=(0, 12))
        self.auth_button = tk.Button(
            auth_row, text="AUTORISER TAILSCALE FUNNEL (NAVIGATEUR)",
            relief="flat", bg="#a16207", fg="white", activebackground="#854d0e",
            font=("Segoe UI", 10, "bold"), padx=12, pady=8,
            command=self.open_tailscale_authorization)
        self.auth_button.pack(side="left")
        self.cloudflare_button = tk.Button(
            panel, text="TESTER UNE ADRESSE HTTPS 443 (CLOUDFLARE, PROVISOIRE)",
            relief="flat", bg="#6d28d9", fg="white", activebackground="#5b21b6",
            font=("Segoe UI", 10, "bold"), padx=13, pady=10,
            command=self.confirm_cloudflare)
        self.cloudflare_button.pack(anchor="w", padx=18, pady=(0, 10))
        tk.Label(panel, textvariable=self.status_var, font=("Segoe UI", 10, "bold"),
                 bg="#1f2937", fg="#93c5fd", wraplength=640,
                 justify="left").pack(anchor="w", padx=18, pady=(2, 10))
        self.log = tk.Text(panel, bg="#111827", fg="#e2e8f0",
                           insertbackground="white", relief="flat",
                           font=("Consolas", 10), wrap="word", padx=10, pady=10, height=10)
        self.log.pack(fill="both", expand=True, padx=18, pady=(0, 15))
        self.log.configure(state="disabled")
        tk.Label(self.root, text="Ton lien secret sera copié sans être affiché. Ne le partage pas.",
                 bg="#111827", fg="#9ca3af", font=("Segoe UI", 10)).pack(anchor="w", padx=28, pady=(10, 18))

    def log_line(self, text):
        # Never include private config, tokens, or full URL in output.
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def msg(self, text):
        self.messages.put(("log", text))

    def run_task(self, callback):
        if self.is_busy:
            return
        self.is_busy = True
        self.install_button.configure(state="disabled")
        self.diagnostic_button.configure(state="disabled")
        self.cloudflare_button.configure(state="disabled")
        self.copy_button.configure(state="disabled")
        self.log_line("────────────────────────────────────────────────────────")
        threading.Thread(target=self.worker, args=(callback,), daemon=True).start()

    def worker(self, callback):
        try:
            callback()
        except Exception as err:
            # Error messages generated here contain no secret, unlike Tailscale raw logs.
            self.messages.put(("failed", str(err)))
        finally:
            self.messages.put(("done", None))

    def confirm_install(self):
        if not messagebox.askyesno(
                "Autoriser CHK Home MCP",
                "Installer le serveur sur ce PC et publier UNIQUEMENT son chemin MCP\n"
                "sur le port HTTPS 8443 via Tailscale Funnel ?\n\n"
                "Le lien contient un jeton privé. Quiconque le possède pourrait\n"
                "accéder aux fichiers du dossier CHK-Workspace.\n\n"
                "Ton tableau de bord IIS 443 et le premier MCP Render ne seront pas modifiés."
        ):
            return
        self.run_task(self.install)

    def install(self):
        self.msg("1/4 — Vérification de l'exécutable Windows…")
        secret = ensure_local_mcp(self.msg)
        self.msg("2/4 — Démarrage automatique (session Windows)…")
        register_autostart()
        self.msg("3/4 — Recherche de Tailscale et de son adresse HTTPS…")
        tailscale, dns = tailscale_status()
        self.msg("Tailscale connecté sur : " + dns)
        self.msg("4/4 — Activation et contrôle de Funnel sur 8443…")
        ensure_funnel(tailscale, self.msg)
        verify_funnel_mcp(dns, secret, self.msg)
        # Credential is intentionally NEVER displayed, printed or logged.
        private_url = f"https://{dns}:8443/mcp/{secret}"
        self.messages.put(("ready", private_url))

    def open_tailscale_authorization(self):
        # Browser-only account approval, no CMD and no programmatic modification
        # of the private tailnet's access-control policy.
        webbrowser.open(TAILSCALE_CONSOLE, new=2)
        self.log_line("Ouvre Tailscale > Access controls > Funnel.")
        self.log_line("Clique sur Add Funnel to policy si le bouton apparaît.")
        self.log_line("Puis reviens ici et clique sur INSTALLER ET ACTIVER.")

    def confirm_cloudflare(self):
        if not messagebox.askyesno(
            "Test externe temporaire - Cloudflare",
            "Tester CHK Home MCP avec une adresse HTTPS publique sur le port 443 ?\n\n"
            "Le PC reste l'hôte, mais Cloudflare transmettra les requêtes.\n"
            "Ce service est distinct de Tailscale, IIS et Render.\n\n"
            "L'URL sera TEMPORAIRE : elle changera si le processus s'arrête\n"
            "ou après un redémarrage Windows. Le jeton MCP privé y sera intégré.\n\n"
            "Ce test sert seulement à vérifier l'accès depuis ChatGPT. Continuer ?"
        ):
            return
        self.run_task(self.cloudflare_worker)

    def cloudflare_worker(self):
        secret = ensure_local_mcp(self.msg)
        register_autostart()
        endpoint = start_cloudflare_quick_test(secret, self.msg)
        self.messages.put(("cloudflare_ready", endpoint))

    def diagnose(self):
        self.run_task(self.diagnostic_worker)

    def diagnostic_worker(self):
        self.msg("État local MCP : " + ("ACTIF" if fetch_health() is not None else "INACTIF"))
        cfg = APP_DIR / "config.json"
        if cfg.is_file() and fetch_health():
            verify_mcp_tools(private_config()["secret"])
            self.msg("Protocole MCP : 10 outils répondent correctement.")
        tailscale, dns = tailscale_status()
        self.msg("Tailscale : connecté (" + dns + ")")
        status = funnel_state(tailscale)
        if "8443" in status and "8766" in status:
            self.msg("Funnel 8443 : route MCP détectée.")
            if cfg.is_file() and fetch_health():
                secret = private_config()["secret"]
                verify_funnel_mcp(dns, secret, self.msg)
                self.messages.put(("ready", f"https://{dns}:8443/mcp/{secret}"))
        else:
            self.msg("Funnel 8443 : non configuré pour ce serveur.")
        self.msg("Aucun état du connecteur Render n'a été modifié.")

    def consume_messages(self):
        try:
            while True:
                action, data = self.messages.get_nowait()
                if action == "log":
                    self.log_line(data)
                    self.status_var.set(data)
                elif action == "ready":
                    self.url = data
                    self.copy_button.configure(state="normal")
                    self.log_line("Installation locale et configuration Funnel détectées.")
                    self.log_line("Clique sur COPIER MON LIEN MCP, puis colle-le dans ChatGPT.")
                    self.log_line("La connexion depuis les serveurs ChatGPT doit encore être validée.")
                    self.status_var.set("MCP prêt pour l'essai depuis ChatGPT.")
                elif action == "cloudflare_ready":
                    self.url = data
                    self.copy_button.configure(state="normal")
                    self.status_var.set("Test HTTPS 443 prêt : lien TEMPORAIRE à essayer dans ChatGPT.")
                    self.log_line("Clique sur COPIER MON LIEN MCP. Cette URL Cloudflare est TEMPORAIRE.")
                    self.log_line("Si le PC ou la passerelle redémarre, ce lien pourra changer.")
                    self.log_line("Aucune modification des ports Tailscale, IIS ou Render.")
                elif action == "failed":
                    self.status_var.set("Installation incomplète — voir le diagnostic ci-dessous.")
                    self.log_line("PROBLÈME : " + data)
                    self.log_line("Ton IIS 443 et ton MCP Render ont été conservés.")
                elif action == "done":
                    self.is_busy = False
                    self.install_button.configure(state="normal")
                    self.diagnostic_button.configure(state="normal")
                    self.cloudflare_button.configure(state="normal")
                    if self.url:
                        self.copy_button.configure(state="normal")
        except queue.Empty:
            pass
        self.root.after(120, self.consume_messages)

    def copy_url(self):
        if not self.url:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(self.url)
        self.root.update()
        self.status_var.set("Lien privé copié ! Dans ChatGPT, utilise le champ URL du serveur.")
        self.log_line("Lien secret copié (masqué à l'écran).")

    def start(self):
        self.root.mainloop()


if __name__ == "__main__":
    Gui().start()
