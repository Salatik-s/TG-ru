"""Clean up inherited Android UI on the verified TG-ru 10016 staging tree."""
import json
import re
import xml.etree.ElementTree as ET

from prepare_preview15 import method
from prepare_repack import A, FAMILY_PACKAGE, ROOT, find_smali, within_work

FAMILY = 'Lorg/telegram/messenger/family/'
FALSE = '    .locals 1\n    const/4 v0, 0x0\n    return v0'
NOOP = '    .locals 0\n    return-void'


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError('Unexpected pinned source: ' + old[:100])
    return source.replace(old, new, 1)


def replace_body(source, signature, body):
    entry = method(source, signature)
    return source[:entry.start()] + '.method ' + entry['head'] + '\n' + body + '\n.end method\n' + source[entry.end():]


def close_settings(source):
    signature = 'onFragmentCreate()Z'
    if re.search(r'^\.method .*\bonFragmentCreate\(\)Z$', source, re.M):
        return replace_body(source, signature, FALSE)
    return source + '\n.method public ' + signature + '\n' + FALSE + '\n.end method\n'


def dialogs(source):
    for register in ('v5', 'v0'):
        old = (f'    iget {register}, ' + ('v1' if register == 'v5' else 'p0')
               + ', Lorg/telegram/ui/ActionBar/BaseFragment;->currentAccount:I\n\n'
               + f'    invoke-static {{}}, {FAMILY}FamilyFeatures;->visibilityTitle()Landroid/text/SpannableStringBuilder;')
        new = old.split('\n\n')[0] + f'\n\n    invoke-static {{{register}}}, {FAMILY}FamilyChatHeader;->title(I)Landroid/text/SpannableStringBuilder;'
        source = replace_once(source, old, new)
    setter = '    invoke-virtual {v2, v5}, Lorg/telegram/ui/ActionBar/ActionBar;->setTitleActionRunnable(Ljava/lang/Runnable;)V'
    return replace_once(source, setter,
        f'    invoke-static {{v1, v5}}, {FAMILY}FamilyChatHeader;->titleAction(Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Runnable;\n'
        '    move-result-object v5\n\n' + setter)


def chat(source):
    start = source.index('    invoke-virtual {v3}, Lxyz/nextalone/nagram/NaConfig;->getChatMenuItemGoToMessage()')
    end = source.index('    :cond_52', start)
    removed = source[start:end]
    if '->ToTheMessage:I' not in removed or 'lazilyAddSubItem' not in removed or '0xc9' not in removed:
        raise ValueError('Unexpected jump-to-message menu')
    return replace_body(source[:start] + source[end:], 'setScrollToMessage()V', NOOP)


def cloud_menu(source):
    pattern = r'(?m)^    \.line (?:95|104)\n    sget v1, Lorg/telegram/messenger/R\$drawable;->cloud_sync:I\n(?:[^\n]*\n)*?    invoke-virtual \{v0, v3, v1, v2\}, Lorg/telegram/ui/ActionBar/ActionBarMenu;->addItem[^\n]*\n\n'
    result, count = re.subn(pattern, '', source)
    if count != 1:
        raise ValueError('Missing cloud action-bar item')
    return close_settings(result)


def inherited_drawer(source):
    pattern = (r'(?m)(^    :pswitch_[0-9a-f]+\n)    new-instance p0, Lapp/exteraless/drawer/MainMenuHelper\$MenuItemInfo;'
               r'\n\n    sget [^\n]+->exteraless_icon_tile:I\n(?:[^\n]*\n)*?    return-object p0')
    source, count = re.subn(pattern, r'\g<1>    const/4 v0, 0x0\n    return-object v0', source)
    if count != 2:
        raise ValueError('Missing inherited drawer settings items')
    return source


def branding(path, changes):
    text = path.read_text('utf-8')
    def replace(match):
        key, value = match[1], match[2]
        if key in {'google_storage_bucket', 'project_id'}:
            return match[0]
        if key == 'OEAyuDatabaseImportInfo':
            return match[0].replace('exteraless', 'TG-ru')
        value = re.sub(r'exteraGram', 'ETG', value, flags=re.I)
        value = re.sub(r'exteraless', 'TG-ru', value, flags=re.I)
        return match[0][:match.start(2)-match.start()] + value + '</string>'
    result = re.sub(r'<string name="([^"]+)"[^>]*>([^<]*)</string>', replace, text)
    if result != text:
        changes[path] = result


def main():
    work = within_work(ROOT / 'work-upstream-1321')
    decoded = work / 'decoded'
    report_path = work / 'report.json'
    report = json.loads(report_path.read_text('utf-8'))
    if report.get('version_code') != 10016:
        raise ValueError('Expected verified TG-ru 10016 staging')
    changes = {}
    def patch(name, transform):
        path = find_smali(decoded, name)
        changes[path] = transform(path.read_text('utf-8'))

    patch('org.telegram.ui.DialogsActivity', dialogs)
    patch('org.telegram.ui.ChatActivity', chat)
    patch('org.telegram.ui.LaunchActivity', lambda s: replace_once(s, '    invoke-static {v1}, Lapp/exteraless/updater/GitHubUpdater;->check(Z)V', '    # TG-ru checks releases through FamilyAppUpdate.'))
    patch('com.exteragram.messenger.utils.AppUtils', lambda s: replace_once(s, '    const-string v1, "\\nbased on @exteraless"', '    const-string v1, ""'))
    patch('tw.nekomimi.nekogram.helpers.TypefaceHelper', lambda s:
          replace_body(s, 'getTitleText(I)Landroid/text/SpannableStringBuilder;', f'''    .locals 0
    invoke-static {{p0}}, {FAMILY}FamilyChatHeader;->title(I)Landroid/text/SpannableStringBuilder;
    move-result-object p0
    return-object p0'''))
    patch('app.exteraless.appearance.AppearanceConfig', lambda s: replace_body(s, 'folderNameAsTitle()Z', FALSE))
    patch('app.exteraless.general.GeneralConfig', lambda s: replace_body(s, 'showNagramSettings()Z', FALSE))
    patch('app.exteraless.settings.OpenExteraSettingsActivity', cloud_menu)
    patch('tw.nekomimi.nekogram.settings.NekoSettingsActivity', cloud_menu)
    patch('app.exteraless.settings.OpenExteraCloudActivity', close_settings)
    patch('app.exteraless.drawer.MainMenuHelper', inherited_drawer)
    patch('tw.nekomimi.nekogram.helpers.SettingsHelper', lambda s: replace_body(s, 'isDeepLink(Ljava/lang/String;)Z', FALSE))
    patch('tw.nekomimi.nekogram.helpers.CloudSettingsHelper', lambda s:
          replace_body(replace_body(s, 'doAutoSync()V', NOOP), 'isAutoSync()Z', FALSE))
    for path in (decoded / 'res').glob('values*/strings.xml'):
        branding(path, changes)
    version_path = decoded / 'apktool.yml'
    version = replace_once(version_path.read_text('utf-8'), 'versionCode: 10016', 'versionCode: 10017')
    changes[version_path] = replace_once(version, 'versionName: 0.1.0-preview15', 'versionName: 0.1.0-preview16')
    manifest_path = decoded / 'AndroidManifest.xml'
    manifest = ET.parse(manifest_path)
    root = manifest.getroot()
    if root.get('package') != FAMILY_PACKAGE or root.get(A + 'versionCode') != '10016':
        raise ValueError('Unexpected application identity')
    root.set(A + 'versionCode', '10017')
    root.set(A + 'versionName', '0.1.0-preview16')
    for path, data in changes.items():
        path.write_text(data, 'utf-8', newline='\n')
    manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)
    report.update(version_code=10017, version_name='0.1.0-preview16', ui_cleanup='TG-ru chat header, retired upstream settings and settings cloud, removed jump by ID')
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), 'utf-8')
    print('Prepared TG-ru 10017 UI changes')


if __name__ == '__main__':
    main()
