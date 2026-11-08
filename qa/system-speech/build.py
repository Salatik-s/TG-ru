"""Build only the separate test-only system speech APK. No adb or production edits."""
from pathlib import Path
import hashlib
import os
import subprocess
import wave
import zipfile

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent.parent
ALIAS = Path('C:/Users/andre/Documents/Codex/familygram-build-2026-11-08/qa/system-speech')
SDK = Path('C:/Users/andre/AppData/Local/Android/Sdk')
JDK = Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
TOOLS = SDK / 'build-tools/37.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'
if not os.path.samefile(ROOT, ALIAS):
    raise ValueError('Unexpected build alias')
build = ROOT / 'build'
for folder in ('classes', 'dex', 'assets'):
    (build / folder).mkdir(parents=True, exist_ok=True)
sample = PROJECT / 'tools/asr-source-cache/third_party/whisper.cpp/samples/jfk.wav'
with wave.open(str(sample), 'rb') as source:
    assert source.getnchannels() == 1 and source.getframerate() == 16000 and source.getsampwidth() == 2
    pcm = source.readframes(source.getnframes())
(build / 'assets/jfk.pcm').write_bytes(pcm)
for name in ['synthetic-ru','synthetic-en']:
    if (build/(name+'.wav')).is_file():
        with wave.open(str(build/(name+'.wav')),'rb') as source:
            assert source.getnchannels()==1 and source.getframerate()==16000 and source.getsampwidth()==2
            (build/'assets'/(name+'.pcm')).write_bytes(source.readframes(source.getnframes()))

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

run(JDK/'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-classpath', ANDROID,
    '-d', build/'classes', ROOT/'SystemSpeechDiagnostic.java',
    PROJECT/'overlay/java/org/telegram/messenger/family/FamilySystemSpeech.java')
with zipfile.ZipFile(build/'diagnostic.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for item in (build/'classes').rglob('*.class'):
        jar.write(item, item.relative_to(build/'classes').as_posix())
run(JDK/'java.exe', '-cp', TOOLS/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '33',
    '--lib', ANDROID, '--output', build/'dex', build/'diagnostic.jar')
run(TOOLS/'aapt2.exe', 'link', '-I', ANDROID, '--manifest', ALIAS/'AndroidManifest.xml', '-o', ALIAS/'build/un-aligned.apk')
with zipfile.ZipFile(build/'un-aligned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write(build/'dex/classes.dex', 'classes.dex')
    for sample in (build/'assets').glob('*.pcm'):apk.write(sample,'assets/'+sample.name)
run(TOOLS/'zipalign.exe', '-f', '4', ALIAS/'build/un-aligned.apk', ALIAS/'build/System-Speech-Diagnostic-unsigned.apk')
print('SYSTEM_SPEECH_DIAGNOSTIC_BUILT: no install or recognition performed')
print('PUBLIC_PCM_SHA256=' + hashlib.sha256(pcm).hexdigest())
