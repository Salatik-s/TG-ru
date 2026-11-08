from pathlib import Path
import importlib.util,subprocess,tempfile
root=Path(__file__).resolve().parents[2]
qa=Path(__file__).resolve().parent
jdk=Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
android=Path('C:/Users/andre/AppData/Local/Android/Sdk/platforms/android-35/android.jar')
source=root/'overlay/java/org/telegram/messenger/family'
build=qa/'build';build.mkdir(exist_ok=True)
stubs=build/'stubs/org/telegram/messenger/family';stubs.mkdir(parents=True,exist_ok=True)
(stubs/'FamilyChatTools.java').write_text('package org.telegram.messenger.family; public final class FamilyChatTools {public static boolean enabled(android.content.Context c,String id){return true;}}')
(stubs/'FamilyAccess.java').write_text('package org.telegram.messenger.family; public final class FamilyAccess {public static boolean isAllowed(){return true;}}')
def run(*args):subprocess.run([str(a) for a in args],check=True)
run(jdk/'javac.exe','--release','17','-classpath',android,'-d',build,source/'FamilyPythonSnippets.java',source/'FamilyPythonRunner.java',source/'FamilyInlinePython.java',stubs/'FamilyChatTools.java',stubs/'FamilyAccess.java',qa/'SnippetTest.java')
run(jdk/'java.exe','-cp',build,'org.telegram.messenger.family.SnippetTest')
spec=importlib.util.spec_from_file_location('patch_inline_python',root/'build-support/patch_inline_python.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
cell=root/'build-support/work-preview7/decoded/smali_classes5/org/telegram/ui/Cells/ChatMessageCell.smali'
text=cell.read_text(encoding='utf-8');patched=module.patch(text)
assert len(list(module.METHOD.finditer(patched)))==len(list(module.METHOD.finditer(text)))+4
assert patched.count('->measure(Landroid/view/ViewGroup;)I')==1
assert patched.count('->beforeBind(Landroid/view/ViewGroup;Ljava/lang/Object;)V')==1
assert 'add-int/2addr v1, v0' in patched and 'additionalPaddingHeight:I' in patched
for invalid in (patched,text.replace('.line 14039','.line 14040',1)):
 try:module.patch(invalid)
 except ValueError:pass
 else:raise AssertionError('accepted an unpinned or already patched cell')
assert cell.read_text(encoding='utf-8')==text
(build/'ChatMessageCell.patched.smali').write_text(patched,encoding='utf-8')
print('PASS four pinned wrappers, no repeated-patch accumulation, wrong-pin rejection; staging unchanged')
