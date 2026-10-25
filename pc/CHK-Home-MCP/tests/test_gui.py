"""No-network regressions for the Windows GUI's isolated optional 443 test."""
import hashlib
import queue
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import chk_home_installer_gui as gui


class CloudflareGuiSmoke(unittest.TestCase):
    def test_existing_relay_update_keeps_config_and_does_not_reconfigure_funnel(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "config.json").write_text("{}")
            cfg = {"secret": "e"*64, "workspace": "unchanged"}
            instance = object.__new__(gui.Gui)
            instance.messages = queue.Queue()
            instance.msg = lambda text: None
            with patch.object(gui, "APP_DIR", folder), patch.object(gui, "private_config", return_value=cfg), \
                 patch.object(gui, "ensure_local_mcp", return_value=cfg["secret"]) as update, \
                 patch.object(gui, "ensure_funnel") as funnel, patch.object(gui, "register_autostart") as autostart:
                instance.update_existing()
                update.assert_called_once()
                funnel.assert_not_called()
                autostart.assert_not_called()
            self.assertEqual(instance.messages.get_nowait(), ("updated", None))
    def test_temporary_hostname_capture(self):
        typical_log = "INF +-----------------------------+\nINF | https://quiet-marble-otter.trycloudflare.com |\n"
        match = gui.CF_HOST_PATTERN.search(typical_log)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(0), "https://quiet-marble-otter.trycloudflare.com")
        self.assertIsNone(gui.CF_HOST_PATTERN.search("https://cloudflare.com/"))
        self.assertIsNone(gui.CF_HOST_PATTERN.search("https://example.com/"))

    def test_file_sha256(self):
        with tempfile.TemporaryDirectory() as directory:
            location = Path(directory) / "payload.bin"
            location.write_bytes(b"sample")
            self.assertEqual(gui.checksum_file(location), hashlib.sha256(b"sample").hexdigest())

    def test_dns_propagation_retries_then_validates_real_post(self):
        outcomes = [RuntimeError("DNS temporary"), RuntimeError("DNS temporary"), None]
        attempts = []
        def simulate_post(_url):
            attempts.append("attempt")
            outcome = outcomes.pop(0)
            if outcome is not None:
                raise outcome
        logs = []
        with patch.object(gui, "verify_public_mcp", side_effect=simulate_post), \
             patch.object(gui.time, "sleep", return_value=None):
            gui.wait_public_mcp("https://example.test/mcp/secret", logs.append,
                                duration_seconds=5)
        self.assertEqual(len(attempts), 3)
        self.assertTrue(any("validée" in line for line in logs))

    def test_install_button_launches_worker_without_closing_window(self):
        instance = object.__new__(gui.Gui)
        instance.messages = queue.Queue()
        instance.is_busy = False
        events = []
        class FakeButton:
            def configure(self, **kwargs):
                events.append(("button", kwargs))
        class FakeStatus:
            def set(self, value):
                events.append(("status", value))
        class FakeRoot:
            def destroy(self):
                events.append(("destroy", None))
        instance.root = FakeRoot()
        instance.status_var = FakeStatus()
        instance.log_line = lambda message: events.append(("log", message))
        instance.install_button = FakeButton()
        instance.update_button = FakeButton()
        instance.diagnostic_button = FakeButton()
        instance.cloudflare_button = FakeButton()
        instance.copy_button = FakeButton()
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(gui, "APP_DIR", Path(directory)), \
             patch.object(gui, "INSTALL_LOG", Path(directory) / "install.log"), \
             patch.object(gui.threading, "Thread") as worker, \
             patch.object(gui.messagebox, "askyesno", return_value=True):
            instance.confirm_install()
            self.assertTrue(instance.is_busy)
            worker.return_value.start.assert_called_once()
            instance.on_close()
            self.assertFalse(any(event[0] == "destroy" for event in events))
            trace = (Path(directory) / "install.log").read_text("utf-8")
            self.assertIn("INSTALL_CLICKED", trace)
            self.assertIn("ACTION_STARTED", trace)

    def test_worker_failure_is_reported_and_does_not_exit(self):
        instance = object.__new__(gui.Gui)
        instance.messages = queue.Queue()
        def problem():
            raise RuntimeError("Erreur de démarrage du relais")
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(gui, "APP_DIR", Path(directory)), \
             patch.object(gui, "INSTALL_LOG", Path(directory) / "install.log"):
            instance.worker(problem)
            self.assertEqual(instance.messages.get_nowait(), ("failed", "Erreur de démarrage du relais"))
            self.assertEqual(instance.messages.get_nowait(), ("done", None))
            self.assertIn("ACTION_FAILED | RuntimeError", (Path(directory) / "install.log").read_text("utf-8"))

    def test_diagnostic_never_logs_private_tokens(self):
        secret = "c" * 64
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(gui, "APP_DIR", Path(directory)), \
             patch.object(gui, "INSTALL_LOG", Path(directory) / "install.log"):
            gui.record_install_step("INSTALL_CLICKED", RuntimeError(secret))
            trace = (Path(directory) / "install.log").read_text("utf-8")
            self.assertIn("INSTALL_CLICKED | RuntimeError", trace)
            self.assertNotIn(secret, trace)

    def test_official_binary_pin(self):
        self.assertEqual(len(gui.CLOUDFLARED_SHA256), 64)
        self.assertIn("github.com/cloudflare/cloudflared/releases/download/", gui.CLOUDFLARED_URL)
        self.assertEqual(gui.WINDOWS_PORT, 8766)
        self.assertEqual(gui.PUBLIC_PORT, 8443)


if __name__ == "__main__":
    unittest.main()
