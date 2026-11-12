"""Add the connection consent strings to a source-free decode of preview17."""

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from prepare_repack import A, FAMILY_PACKAGE, ROOT, digest, within_work


PREVIEW17 = 'fe8f6e173b672a178ff44f582d6017d91a77256526afddb92f1acaac67a1daa5'
RESOURCE_REF = re.compile(r'(?<![\w.])R\.(\w+)\.(\w+)')


def generate_r(resources, work):
    public = {(node.get('type'), node.get('name').replace('.', '_')): node.get('id')
              for node in resources.getroot()}
    overlay = ROOT.parent / 'overlay/java/org/telegram/messenger/family'
    sources = list(overlay.glob('*.java')) + list(ROOT.glob('Family*.java'))
    refs = {ref for path in sources for ref in RESOURCE_REF.findall(path.read_text('utf-8'))}
    missing = refs - public.keys()
    if missing:
        raise ValueError(f'Resource identifiers missing from the pinned base: {sorted(missing)}')

    lines = ['package org.telegram.messenger;', 'public final class R {']
    for resource_type in sorted({kind for kind, _ in refs}):
        lines.append(f'    public static final class {resource_type} {{')
        for kind, name in sorted(refs):
            if kind == resource_type:
                lines.append(f'        public static final int {name} = {public[kind, name]};')
        lines.append('    }')
    lines.append('}')
    generated = work / 'generated'
    generated.mkdir(exist_ok=True)
    (generated / 'R.java').write_text('\n'.join(lines) + '\n', 'utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    work = within_work(args.work)
    decoded = work / 'decoded'
    if digest(args.base) != PREVIEW17 or not (decoded / 'classes.dex').is_file():
        raise ValueError('Expected the verified preview17 APK and an Apktool --no-src decode')

    metadata_path = decoded / 'apktool.yml'
    metadata = metadata_path.read_text('utf-8')
    if metadata.count('versionCode: 10018') != 1 or metadata.count('versionName: 0.1.0-preview17') != 1:
        raise ValueError('Unexpected version in decoded APK metadata')
    manifest_path = decoded / 'AndroidManifest.xml'
    manifest = ET.parse(manifest_path)
    if manifest.getroot().get('package') != FAMILY_PACKAGE:
        raise ValueError('Unexpected package in decoded APK')

    values = decoded / 'res/values'
    public_path = values / 'public.xml'
    public = ET.parse(public_path)
    existing = {node.get('name'): int(node.get('id'), 16)
                for node in public.getroot() if node.get('type') == 'string'}
    source = ET.parse(ROOT.parent / 'overlay/res/values/fg_live_strings.xml')
    strings = [node for node in source.getroot() if node.get('name', '').startswith('fg_route_')]
    target = values / 'fg_route_update.xml'
    if not strings or target.exists() or any(node.get('name') in existing for node in strings):
        raise ValueError('Missing or already allocated route resources')

    added = ET.Element('resources')
    for offset, node in enumerate(strings, 1):
        resource_id = max(existing.values()) + offset
        if resource_id & 0xffff == 0:
            raise ValueError('String resource space exhausted')
        ET.SubElement(public.getroot(), 'public', type='string', name=node.get('name'), id=f'0x{resource_id:08x}')
        added.append(node)

    generate_r(public, work)
    public.write(public_path, 'utf-8', xml_declaration=True)
    ET.ElementTree(added).write(target, 'utf-8', xml_declaration=True)
    metadata_path.write_text(metadata.replace('versionCode: 10018', 'versionCode: 10019')
                             .replace('versionName: 0.1.0-preview17', 'versionName: 0.1.0-preview18'), 'utf-8')
    manifest.getroot().set(A + 'versionCode', '10019')
    manifest.getroot().set(A + 'versionName', '0.1.0-preview18')
    manifest.write(manifest_path, 'utf-8', xml_declaration=True)
    (work / 'report.json').write_text(json.dumps({'base_sha256': PREVIEW17, 'version_code': 10019}, indent=2), 'utf-8')
    print(f'Allocated {len(strings)} route resources for TG-ru 10019')


if __name__ == '__main__':
    main()
