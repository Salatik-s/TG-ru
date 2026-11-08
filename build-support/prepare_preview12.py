"""Preview12: only the ongoing connection notification and release version.

Start from the pinned preview11 staging tree. No chat resources, preferences,
account data, native binaries, or other manifest declarations are reset.
"""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from prepare_repack import ROOT, within_work, A

PINS = {
    'apktool.yml': 'bc6a4defa326abcf59e145ce4694663d244cc6f2da1326dfec9941c661c2880a',
    'AndroidManifest.xml': '9c368a76bf01a05a383b848c637daffccb782b85a4871f99ac100a6e150b2a88',
}

def main():
    work = within_work(ROOT / 'work-preview7')
    decoded = work / 'decoded'
    for name, digest in PINS.items():
        if hashlib.sha256((decoded / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Preview11 staging precondition failed: ' + name)
    report = json.loads((work / 'report.json').read_text('utf-8'))
    if report.get('version_code') != 10011:
        raise ValueError('Expected preview11 report')
    for name, digest in report['native_sha256'].items():
        if hashlib.sha256((decoded / 'lib' / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Native staging changed')
    strings = ROOT.parent / 'overlay/res/values/fg_push_strings.xml'
    values = ET.parse(strings).getroot()
    expected = {'fg_push_title': 'TG-ru', 'fg_push_connected': 'Соединено с Telegram', 'fg_push_connecting': 'Подключение к Telegram…'}
    if {n.get('name'): n.text for n in values} != expected:
        raise ValueError('Unexpected notification strings')
    public = decoded / 'res/values/public.xml'
    tree = ET.parse(public)
    ids = {(n.get('type'), n.get('name')): int(n.get('id'), 16) for n in tree.getroot()}
    for name in expected:
        if ('string', name) in ids:
            raise ValueError('Notification resource already exists')
        value = max(v for (kind, _), v in ids.items() if kind == 'string') + 1
        ids['string', name] = value
        ET.SubElement(tree.getroot(), 'public', {'type': 'string', 'name': name, 'id': f'0x{value:08x}'})
        report['resource_ids']['string/' + name] = f'0x{value:08x}'
    manifest = ET.parse(decoded / 'AndroidManifest.xml')
    if manifest.getroot().get('package') != 'com.salatik.familygram':
        raise ValueError('Unexpected app identity')
    manifest.getroot().set(A + 'versionCode', '10012')
    manifest.getroot().set(A + 'versionName', '0.1.0-preview12')
    version = (decoded / 'apktool.yml').read_text('utf-8')
    if version.count('versionCode: 10011') != 1 or version.count('versionName: 0.1.0-preview11') != 1:
        raise ValueError('Unexpected version anchors')
    version = version.replace('versionCode: 10011', 'versionCode: 10012').replace('versionName: 0.1.0-preview11', 'versionName: 0.1.0-preview12')
    lines = ['package org.telegram.messenger;', 'public final class R {']
    for kind in sorted({key.split('/')[0] for key in report['resource_ids']}):
        lines.append('public static final class ' + kind + ' {')
        for key, value in sorted(report['resource_ids'].items()):
            if key.startswith(kind + '/'):
                lines.append('public static final int ' + key.split('/')[1].replace('.', '_') + ' = ' + value + ';')
        lines.append('}')
    lines.append('}')
    # All identity, native and version checks above precede the first write.
    tree.write(public, encoding='utf-8', xml_declaration=True)
    (decoded / 'res/values/fg_push_strings.xml').write_bytes(strings.read_bytes())
    manifest.write(decoded / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    (decoded / 'apktool.yml').write_text(version, encoding='utf-8')
    (work / 'generated/R.java').write_text('\n'.join(lines), encoding='utf-8')
    report.update(version_code=10012, version_name='0.1.0-preview12', notification_change='TG-ru foreground status reflects selected Telegram account connection; existing channel/user settings preserved', preview11_native_sha256=dict(report['native_sha256']))
    (work / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print('Preview12 notification resources and version prepared; native hashes unchanged.')

if __name__ == '__main__': main()
