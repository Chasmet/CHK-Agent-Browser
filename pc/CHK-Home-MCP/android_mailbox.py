"""Isolated, bounded command mailbox for CHK Android via Windows Local 2.

The mailbox never calls Render and does not execute Android commands itself.
Authentication and HTTP exposure belong to the calling Windows MCP handler.
Only one active lease exists per command; repeated polls return the same lease.
"""
from __future__ import annotations
import threading
import time
import uuid

ALLOWED_ACTIONS = frozenset({
    "browser_status", "browser_tabs", "browser_read_page",
    "browser_open_url", "browser_click", "browser_type",
    "browser_scroll", "browser_get_form", "browser_batch",
    "android_action",
})

class AndroidMailbox:
    def __init__(self, *, ttl: float = 180.0, limit: int = 32):
        self.ttl, self.limit = ttl, limit
        self.lock = threading.Condition()
        self.items = {}
        self.last_seen = 0.0

    def enqueue(self, action: str, args: dict) -> str:
        if action not in ALLOWED_ACTIONS or not isinstance(args, dict):
            raise ValueError("Commande Android non autorisée.")
        if action == "android_action":
            name = args.get("action", "")
            payload = args.get("args", {})
            if not isinstance(name, str) or not name or len(name) > 100 or not isinstance(payload, dict):
                raise ValueError("Action Android invalide.")
            if not (name.startswith(("files_", "notes_", "video_editor_", "media_", "document_", "publication_"))
                    or name in ("workspace_status", "desktop", "keyboard", "pointer", "vision", "type", "click", "scroll",
                                "open_url", "read_page", "tabs", "get_form", "browser_task_submit", "browser_task_status")):
                raise ValueError("Action Android non autorisée.")
        if action == "browser_batch":
            steps = args.get("steps")
            if not isinstance(steps, list) or not 1 <= len(steps) <= 12:
                raise ValueError("Lot Android : 1 à 12 étapes requises.")
            if any(not isinstance(s, dict) or s.get("action") not in
                   ALLOWED_ACTIONS - {"browser_batch"} or
                   not isinstance(s.get("args", {}), dict) for s in steps):
                raise ValueError("Étape Android non autorisée.")
        with self.lock:
            self._expire()
            if sum(item["state"] in ("queued", "leased")
                   for item in self.items.values()) >= self.limit:
                raise ValueError("File Android saturée.")
            ident = uuid.uuid4().hex
            self.items[ident] = {
                "id": ident, "action": action, "args": dict(args),
                "state": "queued", "created": time.monotonic(),
                "result": None,
            }
            self.lock.notify_all()
            return ident

    def _expire(self) -> None:
        now = time.monotonic()
        for ident, item in list(self.items.items()):
            if item["state"] in ("done", "expired") and now - item["created"] > self.ttl * 2:
                del self.items[ident]
        for item in self.items.values():
            if item["state"] in ("queued", "leased") and now - item["created"] > self.ttl:
                item["state"], item["result"] = "expired", {
                    "success": False, "message": "Délai Android dépassé ; vérifier avant de relancer."
                }

    def poll(self, wait_seconds: float = 0) -> dict | None:
        """Wait for a new Android command without invoking Supabase per check."""
        if not isinstance(wait_seconds, (int, float)) or not 0 <= wait_seconds <= 18:
            raise ValueError("Durée d'attente invalide.")
        deadline = time.monotonic() + wait_seconds
        with self.lock:
            while True:
                self._expire()
                self.last_seen = time.monotonic()
                for item in self.items.values():
                    if item["state"] == "queued":
                        item["state"] = "leased"
                        return {k: item[k] for k in ("id", "action", "args")}
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.lock.wait(min(remaining, 1.0))

    def resolve(self, ident: str, success: bool, message: str) -> bool:
        with self.lock:
            item = self.items.get(ident)
            if item is None or item["state"] != "leased":
                return False
            if not isinstance(success, bool) or not isinstance(message, str):
                return False
            item["result"] = {"success": success, "message": message[:24000]}
            item["state"] = "done"
            self.lock.notify_all()
            return True

    def await_result(self, ident: str, timeout: float = 30.0) -> dict:
        deadline = time.monotonic() + min(max(timeout, 0), self.ttl)
        with self.lock:
            while True:
                self._expire()
                item = self.items.get(ident)
                if item is None:
                    raise ValueError("Commande inconnue.")
                if item["state"] in ("done", "expired"):
                    return {"id": ident, "state": item["state"], **item["result"]}
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return {"id": ident, "state": "pending",
                            "success": False, "message": "Android n'a pas encore répondu."}
                self.lock.wait(min(remaining, 1.0))

    def status(self) -> dict:
        with self.lock:
            self._expire()
            return {"android_recent": self.last_seen > 0 and
                    time.monotonic() - self.last_seen < 45,
                    "queued": sum(x["state"] == "queued" for x in self.items.values()),
                    "leased": sum(x["state"] == "leased" for x in self.items.values())}
