"""Durable, asynchronous copies between the authorized PC workspace and Android.

Only file bytes cross the existing authenticated mailbox. No shell, cloud storage,
new listener or credential is created. Resume reconciles Android's checkpoint.
"""
from __future__ import annotations
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import threading

MAX_BYTES = 512 * 1024 * 1024
_lock = threading.RLock()
_running: dict[str, threading.Event] = {}


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            value.update(block)
    return value.hexdigest()


def save(path: Path, state: dict) -> None:
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(state, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def request(queue, action: str, args: dict) -> dict:
    ident = queue.enqueue("android_action", {"action": action, "args": args})
    answer = queue.await_result(ident, timeout=180)
    if answer.get("state") != "done" or answer.get("success") is not True:
        raise ValueError("Commande Android non confirmée ; reprendre ce transfert après vérification.")
    result = json.loads(answer["message"])
    if not isinstance(result, dict) or result.get("ok") is False:
        raise ValueError("Résultat Android refusé ou invalide.")
    return result


def command(args: dict, cfg: dict, queue, resolve_path) -> dict:
    ident, operation = args.get("id", ""), args.get("operation", "status")
    if not isinstance(ident, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", ident):
        raise ValueError("Identifiant de transfert : 1 à 64 lettres/chiffres/tirets.")
    if operation not in ("start", "status", "resume", "cancel"):
        raise ValueError("Opération de transfert invalide.")
    root = Path(cfg["root"]).resolve()
    folder = root / ".transfers"
    folder.mkdir(exist_ok=True)
    if folder.is_symlink():
        raise ValueError("Journal de transfert invalide.")
    journal = folder / (ident + ".json")
    key = str(journal)
    with _lock:
        if operation == "start":
            direction, source, destination = args.get("direction"), args.get("source"), args.get("destination")
            if direction not in ("to_android", "to_pc") or not all(isinstance(p, str) and p for p in (source, destination)):
                raise ValueError("direction to_android/to_pc, source et destination requises.")
            resolve_path(root, source if direction == "to_android" else destination)
            if journal.exists():
                state = json.loads(journal.read_text("utf-8"))
                if any(state.get(k) != v for k, v in (("direction", direction), ("source", source), ("destination", destination))):
                    raise ValueError("Identifiant utilisé pour un autre transfert.")
            else:
                for other in folder.glob("*.json"):
                    prior = json.loads(other.read_text("utf-8"))
                    if prior.get("direction") == direction and prior.get("destination") == destination and prior.get("state") in ("ready", "running", "paused"):
                        raise ValueError("Destination réservée par un transfert précédent.")
                state = dict(id=ident, direction=direction, source=source, destination=destination,
                             state="ready", received=0)
                save(journal, state)
        else:
            if not journal.is_file():
                raise ValueError("Transfert inconnu.")
            state = json.loads(journal.read_text("utf-8"))
        if operation == "cancel":
            if state["state"] == "completed":
                raise ValueError("Transfert déjà terminé ; utiliser la corbeille pour retirer le fichier.")
            if key in _running:
                _running[key].set()
                return {**state, "cancellation_requested": True}
            state["state"] = "cancelled"
            (folder / (ident + ".part")).unlink(missing_ok=True)
            save(journal, state)
            if state["direction"] == "to_android":
                stop = threading.Event()
                stop.set()
                _running[key] = stop
                threading.Thread(target=_run, args=(journal, state, cfg, queue, resolve_path, stop), daemon=True).start()
        elif operation in ("start", "resume") and state["state"] != "completed":
            if state["state"] == "cancelled":
                raise ValueError("Transfert annulé : utiliser un nouvel identifiant.")
            if key not in _running:
                if len(_running) >= 2:
                    raise ValueError("Deux transferts sont déjà actifs ; attendre leur résultat.")
                stop = threading.Event()
                _running[key] = stop
                state["state"] = "running"
                state.pop("error", None)
                save(journal, state)
                threading.Thread(target=_run, args=(journal, state, cfg, queue, resolve_path, stop), daemon=True).start()
        elif state["state"] == "running" and key not in _running:
            state["state"] = "paused"
            save(journal, state)
        return dict(state)


def _run(journal: Path, state: dict, cfg: dict, queue, resolve_path, stop: threading.Event) -> None:
    def checkpoint():
        if stop.is_set():
            raise InterruptedError("Annulation demandée.")
        with _lock:
            save(journal, state)
    try:
        checkpoint()
        root = Path(cfg["root"]).resolve()
        part = journal.with_suffix(".part")
        if state["direction"] == "to_android":
            source = resolve_path(root, state["source"])
            if not source.is_file() or source.stat().st_size > MAX_BYTES:
                raise ValueError("Fichier Windows absent ou supérieur à 512 Mo.")
            size, modified = source.stat().st_size, source.stat().st_mtime_ns
            sha = digest(source)
            if "sha256" in state and (state["sha256"] != sha or state["size"] != size):
                raise ValueError("Source Windows modifiée depuis le début du transfert.")
            state.update(size=size, sha256=sha)
            checkpoint()
            remote = request(queue, "files_transfer_begin", dict(id=state["id"], path=state["destination"], size=size, sha256=sha))
            if remote.get("state") == "committed":
                state.update(state="completed", received=size, verified=True)
            else:
                offset = remote["received"]
                if type(offset) is not int or not 0 <= offset <= size:
                    raise ValueError("Point de reprise Android invalide.")
                with source.open("rb") as stream:
                    stream.seek(offset)
                    while offset < size:
                        checkpoint()
                        if source.stat().st_size != size or source.stat().st_mtime_ns != modified:
                            raise ValueError("Source Windows modifiée pendant le transfert.")
                        block = stream.read(min(49152, size - offset))
                        if not block:
                            raise ValueError("Fin du fichier source inattendue.")
                        remote = request(queue, "files_transfer_write", dict(id=state["id"], offset=offset,
                            base64=base64.b64encode(block).decode("ascii"), chunk_sha256=hashlib.sha256(block).hexdigest()))
                        offset += len(block)
                        if remote.get("received") != offset:
                            raise ValueError("Accusé du bloc Android incohérent.")
                        state["received"] = offset
                        checkpoint()
                if digest(source) != sha:
                    raise ValueError("Source Windows modifiée : aucun fichier publié sur Android.")
                checkpoint()
                remote = request(queue, "files_transfer_commit", dict(id=state["id"]))
                if remote.get("verified") is not True or remote.get("sha256") != sha:
                    raise ValueError("Publication Android non confirmée.")
                state.update(state="completed", verified=True)
        else:
            target = resolve_path(root, state["destination"])
            remote = request(queue, "files_hash", dict(path=state["source"]))
            size, modified, sha = remote["size"], remote["updated"], remote["sha256"]
            if type(size) is not int or not 0 <= size <= MAX_BYTES or not re.fullmatch("[a-f0-9]{64}", sha):
                raise ValueError("Métadonnées Android invalides.")
            if "sha256" in state and (sha != state["sha256"] or size != state["size"]):
                raise ValueError("Source Android modifiée depuis le début du transfert.")
            state.update(size=size, sha256=sha)
            if state.get("publishing") and not part.exists() and target.is_file() and digest(target) == sha:
                state.update(state="completed", received=size, verified=True)
            else:
                if target.exists() or not target.parent.is_dir():
                    raise ValueError("Destination Windows existante ou parent absent.")
                received = state["received"]
                if received and (not part.exists() or part.stat().st_size < received):
                    raise ValueError("Bloc confirmé manquant ; annuler le transfert.")
                with part.open("r+b" if part.exists() else "w+b") as stream:
                    stream.truncate(received)
                    stream.seek(received)
                    while received < size:
                        checkpoint()
                        reply = request(queue, "files_transfer_read", dict(path=state["source"], offset=received, size=size, updated=modified))
                        block = base64.b64decode(reply["base64"], validate=True)
                        if not 0 < len(block) <= min(8192, size - received) or reply.get("offset") != received or hashlib.sha256(block).hexdigest() != reply.get("chunk_sha256"):
                            raise ValueError("Bloc Android corrompu ou position incorrecte.")
                        stream.write(block)
                        stream.flush()
                        os.fsync(stream.fileno())
                        received += len(block)
                        state["received"] = received
                        checkpoint()
                if digest(part) != sha:
                    raise ValueError("SHA-256 final Windows incorrect : aucun fichier publié.")
                state["publishing"] = True
                checkpoint()
                # Windows rename refuses an occupied destination; do not use os.replace.
                if target.exists():
                    raise ValueError("Destination Windows occupée.")
                part.rename(target)
                state.update(state="completed", verified=True)
    except Exception as error:
        state.update(state="cancelled" if stop.is_set() else "paused", error=str(error)[:250])
        if stop.is_set():
            journal.with_suffix(".part").unlink(missing_ok=True)
            if state["direction"] == "to_android":
                try:
                    request(queue, "files_transfer_abort", dict(id=state["id"]))
                except Exception:
                    state["android_cleanup_pending"] = True
    finally:
        with _lock:
            try:
                save(journal, state)
            finally:
                _running.pop(str(journal), None)
