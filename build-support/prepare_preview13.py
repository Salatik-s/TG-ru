"""Prepare the transcription-provider repair on the isolated preview12 staging copy."""
import json
import xml.etree.ElementTree as ET

from patch_voice_transcription import upgrade
from prepare_repack import A, FAMILY_PACKAGE, ROOT, within_work


def main():
    work = within_work(ROOT / 'work-preview13')
    decoded = work / 'decoded'
    report_path = work / 'report.json'
    report = json.loads(report_path.read_text('utf-8'))
    if report['version_code'] != 10012:
        raise ValueError('Expected the verified preview12 staging copy')
    manifest_path = decoded / 'AndroidManifest.xml'
    manifest = ET.parse(manifest_path)
    root = manifest.getroot()
    if root.get('package') != FAMILY_PACKAGE or root.get(A + 'versionCode') != '10012':
        raise ValueError('Unexpected preview12 manifest')
    version_path = decoded / 'apktool.yml'
    version = version_path.read_text('utf-8')
    if version.count('versionCode: 10012') != 1 or version.count('versionName: 0.1.0-preview12') != 1:
        raise ValueError('Unexpected preview12 packaging version')
    changes = []
    for name, folder in [('ChatMessageCell', 'Cells'), ('TranscribeButton', 'Components')]:
        paths = list(decoded.glob(f'smali*/org/telegram/ui/{folder}/{name}.smali'))
        if len(paths) != 1:
            raise ValueError('Expected exactly one ' + name)
        changes.append((paths[0], upgrade(paths[0].read_text('utf-8'), name)))
    strings_path = decoded / 'res/values/fg_transcription.xml'
    strings = strings_path.read_text('utf-8')
    old = '<string name="fg_transcription_menu">Расшифровать на телефоне</string>'
    if strings.count(old) != 1:
        raise ValueError('Unexpected transcription menu label')
    changes.append((strings_path, strings.replace(old, '<string name="fg_transcription_menu">Расшифровать</string>')))
    changes.append((version_path, version.replace('versionCode: 10012', 'versionCode: 10013')
                    .replace('versionName: 0.1.0-preview12', 'versionName: 0.1.0-preview13')))
    for path, source in changes:
        path.write_text(source, encoding='utf-8', newline='\n')
    root.set(A + 'versionCode', '10013')
    root.set(A + 'versionName', '0.1.0-preview13')
    manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)
    report.update(version_code=10013, version_name='0.1.0-preview13',
                  transcription_change='Auto and Telegram Premium use native Telegram for a Premium account')
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print('Preview13 provider hooks and package version prepared')


if __name__ == '__main__':
    main()
