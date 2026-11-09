"""Pinned resource packaging; no downloads, Mac tools, signing or application code execution.

rules_apple 485016eb5b0948cc17307064e7977a6d68e94046:
apple_bundle_import buckets files unprocessed with bundle_relative_parent_dir.
Thus Telegram/FamilyPython.bundle/family-python/runner/index.html is emitted as
<App>.app/FamilyPython.bundle/family-python/runner/index.html (not flattened).
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import plistlib
import re
import zipfile

FEATURES = Path(__file__).resolve().parent
ASSETS = FEATURES / 'python/Resources/family-python/runner'
RUNTIME_LOCK = FEATURES / 'python/Resources/runtime-lock.json'
BUILD_LOCK = FEATURES / 'resources-lock.json'
BUNDLE_PATH = 'Telegram/FamilyPython.bundle'
APP_BUNDLE_PATH = 'FamilyPython.bundle'
DESCRIPTION = 'Распознавание голосовых сообщений и кружков выполняется только на устройстве по вашему запросу.'
ORIGINAL_DESCRIPTION = 'We need this to transcribe audio messages on your request.'
RULE = '''apple_bundle_import(
    name = "FamilyPythonResources",
    bundle_imports = glob(["FamilyPython.bundle/**"]),
)

'''
LOAD = 'load("@build_bazel_rules_apple//apple:resources.bzl", "apple_bundle_import")\n'

def sha(data): return hashlib.sha256(data).hexdigest()

def replace_one(value, old, new):
    if value.count(old) != 1: raise ValueError('Resource anchor mismatch: ' + old[:100])
    return value.replace(old, new, 1)

def info_fragment(build):
    matches = re.findall(r'plist_fragment\(\s*name = "TelegramInfoPlist",\s*extension = "plist",\s*template\s*=\s*"""(.*?)"""', build, re.S)
    if len(matches) != 1: raise ValueError('Expected one app Info.plist template')
    return plistlib.loads(('<plist version="1.0"><dict>' + matches[0] + '</dict></plist>').encode())

def transform_build(build):
    if 'FamilyPythonResources' in build: raise ValueError('Unexpected existing resource target')
    build = LOAD + build
    anchor = 'swift_library(\n    name = "Lib",'
    build = replace_one(build, anchor, RULE + anchor)
    start = build.index(anchor)
    end = build.index('\n)\n', start)
    lib = replace_one(build[start:end], '    data = [\n', '    data = [\n        ":FamilyPythonResources",\n')
    build = build[:start] + lib + build[end:]
    # This exact pinned app template already has the Speech key. Never add a duplicate.
    build = replace_one(build, '<key>NSSpeechRecognitionUsageDescription</key>\n    <string>' + ORIGINAL_DESCRIPTION + '</string>',
                        '<key>NSSpeechRecognitionUsageDescription</key>\n    <string>' + DESCRIPTION + '</string>')
    if info_fragment(build).get('NSSpeechRecognitionUsageDescription') != DESCRIPTION:
        raise ValueError('Generated app Speech permission description mismatch')
    return build

def bundle_files():
    lock = json.loads(RUNTIME_LOCK.read_text('utf-8'))
    if lock['version'] != '314.0.7': raise ValueError('Runtime version mismatch')
    output = {}
    for name, expected in lock['files'].items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts: raise ValueError('Unsafe resource path')
        source = (ASSETS / name).resolve()
        source.relative_to(ASSETS.resolve())
        data = source.read_bytes()
        if sha(data) != expected: raise ValueError('Resource digest mismatch: ' + name)
        output['family-python/runner/' + name] = data
    output['Info.plist'] = plistlib.dumps({
        'CFBundleIdentifier': 'com.salatik.tgru.python.resources',
        'CFBundleName': 'FamilyPython', 'CFBundlePackageType': 'BNDL',
        'CFBundleInfoDictionaryVersion': '6.0', 'CFBundleVersion': '1',
        'CFBundleShortVersionString': '1.0'
    }, sort_keys=True)
    return output

def prepare(checkout):
    """Return all checked writes. Caller validates the rest of the feature plan before applying any."""
    checkout = Path(checkout).resolve()
    lock = json.loads(BUILD_LOCK.read_text('utf-8'))
    target = checkout / 'Telegram/BUILD'
    before = target.read_bytes(); digest = sha(before)
    accepted = lock['builds']
    if digest in accepted:
        after = transform_build(before.decode()).encode()
        if sha(after) != accepted[digest]['after_sha256']: raise ValueError('Resource patch output lock mismatch')
    elif digest in {entry['after_sha256'] for entry in accepted.values()}:
        after = before
        if info_fragment(after.decode()).get('NSSpeechRecognitionUsageDescription') != DESCRIPTION:
            raise ValueError('Speech key missing from already patched app template')
    else:
        raise ValueError('Telegram/BUILD is neither pinned upstream, supported Personal Team variant nor exact resource output')
    files = bundle_files()
    destination = checkout / BUNDLE_PATH
    if destination.is_symlink(): raise ValueError('Symlink resource bundle rejected')
    if destination.exists():
        for path in destination.rglob('*'):
            if path.is_symlink(): raise ValueError('Symlink resource rejected')
            if path.is_file() and (path.relative_to(destination).as_posix() not in files or path.read_bytes() != files[path.relative_to(destination).as_posix()]):
                raise ValueError('Unexpected preexisting bundle resource: ' + path.relative_to(destination).as_posix())
    return [(target, after)] + [(destination / name, data) for name, data in files.items()]

def write_plan(plan):
    for target, data in plan:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

def verify_ipa(ipa):
    """Inspect an actual built archive (or an explicitly labelled test fixture); no extraction/signing."""
    expected = bundle_files()
    with zipfile.ZipFile(ipa) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)): raise ValueError('Duplicate ZIP paths')
        roots = [name[:-len('Info.plist')] for name in names if re.fullmatch(r'Payload/[^/]+\.app/Info\.plist', name)]
        if len(roots) != 1: raise ValueError('Expected one main app')
        root = roots[0]
        info = plistlib.loads(archive.read(root + 'Info.plist'))
        if info.get('NSSpeechRecognitionUsageDescription') != DESCRIPTION: raise ValueError('App Speech permission string missing or different')
        prefix = root + APP_BUNDLE_PATH + '/'
        actual = {name[len(prefix):] for name in names if name.startswith(prefix) and not name.endswith('/')}
        # Real signing can add a CodeResources seal to a resource bundle. It is not runtime input.
        actual = {name for name in actual if not name.startswith('_CodeSignature/')}
        if actual != set(expected): raise ValueError('Resource bundle hierarchy/file set mismatch')
        for name, data in expected.items():
            packaged = archive.read(prefix + name)
            if name == 'Info.plist':
                if plistlib.loads(packaged) != plistlib.loads(data): raise ValueError('Resource bundle metadata mismatch')
            elif sha(packaged) != sha(data): raise ValueError('Packaged runtime changed: ' + name)
        return {'bundle': prefix.rstrip('/'), 'runtime_files': len(expected) - 1, 'speech_permission': True}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--checkout', type=Path)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--verify-ipa', type=Path)
    args = parser.parse_args()
    if args.verify_ipa: print(json.dumps(verify_ipa(args.verify_ipa), ensure_ascii=False))
    elif args.checkout:
        plan = prepare(args.checkout)
        if not args.check_only: write_plan(plan)
        print(('Validated' if args.check_only else 'Packaged'), len(plan) - 1, 'bundle resources plus Telegram/BUILD')
    else: parser.error('--checkout or --verify-ipa is required')
