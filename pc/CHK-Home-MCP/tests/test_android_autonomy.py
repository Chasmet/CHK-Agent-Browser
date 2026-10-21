import base64
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_tools
import android_transfers
import chk_home_mcp as mcp


class AndroidPhone:
    """Independent mailbox peer: records bytes and validates the relay's block boundaries."""
    def __init__(self):
        self.files = {}
        self.transfers = {}
        self.answers = {}
        self.offsets = []
        self.lose_ack = False
        self.corrupt = False

    def enqueue(self, wrapper, payload):
        assert wrapper == "android_action"
        action, args = payload["action"], payload["args"]
        result = {}
        if action == "files_transfer_begin":
            state = self.transfers.setdefault(args["id"], {**args, "data": bytearray(), "state": "receiving"})
            result = dict(received=len(state["data"]), state=state["state"])
        elif action == "files_transfer_write":
            state = self.transfers[args["id"]]
            data = base64.b64decode(args["base64"], validate=True)
            assert len(state["data"]) == args["offset"]
            assert hashlib.sha256(data).hexdigest() == args["chunk_sha256"]
            assert len(data) <= 49152
            self.offsets.append(args["offset"])
            state["data"].extend(data)
            result = dict(received=len(state["data"]))
            if self.lose_ack:
                self.lose_ack = False
                ident = str(len(self.answers))
                self.answers[ident] = dict(state="expired", success=False, message="Lost ack")
                return ident
        elif action == "files_transfer_commit":
            state = self.transfers[args["id"]]
            assert len(state["data"]) == state["size"]
            assert hashlib.sha256(state["data"]).hexdigest() == state["sha256"]
            self.files[state["path"]] = bytes(state["data"])
            state["state"] = "committed"
            result = dict(verified=True, sha256=state["sha256"])
        elif action == "files_hash":
            data = self.files[args["path"]]
            result = dict(size=len(data), updated=1234, sha256=hashlib.sha256(data).hexdigest())
        elif action == "files_transfer_read":
            data = self.files[args["path"]]
            assert args["size"] == len(data) and args["updated"] == 1234
            block = data[args["offset"]:args["offset"]+8192]
            result = dict(offset=args["offset"], base64=base64.b64encode(block).decode(), chunk_sha256="bad" if self.corrupt else hashlib.sha256(block).hexdigest())
        elif action == "files_transfer_abort":
            self.transfers.pop(args["id"], None)
        else:
            raise AssertionError(action)
        ident = str(len(self.answers))
        self.answers[ident] = dict(state="done", success=True, message=json.dumps(result))
        return ident

    def await_result(self, ident, timeout=0):
        return self.answers[ident]


class AndroidAutonomyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.cfg = dict(root=self.root, secret="e"*64)
        self.phone = AndroidPhone()

    def tearDown(self):
        self.temp.cleanup()

    def command(self, operation, **args):
        return android_transfers.command(dict(operation=operation, id="test", **args), self.cfg, self.phone, mcp.workspace_path)

    def wait(self):
        deadline = time.monotonic()+5
        while time.monotonic() < deadline:
            state = self.command("status")
            if state["state"] != "running":
                return state
            time.sleep(.01)
        self.fail("Transfer did not settle")

    def test_large_file_moves_both_directions_with_verified_hash(self):
        data = bytes(range(251))*1500
        (self.root/"source.bin").write_bytes(data)
        self.command("start", direction="to_android", source="source.bin", destination="media.bin")
        state = self.wait()
        self.assertEqual(state["state"], "completed", state)
        self.assertTrue(state["verified"])
        self.assertEqual(self.phone.files["media.bin"], data)
        android_transfers.command(dict(operation="start", id="return", direction="to_pc", source="media.bin", destination="returned.bin"), self.cfg, self.phone, mcp.workspace_path)
        for _ in range(500):
            state = android_transfers.command(dict(operation="status", id="return"), self.cfg, self.phone, mcp.workspace_path)
            if state["state"] != "running":
                break
            time.sleep(.01)
        self.assertEqual(state["state"], "completed", state)
        self.assertEqual((self.root/"returned.bin").read_bytes(), data)

    def test_resume_reconciles_phone_after_lost_ack_without_duplicate_write(self):
        data = b"resume"*20000
        (self.root/"source.bin").write_bytes(data)
        self.phone.lose_ack = True
        self.command("start", direction="to_android", source="source.bin", destination="media.bin")
        self.assertEqual(self.wait()["state"], "paused")
        self.assertNotIn("media.bin", self.phone.files)
        self.command("resume")
        self.assertEqual(self.wait()["state"], "completed")
        self.assertEqual(self.phone.offsets, [0, 49152, 98304])
        self.assertEqual(self.phone.files["media.bin"], data)

    def test_changed_source_is_rejected_on_resume(self):
        (self.root/"source.bin").write_bytes(b"old"*20000)
        self.phone.lose_ack = True
        self.command("start", direction="to_android", source="source.bin", destination="media.bin")
        self.assertEqual(self.wait()["state"], "paused")
        (self.root/"source.bin").write_bytes(b"new"*20000)
        self.command("resume")
        state = self.wait()
        self.assertEqual(state["state"], "paused")
        self.assertIn("modifiée", state["error"])
        self.assertNotIn("media.bin", self.phone.files)

    def test_corrupt_download_never_publishes_and_cancel_cleans_staging(self):
        self.phone.files["video.mp4"] = b"data"*10000
        self.phone.corrupt = True
        self.command("start", direction="to_pc", source="video.mp4", destination="video.mp4")
        self.assertEqual(self.wait()["state"], "paused")
        self.assertFalse((self.root/"video.mp4").exists())
        self.command("cancel")
        self.assertFalse((self.root/".transfers/test.part").exists())

    def test_no_existing_destination_overwrite_or_journal_path_access(self):
        self.phone.files["file"] = b"new"
        (self.root/"old.txt").write_bytes(b"old")
        self.command("start", direction="to_pc", source="file", destination="old.txt")
        self.assertEqual(self.wait()["state"], "paused")
        self.assertEqual((self.root/"old.txt").read_bytes(), b"old")
        for path in (".transfers/test.json", "../escape"):
            with self.assertRaises(ValueError):
                mcp.workspace_path(self.root, path)

    def test_specialized_tools_map_to_existing_legacy_transport(self):
        self.assertEqual(len(android_tools.catalog()), 7)
        self.assertEqual(android_tools.route("android_transfer", dict(operation="commit", id="x")), ("files_transfer_commit", dict(id="x")))
        self.assertEqual(android_tools.route("android_files", dict(operation="external", external_operation="external_mkdir", name="Application")), ("files_external", dict(operation="external_mkdir", name="Application")))
        self.assertEqual(android_tools.route("android_document_create", dict(path="a.docx", format="docx"))[0], "document_create")
        with self.assertRaises(ValueError):
            android_tools.route("android_media", dict(operation="run_shell"))

    def test_preview_is_returned_as_mcp_image_instead_of_base64_text(self):
        image = base64.b64encode(b"\xff\xd8\xfftest").decode()
        content = mcp.tool_content(dict(state="done", success=True, message=json.dumps(dict(jpeg_base64=image, path="image.jpg"))))
        self.assertEqual(content[1], dict(type="image", mimeType="image/jpeg", data=image))
        self.assertNotIn("jpeg_base64", content[0]["text"])
        self.assertEqual(len(mcp.tool_content(dict(success=False, message=json.dumps(dict(jpeg_base64=image))))), 1)

    def test_oversized_legacy_response_cannot_be_reported_as_success(self):
        queue = mcp.AndroidMailbox()
        ident = queue.enqueue("browser_status", {})
        queue.poll()
        self.assertTrue(queue.resolve(ident, True, "x"*24001))
        self.assertFalse(queue.await_result(ident, 0)["success"])

    def test_android_failures_are_mcp_errors_but_pending_is_not(self):
        for state in ("done", "expired", "pending"):
            with patch.object(mcp.ANDROID_QUEUE, "await_result", return_value=dict(state=state, success=False, message="unconfirmed")):
                result = mcp.dispatch(dict(jsonrpc="2.0", id=1, method="tools/call", params=dict(name="android_result", arguments=dict(id="x"))), self.cfg)
                self.assertEqual(result["result"]["isError"], state != "pending")

    def test_maximum_upload_block_fits_the_actual_http_envelopes(self):
        payload = dict(action="files_transfer_write", args=dict(id="test", offset=0,
            base64=base64.b64encode(b"x"*49152).decode(), chunk_sha256="e"*64))
        request = dict(jsonrpc="2.0", id=1, method="tools/call", params=dict(name="android_command", arguments=dict(action="android_action", args=payload)))
        self.assertLess(len(json.dumps(request).encode()), mcp.MAX_POST)
        response = dict(command=dict(id="f"*32, action="android_action", args=payload), server_id="e"*16)
        self.assertGreater(len(json.dumps(response)), 65536)
        self.assertLess(len(json.dumps(response)), 96*1024)


if __name__ == "__main__":
    unittest.main()
