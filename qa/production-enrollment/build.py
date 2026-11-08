"""Build the separate QA instrumentation APK; never changes the production APK."""
from pathlib import Path
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
ALIAS = Path('C:/Users/andre/Documents/Codex/familygram-build-2026-11-08/qa/production-enrollment')
SDK = Path('C:/Users/andre/AppData/Local/Android/Sdk')
JDK = Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
TOOLS = SDK / 'build-tools/37.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'


def run(*arguments):
    subprocess.run([str(value) for value in arguments], check=True)


if not ALIAS.is_dir() or not os.path.samefile(ALIAS, ROOT):
    raise ValueError('The known ASCII build alias must point to this instrumentation directory')
build = ROOT / 'build'
for folder in ('classes', 'dex'):
    (build / folder).mkdir(parents=True, exist_ok=True)

run(JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-classpath', ANDROID,
    '-d', build / 'classes', ROOT / 'ProductionEnrollment.java')
with zipfile.ZipFile(build / 'instrumentation.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for path in (build / 'classes').rglob('*.class'):
        jar.write(path, path.relative_to(build / 'classes').as_posix())
run(JDK / 'java.exe', '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '24',
    '--lib', ANDROID, '--output', build / 'dex', build / 'instrumentation.jar')
run(TOOLS / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', ALIAS / 'AndroidManifest.xml',
    '-o', ALIAS / 'build/un-aligned.apk')
with zipfile.ZipFile(build / 'un-aligned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write(build / 'dex/classes.dex', 'classes.dex')
run(TOOLS / 'zipalign.exe', '-f', '4', ALIAS / 'build/un-aligned.apk', ALIAS / 'build/Production-Enrollment-unsigned.apk')
print('Built separate QA instrumentation APK; production APK unchanged.')

