from pathlib import Path
import os,subprocess,zipfile,tempfile,shutil
root=Path(__file__).resolve().parent
alias=Path('C:/Users/andre/Documents/Codex/familygram-build-2026-11-08/qa/tgnet-diagnostic')
sdk=Path('C:/Users/andre/AppData/Local/Android/Sdk');jdk=Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin');tools=sdk/'build-tools/37.0.0';android=sdk/'platforms/android-35/android.jar'
build=root/'build'
for name in ('classes','dex','assets'):(build/name).mkdir(parents=True,exist_ok=True)
shutil.copyfile(Path(tempfile.gettempdir())/'familygram-tgnet-diagnostic/test-profile.json',build/'assets/test-profile.json')
def run(*a):subprocess.run([str(x) for x in a],check=True)
run(jdk/'javac.exe','--release','17','-classpath',android,'-d',build/'classes',root/'TgnetInstrumentation.java')
with zipfile.ZipFile(build/'classes.jar','w') as z:
 for f in (build/'classes').rglob('*.class'):z.write(f,f.relative_to(build/'classes').as_posix())
run(jdk/'java.exe','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','24','--lib',android,'--output',build/'dex',build/'classes.jar')
run(tools/'aapt2.exe','link','-I',android,'--manifest',alias/'AndroidManifest.xml','-A',alias/'build/assets','-o',alias/'build/raw.apk')
with zipfile.ZipFile(build/'raw.apk','a') as z:z.write(build/'dex/classes.dex','classes.dex')
run(tools/'zipalign.exe','-f','4',alias/'build/raw.apk',alias/'build/unsigned.apk')
