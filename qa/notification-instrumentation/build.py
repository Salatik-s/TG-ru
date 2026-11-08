"""Build a separate, test-only notification inspector. No adb or device actions.

Use --sign to sign with the EXISTING application key via the established signer.
Never creates a new key, reads app data, modifies Defender, or changes the APK.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent.parent
ALIAS = Path('C:/Users/andre/Documents/Codex/familygram-build-2026-11-08/qa/notification-instrumentation')
SDK = Path('C:/Users/andre/AppData/Local/Android/Sdk')
JDK = Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
TOOLS = SDK / 'build-tools/37.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sign', action='store_true')
    args = parser.parse_args()
    if not ALIAS.is_dir() or not os.path.samefile(ROOT, ALIAS):
        raise ValueError('Unexpected build alias')
    if args.sign:
        private = Path.home() / '.codex/private/familygram-release'
        if not (private / 'signing.json').is_file() or not (private / 'familygram-release.jks').is_file():
            raise ValueError('Existing application signing key required; no replacement will be created')
    build = ROOT / 'build'
    if build.is_symlink() or not build.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError('Build path escapes the instrumentation directory')
    build.mkdir(exist_ok=True)
    # Unique compilation directory avoids carrying obsolete diagnostic classes.
    # It is retained under ignored build/, so this script performs no deletion.
    stage = Path(tempfile.mkdtemp(prefix='stage-', dir=build))
    if not stage.resolve().is_relative_to(build.resolve()):
        raise ValueError('Compiler output path escaped')
    stage_alias = ALIAS / 'build' / stage.name
    for name in ('classes', 'dex'): (stage / name).mkdir()
    source = ROOT / 'NotificationDiagnostic.java'
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    run(JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-classpath', ANDROID,
        '-d', stage / 'classes', source)
    if hashlib.sha256(source.read_bytes()).hexdigest() != before:
        raise ValueError('Diagnostic source changed during compilation')
    with zipfile.ZipFile(stage / 'diagnostic.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
        for item in sorted((stage / 'classes').rglob('*.class')):
            jar.write(item, item.relative_to(stage / 'classes').as_posix())
    run(JDK / 'java.exe', '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26',
        '--lib', ANDROID, '--output', stage / 'dex', stage / 'diagnostic.jar')
    run(TOOLS / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', ALIAS / 'AndroidManifest.xml', '-o', stage_alias / 'unaligned.apk')
    with zipfile.ZipFile(stage / 'unaligned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
        apk.write(stage / 'dex/classes.dex', 'classes.dex')
    unsigned = ALIAS / 'build/Notification-Diagnostic-unsigned.apk'
    run(TOOLS / 'zipalign.exe', '-f', '-P', '16', '4', stage_alias / 'unaligned.apk', unsigned)
    run(TOOLS / 'zipalign.exe', '-c', '-P', '16', '4', unsigned)
    output = unsigned
    if args.sign:
        output = ALIAS / 'build/Notification-Diagnostic.apk'
        run('powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            PROJECT / 'build-support/Sign.ps1', '-InputApk', unsigned, '-OutputApk', output)
    print('NOTIFICATION_DIAGNOSTIC_BUILT: no install or notification inspection performed')
    print('APK_SHA256=' + hashlib.sha256(output.read_bytes()).hexdigest())
    print('APK=' + str(output))

if __name__ == '__main__': main()
