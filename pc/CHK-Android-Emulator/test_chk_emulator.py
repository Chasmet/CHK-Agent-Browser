import hashlib
import http.client
from pathlib import Path
import shutil
import ssl
import struct
import tempfile
import threading
import unittest

from chk_emulator import DeviceError, Emulator, Handler, TlsServer, certificate, video_size


class TestAvd(Emulator):
    def __init__(self):
        super().__init__("adb", "emulator-5554")
        self.png = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" + struct.pack(">II", 480, 800)
        self.commands = []
        self.digest = b""

    def run(self, *args, **_kwargs):
        self.commands.append(args)
        if args == ("exec-out", "screencap", "-p"):
            return self.png
        if args == ("get-state",):
            return b"device\n"
        if args == ("shell", "getprop", "sys.boot_completed"):
            return b"1\n"
        if args[0] == "push":
            self.digest = hashlib.sha256(Path(args[1]).read_bytes()).hexdigest().encode()
        if args[:2] == ("shell", "sha256sum"):
            return self.digest + b"  media\n"
        return b""


class EmulatorTest(unittest.TestCase):
    def test_only_local_avd_is_accepted(self):
        for serial in ("USB123", "192.168.1.20:5555", "-s"):
            with self.assertRaises(ValueError):
                Emulator("adb", serial)
        self.assertEqual(TestAvd().adb[-2:], ["-s", "emulator-5554"])

    def test_gestures_are_bound_to_an_observation_and_checked(self):
        device = TestAvd()
        png, frame_id = device.frame()
        self.assertEqual(video_size(png), (480, 800))
        args = {"operation": "tap", "frame_id": frame_id, "x": 479, "y": 799}
        with self.assertRaises(ValueError):
            device.control(dict(args, x=True))
        with self.assertRaises(ValueError):
            device.control(dict(args, x=480))
        self.assertTrue(device.control(args)["input_sent"])
        self.assertEqual(device.commands[-1], ("shell", "input tap 479 799"))
        with self.assertRaises(ValueError):
            device.control(args)

    def test_text_metacharacters_stay_in_one_shell_argument(self):
        device = TestAvd()
        device.control({"operation": "text", "text": "'& x; $(a)"})
        self.assertEqual(device.commands[-1][0], "shell")
        import shlex
        self.assertEqual(shlex.split(device.commands[-1][1]), ["input", "text", "'&%sx;%s$(a)"])
        with self.assertRaises(ValueError):
            device.control({"operation": "text", "text": "é"})

    def test_invalid_images_are_rejected(self):
        with self.assertRaises(DeviceError):
            video_size(b"not an image")


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required for HTTPS integration")
class HttpIntegrationTest(unittest.TestCase):
    def setUp(self):
        states = tempfile.TemporaryDirectory()
        self.addCleanup(states.cleanup)
        cert, key = certificate(Path(states.name), shutil.which("openssl"))
        self.device = TestAvd()
        self.server = TlsServer(("127.0.0.1", 0), Handler)
        self.server.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.server.tls.load_cert_chain(cert, key)
        self.server.device = self.device
        self.server.authorization = b"Bearer integration-token"
        self.server.media_slot = threading.Lock()
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(self, method, path, body=None, *, headers=None, authenticated=True):
        all_headers = headers or {}
        if authenticated:
            all_headers = dict(all_headers, Authorization="Bearer integration-token")
        client = http.client.HTTPSConnection("127.0.0.1", self.server.server_port,
                                            context=ssl._create_unverified_context(), timeout=5)
        try:
            client.request(method, path, body, all_headers)
            result = client.getresponse()
            return result.status, result.headers, result.read()
        finally:
            client.close()

    def test_auth_frame_and_bounded_media_transfer(self):
        import json
        self.assertEqual(self.request("GET", "/v1/status", authenticated=False)[0], 401)
        self.assertTrue(json.loads(self.request("GET", "/v1/status")[2])["ready"])
        status, headers, png = self.request("GET", "/v1/frame")
        self.assertEqual((status, headers["Content-Type"], png), (200, "image/png", self.device.png))
        self.assertEqual(len(headers["X-CHK-Frame"]), 24)
        video = b"test MP4 transfer bytes"
        self.assertEqual(self.request("POST", "/v1/media", video, headers={"X-CHK-Filename": "../bad.mp4"})[0], 400)
        status, _, response = self.request("POST", "/v1/media", video, headers={"X-CHK-Filename": "clip.mp4"})
        result = json.loads(response)
        self.assertEqual(status, 200)
        self.assertTrue(result["verified"])
        self.assertEqual(result["sha256"], hashlib.sha256(video).hexdigest())
        self.assertRegex(result["path"], r"^/sdcard/Download/CHK/[a-f0-9]{12}-clip\.mp4$")


if __name__ == "__main__":
    unittest.main()
