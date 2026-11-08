"""Build a self-targeted local codec fixture APK. Does not sign, install or run it."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent.parent
ALIAS = Path('C:/Users/andre/Documents/Codex/familygram-build-2026-11-08/qa/pcm-decoder')
SDK = Path('C:/Users/andre/AppData/Local/Android/Sdk')
JDK = Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
TOOLS = SDK / 'build-tools/37.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

if not ALIAS.is_dir() or not os.path.samefile(ALIAS, ROOT):
    raise ValueError('ASCII build alias must resolve to this exact QA directory')
build = ROOT / 'codec-build'
for name in ('classes', 'dex', 'assets'):
    (build / name).mkdir(parents=True, exist_ok=True)
fixture = PROJECT / 'qa/system-speech/build/synthetic-ru.wav'
if not fixture.is_file() or not 44 < fixture.stat().st_size < 16 * 1024 * 1024:
    raise ValueError('Expected existing synthetic speech WAV fixture')
shutil.copy2(fixture, build / 'assets/synthetic-ru.wav')
source = PROJECT / 'overlay/java/org/telegram/messenger/family/FamilyPcmDecoder.java'
diagnostic = ROOT / 'CodecDiagnostic.java'
inputs = {str(p.relative_to(PROJECT)): sha(p) for p in (source, diagnostic, fixture)}
run(JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-classpath', ANDROID,
    '-d', build / 'classes', source, diagnostic)
with zipfile.ZipFile(build / 'codec-check.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in (build / 'classes').rglob('*.class'):
        archive.write(path, path.relative_to(build / 'classes').as_posix())
run(JDK / 'java.exe', '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '29',
    '--lib', ANDROID, '--output', build / 'dex', build / 'codec-check.jar')
run(TOOLS / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', ALIAS / 'AndroidManifest.xml',
    '-A', ALIAS / 'codec-build/assets', '-o', ALIAS / 'codec-build/unaligned.apk')
with zipfile.ZipFile(build / 'unaligned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write(build / 'dex/classes.dex', 'classes.dex')
apk = ROOT / 'Codec-Check-unsigned.apk'
run(TOOLS / 'zipalign.exe', '-f', '-P', '16', '4', ALIAS / 'codec-build/unaligned.apk', ALIAS / apk.name)
run(TOOLS / 'zipalign.exe', '-c', '-P', '16', '4', ALIAS / apk.name)
if inputs != {str(p.relative_to(PROJECT)): sha(p) for p in (source, diagnostic, fixture)}:
    raise ValueError('Codec test inputs changed during compilation')
(build / 'report.json').write_text(json.dumps({'inputs': inputs, 'unsigned_sha256': sha(apk), 'target_package': 'com.salatik.familygram.codeccheck', 'permissions': [], 'runtime': 'not run'}, indent=2), encoding='utf-8')
print('Built self-targeted codec test APK; not signed, installed or run: ' + str(apk))
