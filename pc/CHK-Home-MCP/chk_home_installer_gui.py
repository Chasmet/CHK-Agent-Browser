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
VERSION = "1.3"
TAILSCALE_CONSOLE = "https://console.tailscale.com/admin/acls"
APP_DIR = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "CHKHomeMCP"


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


def verify_mcp_tools(secret: str) -> None:
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
    if len(tools) != 10:
        raise RuntimeError("Réponse MCP incorrecte : les dix outils ne sont pas présents.")


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
    verify_mcp_tools(cfg["secret"])
    progress("10 outils MCP testés avec succès. Aucun appel Render.")
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


class Gui:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("CHK HOME MCP — Sans CMD, sans Render")
        self.root.geometry("740x560")
        self.root.minsize(640, 485)
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
                elif action == "failed":
                    self.status_var.set("Installation incomplète — voir le diagnostic ci-dessous.")
                    self.log_line("PROBLÈME : " + data)
                    self.log_line("Ton IIS 443 et ton MCP Render ont été conservés.")
                elif action == "done":
                    self.is_busy = False
                    self.install_button.configure(state="normal")
                    self.diagnostic_button.configure(state="normal")
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
