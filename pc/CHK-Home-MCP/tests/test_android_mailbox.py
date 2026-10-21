"""Standalone safety tests for the Android Local 2 mailbox."""
import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_mailbox import AndroidMailbox

class AndroidMailboxTests(unittest.TestCase):
    def test_roundtrip(self):
        queue = AndroidMailbox()
        ident = queue.enqueue("browser_tabs", {})
        self.assertEqual(queue.poll()["id"], ident)
        self.assertTrue(queue.resolve(ident, True, "ok"))
        self.assertEqual(queue.await_result(ident)["state"], "done")
        self.assertFalse(queue.resolve(ident, True, "duplicate"))
    def test_rejects_unapproved_actions(self):
        queue = AndroidMailbox()
        with self.assertRaises(ValueError):
            queue.enqueue("exec_shell", {"command": "whoami"})
    def test_poll_does_not_duplicate(self):
        queue = AndroidMailbox()
        queue.enqueue("browser_status", {})
        self.assertIsNotNone(queue.poll())
        self.assertIsNone(queue.poll())
    def test_browser_batch(self):
        queue = AndroidMailbox()
        ident = queue.enqueue("browser_batch", {"steps": [
            {"action": "browser_open_url", "args": {"url": "https://example.com"}},
            {"action": "browser_read_page", "args": {}},
        ]})
        task = queue.poll()
        self.assertEqual(task["id"], ident)
        self.assertEqual(len(task["args"]["steps"]), 2)
        self.assertTrue(queue.resolve(ident, True, "completed"))
        self.assertEqual(queue.await_result(ident)["state"], "done")
        for steps in ([], [{"action": "exec_shell", "args": {}}],
                      [{"action": "browser_batch", "args": {}}]):
            with self.assertRaises(ValueError):
                queue.enqueue("browser_batch", {"steps": steps})

    def test_capacity(self):
        queue = AndroidMailbox(limit=1)
        queue.enqueue("browser_tabs", {})
        with self.assertRaises(ValueError):
            queue.enqueue("browser_tabs", {})
    def test_timeout_keeps_unknown_result_explicit(self):
        queue = AndroidMailbox()
        ident = queue.enqueue("browser_status", {})
        self.assertEqual(queue.await_result(ident, timeout=0)["state"], "pending")

if __name__ == "__main__":
    unittest.main()
