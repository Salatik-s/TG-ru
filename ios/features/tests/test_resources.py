import importlib.util
import json
from pathlib import Path, PurePosixPath
import plistlib
import tempfile
import unittest
import zipfile

F = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('feature_resources', F / 'resourcesintegration.py')
r = importlib.util.module_from_spec(spec); spec.loader.exec_module(r)

SMALL_BUILD = '''load("x", "swift_library")
swift_library(
    name = "Lib",
    srcs = [],
    data = [
        ":AppResources",
    ],
)
plist_fragment(
    name = "TelegramInfoPlist",
    extension = "plist",
    template = """
    <key>NSSpeechRecognitionUsageDescription</key>
    <string>We need this to transcribe audio messages on your request.</string>
    """,
)
'''

def imported_path(source):
    # Pinned resources.bundle_relative_parent_dir roots at the outermost *.bundle.
    parts = PurePosixPath(source).parts
    first = next(i for i, part in enumerate(parts) if part.endswith('.bundle'))
    return PurePosixPath(*parts[first:]).as_posix()

class ResourceIntegration(unittest.TestCase):
    def test_rule_uses_data_not_flat_resource_glob(self):
        result = r.transform_build(SMALL_BUILD)
        self.assertIn('"apple_bundle_import"', result)
        self.assertIn('bundle_imports = glob(["FamilyPython.bundle/**"])', result)
        self.assertIn('    data = [\n        ":FamilyPythonResources",', result)
        self.assertEqual(r.info_fragment(result)['NSSpeechRecognitionUsageDescription'], r.DESCRIPTION)
        with self.assertRaises(ValueError): r.transform_build(result)
        with self.assertRaises(ValueError): r.transform_build(SMALL_BUILD.replace('NSSpeechRecognitionUsageDescription', 'WrongKey'))

    def make_archive(self, target, *, flatten=False, missing=None, corrupt=None, speech=True):
        with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_STORED) as archive:
            info = {'CFBundleIdentifier': 'example.resource.layout.fixture'}
            if speech: info['NSSpeechRecognitionUsageDescription'] = r.DESCRIPTION
            archive.writestr('Payload/Fixture.app/Info.plist', plistlib.dumps(info))
            for relative, content in r.bundle_files().items():
                if relative == missing: continue
                bundled = imported_path(r.BUNDLE_PATH + '/' + relative)
                if flatten: bundled = r.APP_BUNDLE_PATH + '/' + PurePosixPath(relative).name
                if relative == corrupt: content += b'corrupt'
                if relative == 'Info.plist': content = plistlib.dumps(plistlib.loads(content), fmt=plistlib.FMT_BINARY)
                archive.writestr('Payload/Fixture.app/' + bundled, content)

    def test_archive_hierarchy_matches_swift_bundle_lookup(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'resource-layout-fixture.ipa'
            self.make_archive(archive)
            result = r.verify_ipa(archive)
            self.assertEqual(result['bundle'], 'Payload/Fixture.app/FamilyPython.bundle')
            self.assertEqual(result['runtime_files'], 11)
            with zipfile.ZipFile(archive) as file:
                self.assertIn('Payload/Fixture.app/FamilyPython.bundle/family-python/runner/index.html', file.namelist())
                self.assertIn('Payload/Fixture.app/FamilyPython.bundle/family-python/runner/runtime/pyodide.asm.wasm', file.namelist())
            swift = (F / 'python/FamilyPythonInlineView.swift').read_text('utf-8')
            self.assertIn('Bundle.main.url(forResource: "FamilyPython", withExtension: "bundle")', swift)
            self.assertIn('subdirectory: "family-python/runner"', swift)

    def test_archive_rejects_flattening_missing_tampered_runtime_and_permission(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'invalid-resource-layout-fixture.ipa'
            for options in ({'flatten': True}, {'missing': 'family-python/runner/index.html'},
                            {'corrupt': 'family-python/runner/runtime/pyodide.asm.wasm'}, {'speech': False}):
                with self.subTest(options=options):
                    self.make_archive(archive, **options)
                    with self.assertRaises(ValueError): r.verify_ipa(archive)

    @unittest.skipUnless((F / 'upstream/Telegram/BUILD').exists(), 'Local official snapshot; production checks actual checkout')
    def test_actual_pinned_build_packaging_is_repeatable_and_rejects_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'Telegram').mkdir()
            target = root / 'Telegram/BUILD'
            target.write_bytes((F / 'upstream/Telegram/BUILD').read_bytes())
            plan = r.prepare(root); r.write_plan(plan)
            expected = target.read_bytes()
            r.write_plan(r.prepare(root)); self.assertEqual(target.read_bytes(), expected)
            self.assertTrue((root / 'Telegram/FamilyPython.bundle/family-python/runner/worker.mjs').is_file())
            target.write_bytes(expected + b'\n# unapproved change\n')
            with self.assertRaises(ValueError): r.prepare(root)

    def test_build_lock_covers_both_personal_team_modes(self):
        lock = json.loads(r.BUILD_LOCK.read_text('utf-8'))
        self.assertEqual({entry['variant'] for entry in lock['builds'].values()},
                         {'official', 'personal_team', 'personal_team_compile_only'})

if __name__ == '__main__': unittest.main()
