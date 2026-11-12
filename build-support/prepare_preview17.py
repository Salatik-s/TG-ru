"""Stage the Android 10018 changes against the pinned 10017 decoder output."""
import json
import re
import shutil
import xml.etree.ElementTree as ET

from prepare_preview15 import method
from prepare_preview16 import FAMILY, replace_once
from prepare_repack import A, FAMILY_PACKAGE, ROOT, find_smali, within_work


def change_method(source, signature, transform):
    entry = method(source, signature)
    return source[:entry.start()] + transform(entry.group(0)) + source[entry.end():]


def cached_start(source):
    return replace_once(source, FAMILY + 'FamilyAccess;->isAllowed()Z', FAMILY + 'FamilyBinaryGuard;->canShowChats()Z')


def launcher(source):
    for signature in ('onCreate(Landroid/os/Bundle;)V', 'onResume()V'):
        source = change_method(source, signature, cached_start)
    def title(method_source):
        point = '    iput p1, p0, Lorg/telegram/ui/LaunchActivity;->currentConnectionState:I'
        return replace_once(method_source, point, point + f'\n\n    invoke-static {{p1}}, {FAMILY}FamilyConnectionMonitor;->displayState(I)I\n    move-result p1')
    return change_method(source, 'updateCurrentConnectionState(I)V', title)


def upstream_counters(source, count):
    pattern = (r'    sget-object (v\d+), Lxyz/nextalone/nagram/NaConfig;->INSTANCE:Lxyz/nextalone/nagram/NaConfig;\n\n'
               r'    invoke-virtual \{\1\}, Lxyz/nextalone/nagram/NaConfig;->getIgnoreUnreadCount\(\)Ltw/nekomimi/nekogram/config/ConfigItem;\n\n'
               r'    move-result-object \1\n\n'
               r'    invoke-virtual \{\1\}, Ltw/nekomimi/nekogram/config/ConfigItem;->Int\(\)I\n\n'
               r'    move-result \1')
    result, matches = re.subn(pattern, r'    const/4 \1, 0x0', source)
    if matches != count:
        raise ValueError(f'Expected {count} inherited unread counter overrides, got {matches}')
    return result


def main():
    work = within_work(ROOT / 'work-upstream-1321')
    decoded = work / 'decoded'
    report_path = work / 'report.json'
    report = json.loads(report_path.read_text('utf-8'))
    if report.get('version_code') != 10017:
        raise ValueError('Expected verified TG-ru 10017 staging')
    changes = {}
    def patch(name, transform):
        path = find_smali(decoded, name)
        changes[path] = transform(path.read_text('utf-8'))

    patch('org.telegram.ui.LaunchActivity', launcher)
    patch('org.telegram.messenger.ApplicationLoader',
          lambda s: change_method(s, 'postInitApplication()V', cached_start))
    patch('org.telegram.ui.DialogsActivity$10', lambda s: upstream_counters(s, 1))
    patch('org.telegram.messenger.MessagesStorage', lambda s: upstream_counters(s, 2))

    version_path = decoded / 'apktool.yml'
    version = replace_once(version_path.read_text('utf-8'), 'versionCode: 10017', 'versionCode: 10018')
    changes[version_path] = replace_once(version, 'versionName: 0.1.0-preview16', 'versionName: 0.1.0-preview17')
    manifest_path = decoded / 'AndroidManifest.xml'
    manifest = ET.parse(manifest_path)
    root = manifest.getroot()
    if root.get('package') != FAMILY_PACKAGE or root.get(A + 'versionCode') != '10017':
        raise ValueError('Unexpected application identity')
    root.set(A + 'versionCode', '10018')
    root.set(A + 'versionName', '0.1.0-preview17')

    abi_list = ('arm64-v8a', 'x86_64')
    native = ROOT / 'native-calls-13.2.1' / 'out'
    for abi in abi_list:
        if not (native / abi / 'libfamilycalls.so').is_file():
            raise ValueError(f'Missing built call adapter for {abi}')

    for path, data in changes.items():
        path.write_text(data, 'utf-8', newline='\n')
    for abi in abi_list:
        shutil.copyfile(native / abi / 'libfamilycalls.so', decoded / 'lib' / abi / 'libfamilycalls.so')
    manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)
    report.update(version_code=10018, version_name='0.1.0-preview17')
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), 'utf-8')
    print('Prepared TG-ru 10018')


if __name__ == '__main__':
    main()
