"""Port TG-ru integration from preview14 to the pinned exteraless 13.2.1 CI build."""
from copy import deepcopy
import json
import re
import shutil
import xml.etree.ElementTree as ET

import patch_media_1321
import patch_mentions_1321
from patch_mentions import deduplicate_results
from prepare_preview14 import FAMILY, append_resume_hook
from prepare_repack import A, FAMILY_PACKAGE, METHOD, ROOT, digest, find_smali, within_work

PIN = '1da4352d18d4bb7f622169be45eded0d47fffa925dc346b2fcfcd282ff15010a'


def method(source, signature):
    matches = [entry for entry in METHOD.finditer(source) if entry.group('head').split()[-1] == signature]
    if len(matches) != 1:
        raise ValueError('Expected exactly one method: ' + signature)
    return matches[0]


def reuse_wrapper(previous, current, signature, alias):
    original = method(current, signature)
    wrapper = method(previous, signature)
    method_name = signature.split('(')[0]
    if f'->{alias}(' not in wrapper.group(0) or alias in current:
        raise ValueError('Unexpected wrapper input for ' + signature)
    saved = original.group(0).replace(' ' + method_name + '(', ' ' + alias + '(', 1)
    return current[:original.start()] + wrapper.group(0) + '\n' + saved + current[original.end():]


def add_chat_hooks(source):
    title = r'invoke-static \{v[05]\}, Ltw/nekomimi/nekogram/helpers/TypefaceHelper;->getTitleText\(I\)Landroid/text/SpannableStringBuilder;'
    source, count = re.subn(title,
        f'invoke-static {{}}, {FAMILY}FamilyFeatures;->visibilityTitle()Landroid/text/SpannableStringBuilder;', source)
    if count != 2:
        raise ValueError('Missing chat title call sites')
    # The mention selector wraps onResume. Insert before wrapping the original lifecycle.
    target = 'familyMentionsOriginalOnResume()V'
    original = method(source, target)
    body = original.group(0)
    anchor = '    invoke-super {p0}, Lorg/telegram/ui/ActionBar/BaseFragment;->onResume()V'
    if body.count(anchor) != 1:
        raise ValueError('Original dialog resume body changed')
    body = body.replace(anchor, anchor + f'''

    invoke-virtual {{p0}}, Lorg/telegram/ui/DialogsActivity;->updateTitleText()V
    iget-boolean v0, p0, Lorg/telegram/ui/DialogsActivity;->onlySelect:Z
    if-nez v0, :tg_ru_dialogs_resume
    invoke-static {{p0}}, {FAMILY}FamilyFeatures;->onChatsResumed(Ljava/lang/Object;)V
    :tg_ru_dialogs_resume''')
    source = source[:original.start()] + body + source[original.end():]
    menu = method(source, 'showItemOptions()V')
    body = menu.group(0)
    palette = '    invoke-virtual {v0, v3, v2}, Lorg/telegram/ui/Components/ItemOptions;->setColors(II)Lorg/telegram/ui/Components/ItemOptions;'
    legacy = '''    sget-object v1, Ltw/nekomimi/nekogram/NekoConfig;->showGhostInDrawer:Ltw/nekomimi/nekogram/config/ConfigItem;

    invoke-virtual {v1}, Ltw/nekomimi/nekogram/config/ConfigItem;->Bool()Z

    move-result v1'''
    if body.count(palette) != 1 or body.count(legacy) != 1:
        raise ValueError('Chat overflow anchors changed')
    body = body.replace(palette, palette + f'\n\n    invoke-static {{v0, p0}}, {FAMILY}FamilyNavigation;->addGhostMenu(Ljava/lang/Object;Ljava/lang/Object;)V')
    return source[:menu.start()] + body.replace(legacy, '    const/4 v1, 0x0') + source[menu.end():]


def patch_app_loader(source):
    original = method(source, 'onCreate()V')
    pattern = r'(^    \.locals [1-9][0-9]*\n)'
    guard = f'''
    invoke-static {{}}, {FAMILY}FamilyDocumentService;->isIsolatedDocumentProcess()Z
    move-result v0
    if-eqz v0, :saa_normal_application
    invoke-super {{p0}}, Landroid/app/Application;->onCreate()V
    return-void
  :saa_normal_application
'''
    body, count = re.subn(pattern, lambda m: m[0] + guard, original.group(0), flags=re.M)
    if count != 1:
        raise ValueError('Document process entrypoint changed')
    return source[:original.start()] + body + source[original.end():]


def patch_photo_references(source):
    count = 0
    def update(method_match):
        nonlocal count
        if not method_match.group('head').startswith('private updateMediaPaths('):
            return method_match.group(0)
        def add(match):
            nonlocal count
            count += 1
            return (f"    invoke-static {{{match['dst']}, {match['src']}}}, "
                    f"{FAMILY}FamilyChatTools;->copyPhotoReference(Ljava/lang/Object;Ljava/lang/Object;)V\n\n" + match[0])
        return re.sub(r'    iget-wide (?P<temp>v\d+), (?P<src>v\d+), Lorg/telegram/tgnet/TLRPC\$Photo;->access_hash:J\s+iput-wide (?P=temp), (?P<dst>v\d+), Lorg/telegram/tgnet/TLRPC\$Photo;->access_hash:J', add, method_match.group(0))
    result = METHOD.sub(update, source)
    if count != 3:
        raise ValueError('Expected three photo reference sites, found ' + str(count))
    return result


def main():
    work = within_work(ROOT / 'work-upstream-1321')
    decoded = work / 'decoded'
    previous = within_work(ROOT / 'work-preview14') / 'decoded'
    report_path = work / 'report.json'
    report = json.loads(report_path.read_text('utf-8'))
    if report.get('upstream_sha256') != PIN or report.get('version_code') is not None or not report.get('hooks'):
        raise ValueError('Expected the pinned, prepared 13.2.1 baseline')

    changes = patch_mentions_1321.patched_sources(decoded)
    changes.update(dict(patch_media_1321.prepare(decoded)))
    def patch(name, transform):
        path = find_smali(decoded, name)
        changes[path] = transform(changes.get(path, path.read_text('utf-8')))

    def wrap(name, signature, alias):
        prior = find_smali(previous, name).read_text('utf-8')
        patch(name, lambda source: reuse_wrapper(prior, source, signature, alias))

    patch('org.telegram.ui.Adapters.MentionsAdapter', deduplicate_results)
    patch('org.telegram.ui.DialogsActivity', add_chat_hooks)
    patch('org.telegram.ui.SettingsActivity', lambda text: append_resume_hook(text,
        f'    invoke-static {{p0}}, {FAMILY}FamilyBinarySettings;->onSettingsResumed(Ljava/lang/Object;)V'))
    patch('org.telegram.messenger.ApplicationLoader', patch_app_loader)
    patch('org.telegram.messenger.SendMessagesHelper', patch_photo_references)
    patch('org.telegram.messenger.LocaleController',
          lambda text: text.replace('const-string v0, "FamilyGram"', 'const-string v0, "TG-ru"', 1))
    wrap('org.telegram.ui.ChatActivity',
         'fillMessageMenu(Lorg/telegram/messenger/MessageObject;Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;)V',
         'familyOriginalFillMessageMenu')
    wrap('org.telegram.ui.ChatActivity', 'processSelectedOption(I)V', 'familyOriginalProcessSelectedOption')
    wrap('org.telegram.messenger.AndroidUtilities',
         'openForView(Ljava/io/File;Ljava/lang/String;Ljava/lang/String;Landroid/app/Activity;Lorg/telegram/ui/ActionBar/Theme$ResourcesProvider;Z)Z',
         'familyOriginalOpenForView')
    wrap('app.exteraless.drawer.MainMenuHelper',
         'resolveMenuItem(ILapp/exteraless/drawer/MainMenuHelper$MenuContext;)Lapp/exteraless/drawer/MainMenuHelper$MenuItemInfo;',
         'familyOriginalResolveMenuItem')

    manifest_path = decoded / 'AndroidManifest.xml'
    manifest = ET.parse(manifest_path)
    root = manifest.getroot()
    if root.get('package') != FAMILY_PACKAGE:
        raise ValueError('Unexpected package identity')
    app = root.find('application')
    app.set(A + 'label', 'TG-ru')
    for alias in app.findall('activity-alias'):
        alias.set(A + 'label', 'TG-ru')
    existing = {node.get(A + 'name') for node in app}
    for node in ET.parse(previous / 'AndroidManifest.xml').getroot().find('application'):
        name = node.get(A + 'name', '')
        if name.startswith('org.telegram.messenger.family.') and name not in existing:
            app.append(deepcopy(node))
    queries = root.find('queries')
    if queries is None:
        queries = ET.SubElement(root, 'queries')
    if not any(node.get(A + 'name') == 'android.speech.RecognitionService' for node in queries.findall('intent/action')):
        ET.SubElement(ET.SubElement(queries, 'intent'), 'action', {A + 'name': 'android.speech.RecognitionService'})
    root.set(A + 'versionCode', '10016')
    root.set(A + 'versionName', '0.1.0-preview15')
    version_path = decoded / 'apktool.yml'
    version = version_path.read_text('utf-8')
    if version.count('versionCode: 1261') != 1 or version.count('versionName: 13.2.1-979e175') != 1:
        raise ValueError('Unexpected CI APK version')
    native = ROOT / 'native-13.2.1'
    tgnet = {
        'arm64-v8a': '8291c474aec9dbf174265ebdb9618302c2fac981604d3b76ce47124ae1f5c47e',
        'x86_64': '82c5e8c30f40d25ab4d61e66d48f2c541a0d12b017934f2f032858c71b224782',
    }
    for abi, expected in tgnet.items():
        if digest(native / 'build' / abi / 'libfamilytgnet.so') != expected:
            raise ValueError('Mismatched new tgnet library ' + abi)

    for path, data in changes.items():
        path.write_text(data, 'utf-8', newline='\n')
    manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)
    version_path.write_text(version.replace('versionCode: 1261', 'versionCode: 10016')
        .replace('versionName: 13.2.1-979e175', 'versionName: 0.1.0-preview15'), 'utf-8')
    for path in (decoded / 'res').glob('values*/strings.xml'):
        text = path.read_text('utf-8')
        branded = re.sub(r'(<string name="(?:AppName|Page1Title|AppIconExteraless)"[^>]*>)[^<]*(</string>)',
                         r'\g<1>TG-ru\g<2>', text)
        if text != branded:
            path.write_text(branded, 'utf-8')
    shutil.copytree(previous / 'assets/familygram', decoded / 'assets/familygram', dirs_exist_ok=True)
    shutil.copytree(ROOT.parent / 'overlay/assets', decoded / 'assets', dirs_exist_ok=True)
    for abi in tgnet:
        lib = 'libfamilytunnel.so'
        shutil.copy2(previous / 'lib' / abi / lib, decoded / 'lib' / abi / lib)
        calls = ROOT / 'native-calls-13.2.1/out' / abi / 'libfamilycalls.so'
        expected_calls = {
            'arm64-v8a': 'ef3cff6c81d216e62813b0b943cc8bc5445438ebb716a464163cb66cb8051143',
            'x86_64': '144f82c18d6ec7e81c647c50a756cd130e607933d60d947acd4382487eaf09ba',
        }
        if digest(calls) != expected_calls[abi]:
            raise ValueError('Mismatched new calls adapter ' + abi)
        shutil.copy2(calls, decoded / 'lib' / abi / calls.name)
        shutil.copy2(native / 'build' / abi / 'libfamilytgnet.so', decoded / 'lib' / abi / 'libfamilytgnet.so')
    report.update(version_code=10016, version_name='0.1.0-preview15', telegram_base='13.2.1',
                  upstream_commit='979e175a29f666768ebd0f134e27630fc9b65d4b',
                  compatibility='Same package, signer, enrollment protocol and persisted Telegram account data')
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), 'utf-8')
    print('Prepared Telegram 13.2.1, both tgnet ABIs, and TG-ru overlay hooks')


if __name__ == '__main__':
    main()
