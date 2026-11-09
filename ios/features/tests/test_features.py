import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
F = ROOT / 'features'
spec = importlib.util.spec_from_file_location('feature_patch', ROOT / 'patches/features.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class Contracts(unittest.TestCase):
    def test_anchor_refuses_missing_and_duplicate(self):
        for value in ('none', 'twice twice'):
            with self.assertRaises(ValueError): p.replace_one(value, 'twice', 'one')

    def test_region_rejects_reverse(self):
        with self.assertRaises(ValueError): p.region('end start', 'start', 'end', '')

    def test_speech_no_network_fallback(self):
        source = (F / 'speech/FamilyLocalSpeech.swift').read_text('utf-8')
        self.assertIn('request.requiresOnDeviceRecognition = true', source)
        self.assertGreaterEqual(source.count('recognizer.supportsOnDeviceRecognition'), 2)
        for forbidden in ('URLSession', 'AVAudioRecorder', 'AVAudioEngine', 'print(', 'supportsOnDeviceRecognition ='):
            self.assertNotIn(forbidden, source)
        self.assertIn('defer { reader.cancelReading() }', source)
        self.assertIn('speech?.cancel()', source)
        self.assertIn('removeItem(at: temporary)', source)

    def test_opt_in_only(self):
        settings = (F / 'settings/FamilyFeatureSettingsController.swift').read_text('utf-8')
        self.assertNotIn('register(defaults:', settings)
        self.assertNotIn('UserDefaults.standard.set(true', settings)
        self.assertIn('FamilyFeaturesChanged', settings)

    def test_python_scheme_is_allowlisted(self):
        source = (F / 'python/FamilyPythonInlineView.swift').read_text('utf-8')
        for expected in ('allowed.contains(name)', 'url.query == nil', 'url.user == nil', 'message.frameInfo.isMainFrame',
                         'event["id"] as? String == runID', 'arm(seconds: 10', '.nonPersistent()', 'compileContentRuleList'):
            self.assertIn(expected, source)
        self.assertNotIn('URLSession', source)

    def test_runtime_integrity(self):
        lock = json.loads((F / 'python/Resources/runtime-lock.json').read_text('utf-8'))
        root = F / 'python/Resources/family-python/runner'
        self.assertEqual(lock['version'], '314.0.7')
        for path, expected in lock['files'].items():
            self.assertEqual(hashlib.sha256((root / path).read_bytes()).hexdigest(), expected, path)

    def test_worker_has_no_package_install(self):
        source = (F / 'python/Resources/family-python/runner/worker.mjs').read_text('utf-8')
        self.assertIn('jsglobals:Object.freeze({})', source)
        self.assertNotIn('loadPackagesFromImports', source)
        self.assertNotIn('micropip', source)

    def test_javascript_runtime_controller(self):
        subprocess.run(['node', str(F / 'tests/controller.test.cjs')], check=True)

@unittest.skipUnless((F / 'upstream' / p.FILE).is_file(), 'Pinned development snapshots are local; CI apply validates the real checkout instead')
class PinnedIntegration(unittest.TestCase):
    def test_pinned_transform_and_output_locks(self):
        changes = p.transformed()
        expected = json.loads((F / 'integration-lock.json').read_text('utf-8'))['files']
        self.assertEqual(len(changes), 8)
        for path, source in changes.items():
            self.assertEqual(hashlib.sha256(source.encode()).hexdigest(), expected[path])
        for path in (p.FILE, p.ROUND):
            self.assertNotIn('engine.messages.transcribeAudio(', changes[path])
            self.assertIn('familyTranscribeAudio(', changes[path])
            self.assertIn('storeLocallyTranscribedAudio(', changes[path])
        self.assertIn('let language', changes[p.TEXT])
        self.assertIn('defaultTitle = "S.A.A."', changes[p.CHAT_LIST])
        self.assertIn('parentController?.openFamilyFeatureSettings()', changes[p.CHAT_LIST])
        self.assertIn('self.present(navigation, animated: true)', changes[p.CHAT_LIST])
        self.assertIn('self?.parentController?.composePressed()', changes[p.CHAT_LIST])
        self.assertIn('"//privacy:FamilyPrivacy"', changes[p.CHAT_LIST_BUILD])

    def test_apply_without_snapshots_idempotent_and_mismatch_atomic(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)
            for path in p.transformed():
                dest = target / path; dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(F / 'upstream' / path, dest)
            (target / 'Telegram').mkdir(exist_ok=True)
            shutil.copyfile(F / 'upstream/Telegram/BUILD', target / 'Telegram/BUILD')
            with patch.object(p.subprocess, 'check_output', return_value=p.LOCK['commit']):
                p.apply(target, check_only=True)
                p.apply(target)
                first = {x: (target / x).read_bytes() for x in p.transformed()}
                p.apply(target)
                self.assertEqual(first, {x: (target / x).read_bytes() for x in first})
                (target / p.FILE).write_text('unexpected', 'utf-8')
                before = (target / p.ROUND).read_bytes()
                with self.assertRaises(ValueError): p.apply(target)
                self.assertEqual(before, (target / p.ROUND).read_bytes())

if __name__ == '__main__': unittest.main()
