"""Build the full pinned Telegram application without an Apple signing identity."""
import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import urllib.request
from common import ROOT, LOCK, credentials, digest, owned, write_json
from configuration import emit
from prepare import prepare
from verify_ipa import verify

def capture(*args):
    return subprocess.check_output(args, text=True).strip()

def preflight(compile_only=False):
    owned(); credentials(compile_only=compile_only)
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise ValueError('Build requires an Apple Silicon macOS runner')
    if capture('sw_vers', '-productVersion').split('.')[0] != LOCK['macos_major']:
        raise ValueError('Pinned macOS major is unavailable')
    xcode = capture('xcodebuild', '-version')
    expected = 'Xcode ' + LOCK['xcode_version'] + '\nBuild version ' + LOCK['xcode_build']
    if xcode != expected: raise ValueError('Pinned Xcode version/build is unavailable')
    print('PREFLIGHT_OK: exact Apple toolchain; ' + ('COMPILE_ONLY dummy configuration' if compile_only else 'own API credentials present (not printed)'))

def download_bazel(source):
    name = 'bazel-' + LOCK['bazel_version'] + '-darwin-arm64'
    path = source / 'build-input' / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        url = 'https://github.com/bazelbuild/bazel/releases/download/' + LOCK['bazel_version'] + '/' + name
        temporary = path.with_suffix('.download')
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as out:
            shutil.copyfileobj(response, out)
        if digest(temporary) != LOCK['bazel_sha256']:
            temporary.unlink(); raise ValueError('Bazel download SHA256 mismatch')
        temporary.replace(path)
    if digest(path) != LOCK['bazel_sha256']: raise ValueError('Bazel SHA256 mismatch')
    path.chmod(0o755)
    if capture(str(path), '--version') != 'bazel ' + LOCK['bazel_version']:
        raise ValueError('Bazel version mismatch')
    return path

def build(number, compile_only=False):
    preflight(compile_only)
    source = ROOT / 'upstream'
    if not source.is_dir() or source.is_symlink(): raise ValueError('Pinned upstream checkout required')
    if capture('git', '-C', str(source), 'rev-parse', 'HEAD') != LOCK['commit']:
        raise ValueError('Upstream Git commit mismatch')
    if capture('git', '-C', str(source), 'status', '--porcelain'):
        raise ValueError('Build requires a fresh upstream checkout')
    submodules = capture('git', '-C', str(source), 'submodule', 'status', '--recursive')
    if any(line[:1] in ('-', '+', 'U') for line in submodules.splitlines()):
        raise ValueError('Uninitialized or mismatched upstream submodule')
    rules = source / 'build-system/bazel-rules/rules_apple'
    if capture('git', '-C', str(rules), 'rev-parse', 'HEAD') != LOCK['rules_apple_commit']:
        raise ValueError('Rules Apple commit mismatch')
    report = prepare(source, compile_only=compile_only)
    changed = capture('git', '-C', str(source), 'diff', '--name-only').splitlines()
    report['final_modified_sources'] = [{'path': relative, 'sha256': digest(source / relative)} for relative in changed]
    bazel = download_bazel(source)
    configuration = emit(source, bazel, compile_only=compile_only)
    api_id, api_hash = credentials(compile_only=compile_only)
    api_pattern = re.compile(r'(?<![0-9])' + re.escape(api_id) + r'(?![0-9])')
    def redacted(value):
        return api_pattern.sub('[API_ID]', value.replace(api_hash, '[API_HASH]'))
    dist = ROOT / 'dist'; dist.mkdir(exist_ok=True)
    env = dict(os.environ)
    for key in ('TG_API_ID', 'TG_API_HASH'): env.pop(key, None)
    def run(arguments, use_clean_environment=True, check_result=True):
        # Build failures may include compiler defines. Never persist or print them raw.
        with (dist / 'build.log').open('a', encoding='utf-8') as log:
            proc = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding='utf-8', errors='replace', env=env)
            for line in proc.stdout:
                safe = redacted(line); sys.stdout.write(safe); log.write(safe)
            code = proc.wait()
        if check_result and code: raise RuntimeError('Native build failed; see sanitized build.log')
        return code
    old_dir = Path.cwd()
    try:
        os.chdir(source)
        sys.path.insert(0, str(source / 'build-system/Make'))
        make = importlib.import_module('Make')
        make.call_executable = run
        if make.PostboxImportCheck.run(str(source)) != 0: raise ValueError('Upstream Postbox import guard failed')
        command = make.BazelCommandLine(str(bazel), False, False, None)
        command.common_args += ['--features=disable_legacy_signing',
                                '--//Telegram:disableProvisioningProfiles', '--//Telegram:disableExtensions',
                                '--jobs=3']
        if compile_only:
            command.common_args += ['--@build_bazel_rules_swift//swift:copt=-DFAMILY_COMPILE_ONLY']
        command.set_configuration('release_arm64')
        command.set_build_number(number)
        command.invoke_build()  # Full upstream Telegram/Telegram target, unchanged target selection.
        artifact = source / 'bazel-bin/Telegram/Telegram.ipa'
        report['ipa'] = verify(artifact)
        report['ipa']['local_features'] = json.loads(capture(
            sys.executable, str(ROOT / 'features/resourcesintegration.py'), '--verify-ipa', str(artifact)
        ))
        target = dist / ('BASELINE-NOT-CONNECTED.ipa' if compile_only else 'TG-ru-unsigned.ipa')
        shutil.copyfile(artifact, target)
        report.update({'toolchain': {key: LOCK[key] for key in ('xcode_version', 'xcode_build', 'macos_major', 'bazel_version')},
                       'submodules': submodules.splitlines(), 'ipa_sha256': digest(target),
                       'build_kind': 'unsigned-native-baseline', 'compile_only': compile_only, 'production_ready': False})
        write_json(dist / 'build-manifest.json', report)
        (dist / 'SHA256SUMS.txt').write_text(digest(target) + '  ' + target.name + '\n')
        print('UNSIGNED_IPA_VALIDATED: local signing and device tests still required')
    finally:
        configuration.unlink(missing_ok=True)
        os.chdir(old_dir)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--preflight', action='store_true')
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('--build-number', type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.build_number <= 2147483647: raise SystemExit('Invalid build number')
    try:
        if args.preflight: preflight(args.compile_only)
        else: build(args.build_number, args.compile_only)
    except Exception as error:
        # Exceptions from subprocesses/config parsing must not print credentials.
        print('BUILD_FAILED: ' + type(error).__name__, file=sys.stderr)
        if isinstance(error, (ValueError, RuntimeError)): print(str(error), file=sys.stderr)
        raise SystemExit(1)
