import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
privacy = module("privacy_patch", "patches/privacy.py")
network = module("network_patch", "patches/network.py")

class PrivacyPatchTests(unittest.TestCase):
    def checkout(self, path):
        shutil.copytree(ROOT / "privacy/fixtures", path, dirs_exist_ok=True)
        target = path / privacy.NETWORK
        target.write_bytes(network.network(target.read_text("utf-8")).encode())
        target = path / privacy.BUILD
        target.write_bytes(network.build((ROOT / "patches/network-fixtures" / privacy.BUILD).read_text("utf-8")).encode())

    def test_all_fixture_hashes_and_transport_dependency(self):
        lock = json.loads((ROOT / "privacy/upstream-lock.json").read_text())
        for name, digest in lock["original_sources"].items():
            self.assertEqual(hashlib.sha256((ROOT / "privacy/fixtures" / name).read_bytes()).hexdigest(), digest)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.checkout(root)
            patched = privacy.prepare(root)
            self.assertIn('"//privacy:FamilyPrivacy"', patched[privacy.BUILD])
            self.assertIn('"//transport:FamilyTransport"', patched[privacy.BUILD])
            self.assertEqual(patched[privacy.NETWORK].count('errorDescription: "FAMILY_PRIVACY_SUPPRESSED"'), 2)
            self.assertNotIn('subscriber.putNext', patched[privacy.NETWORK].split('FAMILY_PRIVACY_SUPPRESSED')[0].split('func requestWithAdditionalInfo')[1])

    def test_deleted_messages_and_pts_processing_are_not_replaced(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.checkout(root); patched = privacy.prepare(root)
            before = (root / privacy.REPLAY).read_text("utf-8")
            after = '\n'.join(line for line in patched[privacy.REPLAY].split('\n') if 'familyArchiveReceivedDeletion(' not in line)
            self.assertEqual(after, before)
            bridge = patched[privacy.BRIDGE]
            self.assertIn('transaction.afterCommit { [copies]', bridge)
            self.assertNotIn('.affectedMessages(', bridge)
            self.assertNotIn('updatePts(', bridge)
            for required in ['AutoremoveTimeoutMessageAttribute', 'AutoclearTimeoutMessageAttribute', '.IsIncomingMask', 'isCopyProtected()', 'case .unknown: return false']:
                self.assertIn(required, bridge)

    def test_no_encrypted_or_uncertain_content_call_suppression(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.checkout(root); patched = privacy.prepare(root)
            history = patched[privacy.CORE + 'Sources/State/SynchronizePeerReadState.swift']
            self.assertIn('network.request(Api.functions.messages.readEncryptedHistory(peer: inputPeer, maxDate: maxIncomingReadIndex.timestamp))', history)
            contents = patched[privacy.CORE + 'Sources/State/ManagedConsumePersonalMessagesActions.swift']
            self.assertEqual(contents.count('familyPrivacyCanHideContents(transaction: transaction, ids: [id])'), 4)
            self.assertIn('transaction.setPendingMessageAction(type: .consumeUnseenPersonalMessage, id: id, action: nil)', contents)

    def test_all_hashes_validated_before_any_write(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.checkout(root)
            target = root / privacy.REPLAY; target.write_bytes(target.read_bytes() + b'\n// drift\n')
            before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
            with self.assertRaisesRegex(ValueError, 'input hash mismatch'): privacy.prepare(root)
            self.assertEqual(before, {p: p.read_bytes() for p in root.rglob('*') if p.is_file()})

    def test_second_application_rejected_and_call_counts_guarded(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.checkout(root); patched = privacy.prepare(root)
            for name, data in patched.items(): (root / name).write_bytes(data.encode())
            with self.assertRaises(ValueError): privacy.prepare(root)
        with self.assertRaisesRegex(ValueError, 'call count'): privacy.requests('network.request(Api.functions.messages.readHistory(peer: p, maxId: 1))', ['messages.readHistory'], 2, eligible='true')

if __name__ == '__main__': unittest.main()
