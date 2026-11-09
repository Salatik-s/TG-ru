"""Hash-checked changes to the fresh, owned upstream checkout. No signing secrets."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import sys
from common import ROOT, LOCK, contained, digest, verify_sources

def patch_build(source, compile_only=False):
    needle = '    name = "TelegramEntitlements",'
    start = source.index(needle)
    end = source.index('\n)\n', start)
    original = source[start:end]
    if original.count('template = "".join([') != 1:
        raise ValueError('Entitlements source pin mismatch')
    replacement = original[:original.index('    template =')] + '    template = ""'
    result = source[:start] + replacement + source[end:]
    name = '    <key>CFBundleDisplayName</key>\n    <string>Telegram</string>'
    if result.count(name) != 2:
        raise ValueError('Display name source pin mismatch')
    display = 'TG-ru Baseline' if compile_only else 'TG-ru'
    return result.replace(name, '    <key>CFBundleDisplayName</key>\n    <string>' + display + '</string>')

def patch_personal_delegate(source):
    background = '        configuration.sharedContainerIdentifier = appGroupName'
    container = '        let maybeAppGroupUrl = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupName)'
    if source.count(background) != 1 or source.count(container) != 1:
        raise ValueError('Personal Team AppDelegate source pin mismatch')
    source = source.replace(background, '        configuration.sharedContainerIdentifier = nil // Personal Team: no App Group entitlement.')
    return source.replace(container, '''        // Personal Team: all account data belongs to this application's private container.
        let maybeAppGroupUrl: URL? = {
            guard let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?.appendingPathComponent("TG-ru", isDirectory: true) else {
                return nil
            }
            do {
                try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
                return url
            } catch {
                return nil
            }
        }()''')

def patch_personal_passcode(source):
    old = '        query[kSecAttrAccessGroup as String] = try account.hasPrefix("biometric.") ? self.environment.privateAccessGroup() : self.environment.sharedAccessGroup()'
    if source.count(old) != 1:
        raise ValueError('Personal Team passcode source pin mismatch')
    return source.replace(old, '        query[kSecAttrAccessGroup as String] = try self.environment.privateAccessGroup() // Personal Team: keep passcodes in the signed app\'s own Keychain group.')

def prepare(source, run_integrations=True, compile_only=False):
    source = Path(source).resolve()
    verify_sources(source)  # All old hashes checked BEFORE any mutation.
    transforms = {
        'Telegram/BUILD': lambda value: patch_build(value, compile_only),
        'submodules/TelegramUI/Sources/AppDelegate.swift': patch_personal_delegate,
        'submodules/PasscodeCore/Sources/Passcode.swift': patch_personal_passcode,
    }
    changes = []
    report = {'upstream': LOCK['commit'], 'patches': [], 'copied': []}
    for relative, transform in transforms.items():
        target = contained(source, relative)
        before = target.read_bytes(); after = transform(before.decode('utf-8')).encode('utf-8')
        changes.append((target, after))
        report['patches'].append({'path': relative, 'before_sha256': hashlib.sha256(before).hexdigest(),
            'after_sha256': hashlib.sha256(after).hexdigest(), 'purpose': 'Personal Team standalone application'})
    integration = json.loads((ROOT / 'build-support/integration.json').read_text())
    if integration.get('version') != 1:
        raise ValueError('Integration manifest version mismatch')
    scripts = []
    for entry in integration.get('scripts', []):
        script = contained(ROOT, entry['path'])
        if not entry['path'].startswith('patches/') or digest(script) != entry['sha256']:
            raise ValueError('Integration script hash mismatch')
        args = entry.get('args', ['--source', '{upstream}'])
        if not isinstance(args, list) or not all(isinstance(a, str) for a in args):
            raise ValueError('Invalid integration arguments')
        scripts.append((script, [a.replace('{upstream}', str(source)) for a in args]))
    copies = []
    for module in ('transport', 'access', 'features', 'privacy', 'family-core', 'family-ui'):
        folder = ROOT / module
        if not folder.exists():
            continue
        for path in sorted(folder.rglob('*')):
            if path.is_symlink():
                raise ValueError('Overlay symlinks are not accepted')
            if not path.is_file() or any(p in {'.build', '__pycache__', '.git', 'build', 'out'} for p in path.relative_to(folder).parts):
                continue
            relative = path.relative_to(ROOT).as_posix()
            if path.name.startswith('.env') or path.suffix.lower() in {'.key', '.pem', '.p12', '.mobileprovision', '.sqlite', '.ipa'}:
                raise ValueError('Private/compiled file in source overlay')
            dest = contained(source, relative)
            if dest.exists():
                raise ValueError('Overlay would overwrite upstream: ' + relative)
            copies.append((path, dest))
            report['copied'].append({'path': relative, 'sha256': digest(path)})
    for target, after in changes:
        target.write_bytes(after)
    for path, dest in copies:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, dest)
    if run_integrations:
        for script, args in scripts:
            subprocess.run([sys.executable, str(script), *args], cwd=ROOT, check=True)
            report['patches'].append({'integration': script.relative_to(ROOT).as_posix(), 'sha256': digest(script)})
    return report
