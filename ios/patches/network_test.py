import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("family_network_patch", HERE / "network.py")
patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch)


class NetworkPatchTests(unittest.TestCase):
    def test_exact_upstream_inputs_and_no_direct_fallback(self):
        original, output = patch.prepare(HERE / "network-fixtures")
        self.assertEqual(len(original), 6)
        self.assertEqual(len(output), 5)
        network, tcp, http, fetch, build = [raw.decode() for raw in output.values()]
        self.assertEqual(network.count("return FamilyTcpConnectionInterface("), 2)
        self.assertNotIn("return NetworkFrameworkTcpConnectionInterface(", network)
        self.assertNotIn("return WebProxyTransport.shared.makeConnectionInterface(", network)
        self.assertNotIn("factory.makeEngine(", network)
        self.assertNotIn("engineFactory?.makeEngine(", network)
        self.assertIn("return MtProtoKitEngine(context: context)", network)
        self.assertNotIn("[[MTGcdAsyncSocketTcpConnectionInterface alloc]", tcp)
        self.assertNotIn("[MTDNS resolveHostnameUniversal:", tcp)
        self.assertIn("_familyProxyRejected = context.apiEnvironment.socksProxySettings != nil;", tcp)
        self.assertIn("if (_socket == nil ||", tcp)
        self.assertIn("initWithIp:addressIp port:_scheme.address.port isSocks:false", tcp)
        self.assertIn("if (_scheme.address.secret != nil)", tcp)
        self.assertNotIn("NSURLSession", http)
        self.assertNotIn("data(forHttpUrl:", fetch)
        self.assertIn("subscriber.putError(.generic)", fetch)
        self.assertEqual(build.count('"//transport:FamilyTransport"'), 1)

    def test_every_pin_failure_prevents_any_write(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            shutil.copytree(HERE / "network-fixtures", root, dirs_exist_ok=True)
            for path in patch.PINS:
                before = (root / path).read_bytes()
                (root / path).write_bytes(before + b" ")
                with self.assertRaisesRegex(ValueError, "hash mismatch"):
                    patch.prepare(root)
                (root / path).write_bytes(before)
            for path in patch.PINS:
                self.assertEqual((root / path).read_bytes(), (HERE / "network-fixtures" / path).read_bytes())

    def test_apply_only_selected_files_and_reapply_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            shutil.copytree(HERE / "network-fixtures", root, dirs_exist_ok=True)
            sentinel = root / "untouched.txt"
            sentinel.write_text("preserve")
            original, output = patch.prepare(root)
            patch.apply(root, output)
            self.assertEqual(sentinel.read_text(), "preserve")
            for path in patch.PINS:
                self.assertEqual((root / path).read_bytes(), output.get(path, original[path]))
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                patch.prepare(root)

    def test_region_and_method_guards(self):
        with self.assertRaises(ValueError):
            patch.between("start start end", "start", "end", "x")
        with self.assertRaises(ValueError):
            patch.function("unrelated", "func absent {", "", "x")


if __name__ == "__main__":
    unittest.main()
