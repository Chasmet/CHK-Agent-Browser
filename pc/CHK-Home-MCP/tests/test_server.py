"""Functional regression tests: protocol, capability auth and workspace isolation."""
import http.client
import json
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import chk_home_mcp as mcp


class WindowsMcpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.cfg = {"secret": "e" * 64, "root": self.root}

    def tearDown(self):
        self.temp.cleanup()

    def test_protocol(self):
        for method in ("initialize", "tools/list", "ping"):
            reply = mcp.dispatch({"jsonrpc": "2.0", "id": 9,
                                  "method": method, "params": {"protocolVersion": "2025-06-18"}}, self.cfg)
            self.assertEqual(reply["id"], 9)
            self.assertIn("result", reply)
        self.assertEqual(len(mcp.tool_catalog()), 20)
        self.assertEqual(mcp.safe_tool_action("server_info", {}, self.cfg)["render"], False)
        status = mcp.safe_tool_action("android_status", {}, self.cfg)
        self.assertEqual(status["workspace_protocol_required_for_new_tools"], 4)
        self.assertEqual(status["capabilities_probe"]["args"]["action"], "files_capabilities")

    def test_workspace_save_read_trash_restore(self):
        mcp.safe_tool_action("files_mkdir", {"path": "clips"}, self.cfg)
        mcp.safe_tool_action("files_write_text", {"path": "clips/note.txt", "content": "bonjour"}, self.cfg)
        self.assertEqual(mcp.safe_tool_action("files_read_text", {"path": "clips/note.txt"}, self.cfg)["content"],
                         "bonjour")
        with self.assertRaises(ValueError):
            mcp.safe_tool_action("files_write_text", {"path": "clips/note.txt", "content": "new"}, self.cfg)
        trash_id = mcp.safe_tool_action("files_trash", {"path": "clips/note.txt"}, self.cfg)["trash_id"]
        self.assertFalse((self.root / "clips" / "note.txt").exists())
        mcp.safe_tool_action("files_restore", {"trash_id": trash_id}, self.cfg)
        self.assertTrue((self.root / "clips" / "note.txt").exists())

    def test_no_absolute_or_outside_paths(self):
        for bad in ("../escape", "C:\\Windows\\system.ini", "/etc/passwd", ".trash/file"):
            with self.assertRaises(ValueError):
                mcp.safe_tool_action("files_read_text", {"path": bad}, self.cfg)
        with self.assertRaises(ValueError):
            mcp.safe_tool_action("run_shell", {"command": "whoami"}, self.cfg)

    def test_authenticated_missing_content_type_via_reverse_proxy(self):
        server = mcp.LocalMcpServer(("127.0.0.1", 0), mcp.Handler)
        server.cfg = self.cfg
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        payload = json.dumps({"jsonrpc": "2.0", "id": 3, "method": "initialize",
                              "params": {"protocolVersion": "2025-06-18"}}).encode()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=4)
            conn.request("POST", "/mcp/" + "e"*64, body=payload,
                         headers={"Content-Length": str(len(payload))})
            reply = conn.getresponse()
            self.assertEqual(reply.status, 200)
            self.assertEqual(json.loads(reply.read())["result"]["serverInfo"]["name"], "CHK Home MCP")
            conn.close()
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=4)
            conn.request("POST", "/mcp/" + "e"*64, body=payload,
                         headers={"Content-Type": "text/plain"})
            reply = conn.getresponse()
            self.assertEqual(reply.status, 415)
            reply.read()
            conn.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(3)

    def test_android_bridge_uses_all_authenticated_proxy_paths(self):
        server = mcp.LocalMcpServer(("127.0.0.1", 0), mcp.Handler)
        server.cfg = self.cfg
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for path, authorization in (("/mcp/" + self.cfg["secret"], ""),
                                        ("/" + self.cfg["secret"], ""),
                                        ("/mcp", "Bearer " + self.cfg["secret"])):
                with self.subTest(path_kind="bearer" if authorization else path[:5]):
                    queue = mcp.AndroidMailbox()
                    with patch.object(mcp, "ANDROID_QUEUE", queue):
                        ident = queue.enqueue("browser_status", {})
                        def post(method, params=None, target=path, token=authorization):
                            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
                            connection.request("POST", target,
                                body=json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}),
                                headers={"Content-Type": "application/json", "Authorization": token})
                            response = connection.getresponse()
                            body = json.loads(response.read())
                            status = response.status
                            connection.close()
                            return status, body
                        status, reply = post("android/next")
                        self.assertEqual(status, 200)
                        self.assertEqual(reply["command"]["id"], ident)
                        info = mcp.safe_tool_action("server_info", {}, self.cfg)
                        self.assertEqual(reply["server_id"], info["server_id"])
                        self.assertTrue(mcp.safe_tool_action("android_status", {}, self.cfg)["android_recent"])
                        status, reply = post("android/complete", {"id": ident, "success": True, "message": "pong"})
                        self.assertTrue(reply["ok"])
                        self.assertEqual(queue.await_result(ident, 0)["message"], "pong")
                        _, reply = post("android/complete", {"id": ident, "success": True, "message": "duplicate"})
                        self.assertFalse(reply["ok"])
                        status, _ = post("android/next", target="/mcp/bad", token="")
                        self.assertEqual(status, 401)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(3)

    def test_http_early_auth_rejection_closes_connection(self):
        # Regression: a reverse proxy can convert an unread unauthorized
        # HTTP/1.1 POST body into a Cloudflare 520/400 on connection reuse.
        server = mcp.LocalMcpServer(("127.0.0.1", 0), mcp.Handler)
        server.cfg = self.cfg
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        payload = json.dumps({"jsonrpc": "2.0", "id": 9,
                              "method": "initialize"}).encode()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
            conn.request("POST", "/mcp", body=payload,
                         headers={"Content-Type": "application/json",
                                  "Connection": "keep-alive"})
            result = conn.getresponse()
            self.assertEqual(result.status, 401)
            self.assertEqual(result.headers.get("Connection"), "close")
            self.assertEqual(json.loads(result.read())["error"], "MCP authentication required")
            # Client can transparently open a fresh socket for subsequent
            # requests; no trailing request-body bytes are reused.
            conn.request("GET", "/health")
            second = conn.getresponse()
            self.assertEqual(second.status, 200)
            self.assertEqual(json.loads(second.read())["server"], "CHK Home MCP")
            conn.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(3)

    def test_real_http_security_and_mcp(self):
        server = mcp.LocalMcpServer(("127.0.0.1", 0), mcp.Handler)
        server.cfg = self.cfg
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:" + str(server.server_port)
        payload = json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}).encode()
        try:
            with urllib.request.urlopen(base + "/health", timeout=3) as response:
                self.assertEqual(json.load(response)["render"], False)
            request = urllib.request.Request(base + "/mcp/bad", data=payload,
                                             headers={"Content-Type": "application/json"})
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request, timeout=3)
            self.assertEqual(error.exception.code, 401)
            request = urllib.request.Request(base + "/mcp/" + "e"*64, data=payload,
                                             headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=3) as response:
                self.assertEqual(len(json.load(response)["result"]["tools"]), 20)
            request = urllib.request.Request(base + "/mcp/" + "e"*64, data=payload,
                                             headers={"Content-Type": "application/json",
                                                      "Origin": "https://untrusted.example"})
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request, timeout=3)
            self.assertEqual(error.exception.code, 403)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(3)


if __name__ == "__main__":
    unittest.main()
