from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[2]
qa=Path(__file__).resolve().parent
build=qa/'build/context-entry';build.mkdir(parents=True,exist_ok=True)
jdk=Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
android=Path('C:/Users/andre/AppData/Local/Android/Sdk/platforms/android-35/android.jar')
stubs={
 'android/os/Looper.java':'package android.os; public class Looper {static final Looper MAIN=new Looper();public static Looper getMainLooper(){return MAIN;}public static Looper myLooper(){return MAIN;}}',
 'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}public void removeCallbacks(Runnable r){}public boolean postDelayed(Runnable r,long delay){return true;}}',
 'org/telegram/messenger/family/FamilyAccess.java':'package org.telegram.messenger.family;public class FamilyAccess {public static boolean isAllowed(){return true;}}',
 'org/telegram/messenger/family/FamilyChatTools.java':'package org.telegram.messenger.family;public class FamilyChatTools {public static boolean enabled(android.content.Context c,String s){return true;}}',
}
paths=[]
for name,source in stubs.items():
 file=build/'stubs'/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(source);paths.append(file)
source=root/'overlay/java/org/telegram/messenger/family'
files=[source/(name+'.java') for name in ['FamilyInlinePython','FamilyPythonSnippets','FamilyPythonRunner']]
subprocess.run([str(jdk/'javac.exe'),'--release','17','-classpath',str(android),'-d',str(build),*map(str,files+paths+[qa/'SnippetTest.java',qa/'ContextEntryTest.java'])],check=True)
for name in ['SnippetTest','ContextEntryTest']:
 subprocess.run([str(jdk/'java.exe'),'-cp',str(build)+';'+str(android),'org.telegram.messenger.family.'+name],check=True)
text=(source/'FamilyInlinePython.java').read_text(encoding='utf-8')
assert 'startActivity' not in text and 'FamilyPythonActivity' not in text
assert 'button.performClick()' in text and 'if(!fresh(snippet.key))return;' in text
assert 'if(chooser!=null){chooser.dismiss();chooser=null;}' in text
print('PASS same native button listener, stale-click guard, chooser dismissal on recycle; no editor or Activity launch in helper')
