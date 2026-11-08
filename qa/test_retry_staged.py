import base64
import copy
import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("retry_staged", Path(__file__).resolve().parent.parent / "deploy/retry-staged.py")
retry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retry)


@unittest.skipUnless(os.name == "nt" or os.getuid() == 0, "Ownership-bound fixture requires UID 0")
class RetryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="familygram-retry-test-")
        self.root = Path(self.temp.name)
        for name in ["deploy", "runtime"]:
            (self.root / name).mkdir()
        (self.root / "runtime/xray").write_bytes(b"unchanged-runtime-fixture")
        self.payload = {"updates": {}, "unchanged": {"runtime/xray": retry.digest(self.root / "runtime/xray")}}
        for name in ["deploy/install.sh", "deploy/compose.yaml", "deploy/Deploy.ps1"]:
            path = self.root / name
            path.write_bytes(b"old-file")
            data = ("new-" + name).encode()
            self.payload["updates"][name] = {"old_sha256": retry.digest(path), "new_sha256": hashlib.sha256(data).hexdigest(), "content": base64.b64encode(data).decode()}

    def tearDown(self):
        self.temp.cleanup()

    def assert_unchanged(self):
        for name in self.payload["updates"]:
            self.assertEqual((self.root / name).read_bytes(), b"old-file")

    def test_valid_and_idempotent(self):
        retry.apply_updates(self.root, self.payload)
        retry.apply_updates(self.root, self.payload)
        for name, update in self.payload["updates"].items():
            self.assertEqual(retry.digest(self.root / name), update["new_sha256"])

    def test_last_file_mismatch_writes_nothing(self):
        path = self.root / "deploy/Deploy.ps1"
        path.write_bytes(b"owner-modified-file")
        with self.assertRaises(ValueError):
            retry.apply_updates(self.root, self.payload)
        self.assertEqual((self.root / "deploy/install.sh").read_bytes(), b"old-file")
        self.assertEqual((self.root / "deploy/compose.yaml").read_bytes(), b"old-file")
        self.assertEqual(path.read_bytes(), b"owner-modified-file")

    def test_payload_corruption_writes_nothing(self):
        payload = copy.deepcopy(self.payload)
        payload["updates"]["deploy/Deploy.ps1"]["content"] = base64.b64encode(b"bad").decode()
        with self.assertRaises(ValueError):
            retry.apply_updates(self.root, payload)
        self.assert_unchanged()

    def test_runtime_corruption_writes_nothing(self):
        (self.root / "runtime/xray").write_bytes(b"bad-runtime")
        with self.assertRaises(ValueError):
            retry.apply_updates(self.root, self.payload)
        self.assert_unchanged()

    def test_path_escape_writes_nothing(self):
        payload = copy.deepcopy(self.payload)
        payload["unchanged"]["deploy/../../outside"] = "0" * 64
        with self.assertRaises(ValueError):
            retry.apply_updates(self.root, payload)
        self.assert_unchanged()


if __name__ == "__main__":
    unittest.main()
