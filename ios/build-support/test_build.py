import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import struct
import tempfile
import unittest
import zipfile
from common import ROOT, LOCK, contained, credentials, digest, verify_sources
from configuration import emit
from prepare import patch_build, patch_personal_delegate, patch_personal_passcode, prepare
from verify_ipa import verify, macho

FIXTURE = ROOT / 'build-support/upstream-fixtures'
SYNTHETIC_ENV = {'TG_API_ID': '123456789', 'TG_API_HASH': 'a' * 32}

def binary(platform=2, cpu=0x100000c):
    return struct.pack('<8I', 0xfeedfacf, cpu, 0, 2, 1, 24, 0, 0) + struct.pack('<6I', 0x32, 24, platform, 0, 0, 0)

def archive(path, extra=None, platform=2, bundle='com.salatik.tgru'):
    with zipfile.ZipFile(path, 'w') as out:
        out.writestr('Payload/Telegram.app/Info.plist', plistlib.dumps({'CFBundleIdentifier': bundle,
            'CFBundleExecutable': 'Telegram', 'CFBundleVersion': '1', 'CFBundleShortVersionString': '13.2'}))
        out.writestr('Payload/Telegram.app/Telegram', binary(platform))
        if extra: out.writestr(extra, b'x')

class BuildTests(unittest.TestCase):
    def test_exact_fixtures(self):
        verify_sources(FIXTURE)
        self.assertEqual(len(LOCK['files']), 8)

    def test_personal_team_storage_and_passcode(self):
        source = (FIXTURE / 'submodules/TelegramUI/Sources/AppDelegate.swift').read_text(encoding='utf-8')
        changed = patch_personal_delegate(source)
        self.assertIn('configuration.sharedContainerIdentifier = nil', changed)
        self.assertIn('FileProtectionType.completeUntilFirstUserAuthentication', changed)
        self.assertNotIn('containerURL(forSecurityApplicationGroupIdentifier:', changed)
        self.assertIn('BuildConfig.deviceSpecificEncryptionParameters(rootPath', changed)
        with self.assertRaises(ValueError): patch_personal_delegate(changed)
        passcode = (FIXTURE / 'submodules/PasscodeCore/Sources/Passcode.swift').read_text(encoding='utf-8')
        result = patch_personal_passcode(passcode)
        self.assertIn('try self.environment.privateAccessGroup() // Personal Team', result)
        self.assertIn('kSecAttrAccessibleWhenUnlockedThisDeviceOnly', result)
        self.assertIn('.biometryCurrentSet', result)
        with self.assertRaises(ValueError): patch_personal_passcode(result)

    def test_entitlements_and_name_patch(self):
        source = (FIXTURE / 'Telegram/BUILD').read_text(encoding='utf-8')
        changed = patch_build(source)
        self.assertIn('<string>TG-ru</string>', changed)
        start = changed.index('name = "TelegramEntitlements"')
        self.assertIn('template = ""', changed[start:changed.index('\n)', start)])
        self.assertIn('name = "Telegram"', changed)
        with self.assertRaises(ValueError): patch_build(changed)

    def test_all_preconditions_before_patch(self):
        with tempfile.TemporaryDirectory() as temp:
            dest = Path(temp) / 'source'; shutil.copytree(FIXTURE, dest)
            original = digest(dest / 'Telegram/BUILD')
            (dest / 'MODULE.bazel').write_text('wrong pin')
            with self.assertRaises(ValueError): prepare(dest, False)
            self.assertEqual(original, digest(dest / 'Telegram/BUILD'))

    def test_paths(self):
        for path in ('../escape', str(ROOT.resolve())):
            with self.assertRaises(ValueError): contained(ROOT, path)

    def test_credentials(self):
        self.assertEqual(credentials(SYNTHETIC_ENV)[0], '123456789')
        for environment in ({}, {'TG_API_ID': '-1', 'TG_API_HASH': 'a' * 32},
                            {'TG_API_ID': '1', 'TG_API_HASH': '"; leak'},
                            {'TG_API_ID': '9999999999', 'TG_API_HASH': 'a' * 32}):
            with self.assertRaises(ValueError): credentials(environment)
        dummy = credentials({}, compile_only=True)
        self.assertEqual(dummy, ('2147483647', '0' * 32))
        with self.assertRaises(ValueError): credentials(dict(zip(('TG_API_ID', 'TG_API_HASH'), dummy)))

    def test_configuration(self):
        with tempfile.TemporaryDirectory() as temp:
            path = emit(Path(temp), Path('/build/bazel'), SYNTHETIC_ENV)
            source = path.read_text()
            self.assertIn('telegram_bundle_id = "com.salatik.tgru"', source)
            self.assertIn('telegram_use_xcode_managed_codesigning = False', source)
            self.assertIn('telegram_enable_watch = False', source)
            self.assertIn('telegram_aps_environment = ""', source)
            self.assertNotIn('ph.telegra.Telegraph', source)
            self.assertEqual((path.parent / 'provisioning/BUILD').read_text(), 'exports_files([])\n')

    def test_valid_device_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'test.ipa'; archive(path)
            report = verify(path)
            self.assertEqual(report['platform'], 'iOS-device')
            self.assertFalse(report['signed_for_installation'])
            self.assertFalse(report['device_login_tested'])

    def test_reject_simulator_extensions_and_path_escape(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'test.ipa'
            for kwargs in ({'platform': 7}, {'bundle': 'ph.telegra.Telegraph'},
                           {'extra': '../escape'}, {'extra': 'Payload/Telegram.app/PlugIns/Other.appex/a'},
                           {'extra': 'Payload/Telegram.app/embedded.mobileprovision'}):
                archive(path, **kwargs)
                with self.assertRaises(ValueError): verify(path)

    def test_truncated_macho(self):
        for data in (b'', binary()[:40], binary(cpu=7)):
            with self.assertRaises((ValueError, struct.error)): macho(data)

    def test_manual_workflow_pins(self):
        workflow = ROOT / '.github/workflows/ios-unsigned.yml'
        if not workflow.is_file():
            workflow = ROOT.parent / '.github/workflows/ios-unsigned.yml'
        source = workflow.read_text(encoding='utf-8')
        self.assertIn('workflow_dispatch:', source)
        self.assertNotIn('pull_request:', source)
        self.assertNotIn('push:', source)
        self.assertIn('timeout-minutes: 120', source)
        self.assertIn('persist-credentials: false', source)
        self.assertIn(LOCK['commit'], source)
        self.assertNotIn('@main', source)
        self.assertNotIn('@v4', source)

if __name__ == '__main__': unittest.main(verbosity=2)
