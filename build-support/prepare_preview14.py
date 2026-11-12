"""Prepare TG-ru preview14 on a copy of the verified preview13 staging tree."""
import json
import re
import shutil
import xml.etree.ElementTree as ET

from patch_mentions import apply as apply_mentions, deduplicate_results
from patch_selector_mentions import selector_patch, sender_patch, share_sender_patch
from prepare_repack import A, FAMILY_PACKAGE, ROOT, find_smali, within_work

FAMILY = 'Lorg/telegram/messenger/family/'
FRAGMENT_RESUME = 'invoke-super {p0}, Lorg/telegram/ui/ActionBar/BaseFragment;->onResume()V'


def append_resume_hook(source, instructions):
    pattern = r'(?ms)(^\.method public onResume\(\)V\n.*?^    ' + re.escape(FRAGMENT_RESUME) + r')'
    source, count = re.subn(pattern, lambda m: m[1] + '\n\n' + instructions, source)
    if count != 1:
        raise ValueError('Expected one fragment lifecycle hook')
    return source


def main():
    work = within_work(ROOT / 'work-preview14')
    decoded = work / 'decoded'
    report_path = work / 'report.json'
    report = json.loads(report_path.read_text('utf-8'))
    if report.get('version_code') != 10013:
        raise ValueError('Expected the verified preview13 staging copy')
    manifest_path = decoded / 'AndroidManifest.xml'
    manifest = ET.parse(manifest_path)
    root = manifest.getroot()
    if root.get('package') != FAMILY_PACKAGE or root.get(A + 'versionCode') != '10013':
        raise ValueError('Unexpected preview13 manifest')
    version_path = decoded / 'apktool.yml'
    version = version_path.read_text('utf-8')
    if version.count('versionCode: 10013') != 1 or version.count('versionName: 0.1.0-preview13') != 1:
        raise ValueError('Unexpected preview13 packaging version')

    dialogs = find_smali(decoded, 'org.telegram.ui.DialogsActivity')
    source = dialogs.read_text('utf-8')
    source, count = re.subn(
        r'invoke-static \{v[05]\}, Ltw/nekomimi/nekogram/helpers/TypefaceHelper;->getTitleText\(I\)Landroid/text/SpannableStringBuilder;',
        f'invoke-static {{}}, {FAMILY}FamilyFeatures;->visibilityTitle()Landroid/text/SpannableStringBuilder;',
        source)
    if count != 2:
        raise ValueError('Expected both main chat title call sites')
    source = append_resume_hook(source,
        f'''    invoke-virtual {{p0}}, Lorg/telegram/ui/DialogsActivity;->updateTitleText()V
    iget-boolean v0, p0, Lorg/telegram/ui/DialogsActivity;->onlySelect:Z
    if-nez v0, :tg_ru_dialogs_resume
    invoke-static {{p0}}, {FAMILY}FamilyFeatures;->onChatsResumed(Ljava/lang/Object;)V
    :tg_ru_dialogs_resume''')
    settings = find_smali(decoded, 'org.telegram.ui.SettingsActivity')
    settings_source = append_resume_hook(settings.read_text('utf-8'),
        f'    invoke-static {{p0}}, {FAMILY}FamilyBinarySettings;->onSettingsResumed(Ljava/lang/Object;)V')

    sender = find_smali(decoded, 'org.telegram.ui.ChatActivity')
    sender_source = sender_patch(sender.read_text('utf-8'))
    source = selector_patch(source)
    changed = apply_mentions(decoded)
    mentions = find_smali(decoded, 'org.telegram.ui.Adapters.MentionsAdapter')
    mentions.write_text(deduplicate_results(mentions.read_text('utf-8')), 'utf-8', newline='\n')
    share = find_smali(decoded, 'org.telegram.ui.Components.ShareAlert')
    share.write_text(share_sender_patch(share.read_text('utf-8')), 'utf-8', newline='\n')
    sender.write_text(sender_source, 'utf-8', newline='\n')
    dialogs.write_text(source, 'utf-8', newline='\n')
    settings.write_text(settings_source, 'utf-8', newline='\n')
    for name in ('fg_strings.xml', 'fg_live_strings.xml'):
        shutil.copy2(ROOT.parent / 'overlay/res/values' / name, decoded / 'res/values' / name)
    root.set(A + 'versionCode', '10014')
    root.set(A + 'versionName', '0.1.0-preview14')
    manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)
    version_path.write_text(version.replace('versionCode: 10013', 'versionCode: 10014')
                            .replace('versionName: 0.1.0-preview13', 'versionName: 0.1.0-preview14'),
                            encoding='utf-8', newline='\n')
    report.update(version_code=10014, version_name='0.1.0-preview14',
                  status='prepared Android compatibility update',
                  mention_patch=[str(path).replace(str(work), '.') for path in changed])
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print('Prepared preview14 settings, stories, and mention hooks')


if __name__ == '__main__':
    main()
