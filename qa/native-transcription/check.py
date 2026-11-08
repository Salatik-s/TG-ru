"""Host tests for helper state/identity and exact native wrappers; no device or account."""
from pathlib import Path
import subprocess, importlib.util, hashlib
ROOT = Path(__file__).resolve().parents[2]
QA = Path(__file__).resolve().parent
BUILD = QA/'build'
JDK = Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
ANDROID = Path('C:/Users/andre/AppData/Local/Android/Sdk/platforms/android-35/android.jar')
STUBS = {
 'android/os/Looper.java': 'package android.os; public class Looper {static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return MAIN;}}',
 'android/os/Handler.java': 'package android.os; public class Handler {public Handler(Looper l){} public boolean post(Runnable r){r.run();return true;}}',
 'android/content/Context.java': 'package android.content; public class Context { public Context getApplicationContext(){return this;} public android.content.res.Resources getResources(){return new android.content.res.Resources();} public static String language="ru-RU"; public android.content.SharedPreferences getSharedPreferences(String n,int m){return (android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.class},(p,method,args)->method.getName().equals("getString")?language:null);} public String getPackageName(){return "qa";} public String getString(int id){return "qa";}}',
 'android/content/res/Resources.java': 'package android.content.res; public class Resources {public int getIdentifier(String a,String b,String c){return 0;}}',
 'org/telegram/messenger/family/FamilyAccess.java': 'package org.telegram.messenger.family; public class FamilyAccess { public static boolean allowed=true; public static boolean isAllowed(){return allowed;}}',
 'org/telegram/messenger/family/FamilyLocalTranscriber.java': '''package org.telegram.messenger.family; import android.content.Context; public class FamilyLocalTranscriber {
 public interface Callback {void onState(String s);void onResult(String s);void onError(String s);}
 public static int starts; public static Callback last;
 public static boolean eligible(Object m){return m!=null;}
 public static Job start(Context c,Object m,int a,Callback cb){starts++;last=cb;return new Job(cb);}
 public static class Job {Callback cb;boolean running=true;Job(Callback c){cb=c;} public void cancel(){running=false;cb.onError("CANCELLED");}public boolean isRunning(){return running;}}
}''',
 'org/telegram/messenger/family/FamilySystemSpeech.java': '''package org.telegram.messenger.family; import android.content.Context; public class FamilySystemSpeech {public interface Callback{void onResult(String s);void onError(String s);void onProgress(int a,int b);}public static void downloadLanguage(Context c,String l,Callback cb){throw new AssertionError("Unexpected model download");}}''',
 'org/telegram/messenger/ApplicationLoader.java': 'package org.telegram.messenger;public class ApplicationLoader {public static android.content.Context applicationContext=new android.content.Context();}',
 'org/telegram/messenger/UserConfig.java': '''package org.telegram.messenger; public class UserConfig {public static long[] owners={100,200};int account;UserConfig(int a){account=a;}public static UserConfig getInstance(int a){return new UserConfig(a);}public long getClientUserId(){return owners[account];}}''',
 'org/telegram/messenger/DialogObject.java': '''package org.telegram.messenger;public class DialogObject {public static boolean isEncryptedDialog(long id){return id==999;}}''',
 'org/telegram/messenger/NotificationCenter.java': '''package org.telegram.messenger;public class NotificationCenter {public static int voiceTranscriptionUpdate=7,count;public static Object[] last; public static NotificationCenter getInstance(int account){return new NotificationCenter();}public void postNotificationName(int id,Object... args){count++;last=args;}}''',
 'org/telegram/messenger/MessageObject.java': '''package org.telegram.messenger; public class MessageObject {public int currentAccount,id=1;public long dialog=10;public boolean isRepostPreview,forceUpdate;public Owner messageOwner=new Owner();public Document document=new Document();public Object getDocument(){return document;}public long getDialogId(){return dialog;}public int getId(){return id;}public double getDuration(){return 5.0;}public boolean isVoice(){return true;}public boolean isRoundVideo(){return false;}public boolean isQuickReply(){return false;}public boolean isOutOwner(){return false;}public boolean isSent(){return true;}public static class Owner {public Media media=new Media();public String voiceTranscription;public long voiceTranscriptionId;public boolean voiceTranscriptionOpen,voiceTranscriptionFinal;public Object translatedVoiceTranscription;}public static class Media {public int ttl_seconds;}public static class Document {public long id=50,size=4000;public int dc_id=2;}}''',
 'org/telegram/ui/Components/TranscribeButton.java': '''package org.telegram.ui.Components;public class TranscribeButton {public static void openVideoTranscription(org.telegram.messenger.MessageObject m){}}''',
}

def run(*args): subprocess.run([str(a) for a in args],check=True,cwd=ROOT)
BUILD.mkdir(parents=True,exist_ok=True)
sources=[]
for name, source in STUBS.items():
 path=BUILD/'stubs'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8');sources.append(path)
classes=BUILD/'host-classes'
run(JDK/'javac.exe','--release','17','-classpath',ANDROID,'-d',classes,*sources,ROOT/'overlay/java/org/telegram/messenger/family/FamilyVoiceTranscription.java',QA/'VoiceStateTest.java')
run(JDK/'java.exe','-cp',str(classes)+';'+str(ANDROID),'org.telegram.messenger.family.VoiceStateTest')
spec=importlib.util.spec_from_file_location('voice_patch',ROOT/'build-support/patch_voice_transcription.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
decoded=ROOT/'build-support/work-preview7/decoded'
changes=module.prepare(decoded)
for file,patched in changes:
 original=file.read_text(encoding='utf-8');name=file.stem
 assert len(list(module.METHOD.finditer(patched)))==len(list(module.METHOD.finditer(original)))+len(module.PINS[name])
 for signature in module.PINS[name]:
  wrapper=next(m.group() for m in module.METHOD.finditer(patched) if m.group().splitlines()[0].endswith(' '+signature))
  if name=='TranscribeButton':assert 'familyVoiceOriginal' not in wrapper and 'sendRequest' not in wrapper
  else:assert 'familyVoiceOriginal' in wrapper
 try:module.patch(patched,name)
 except ValueError:pass
 else:raise AssertionError('Repeated patch accepted')
 first=next(iter(module.PINS[name]));bad=original.replace(' '+first,' '+first+' ',1)
 try:module.patch(bad,name)
 except ValueError:pass
 else:raise AssertionError('Changed pin accepted')
 assert file.read_text(encoding='utf-8')==original
 out=BUILD/'smali'/file.name;out.parent.mkdir(exist_ok=True);out.write_text(patched,encoding='utf-8')
print('PASS six pinned wrappers, Python compatibility, paid/cloud entrypoints have no fallback; staging unchanged')
