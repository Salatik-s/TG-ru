"""Build an unsigned, explicitly unverified contingency APK from prepared inputs.

No downloads, test keys, placeholder native libraries, signing, installation or deployment.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from prepare_repack import ROOT, EXPECTED, within_work, digest
from verify_dex import verify_unique
from dex_marker import share_marker


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def check_elf(path, abi):
    header = path.read_bytes()[:64]
    machine = {'arm64-v8a': 183, 'x86_64': 62}.get(abi)
    if machine is None or len(header) < 64 or header[:6] != b'\x7fELF\x02\x01':
        raise ValueError(f'{path.name}: expected a 64-bit little-endian ELF for {abi}')
    if int.from_bytes(header[18:20], 'little') != machine:
        raise ValueError(f'{path.name}: ELF architecture mismatch for {abi}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--apktool', type=Path, required=True)
    parser.add_argument('--java-bin', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--native-dir', type=Path, help='Contains ABI/libfamilytunnel.so and ABI/libfamilytgnet.so')
    parser.add_argument('--abi', action='append', choices=['arm64-v8a', 'x86_64'])
    parser.add_argument('--compile-only', action='store_true', help='Validate all current overlay Java and D8 without constructing an APK')
    parser.add_argument('--ascii-work', type=Path, help='Existing ASCII subst/junction alias of --work for Windows native AAPT2')
    parser.add_argument('--retry', action='store_true', help='Replace only this staging directory\'s compiler outputs after a failed build')
    parser.add_argument('--overlay', type=Path, default=ROOT.parent / 'overlay')
    parser.add_argument('--extra-jars', type=Path, help='Verified document-parser dependency directory')
    args = parser.parse_args()
    work = within_work(args.work)
    tool_work = args.ascii_work.absolute() if args.ascii_work else work
    if args.ascii_work and (not str(tool_work).isascii() or not os.path.samefile(tool_work, work)):
        raise ValueError('--ascii-work must be an ASCII alias of the exact validated work directory')
    decoded = work / 'decoded'
    report = json.loads((work / 'report.json').read_text(encoding='utf-8'))
    if report.get('upstream_sha256') != EXPECTED or not report.get('hooks'):
        raise ValueError('Missing pinned preparation report')
    native_java = args.overlay / 'java/org/telegram/messenger/family/FamilyNative.java'
    source = native_java.read_text(encoding='utf-8')
    if not re.search(r'\bnative\s+int\s+nativeStart\s*\(', source):
        raise ValueError('Real native bridge declarations are required; no stand-in is allowed')
    load_sources = '\n'.join(p.read_text(encoding='utf-8') for p in native_java.parent.glob('*.java'))
    required_native = sorted({'libfamilytunnel.so', 'libfamilytgnet.so'} | {
        'lib' + name + '.so' for name in re.findall(r'System\.loadLibrary\("(family[a-z0-9_]+)"\)', load_sources)
    })
    if not args.compile_only and (not args.native_dir or not args.abi):
        parser.error('--native-dir and --abi are required for an APK build')
    if not args.compile_only:
        notices = {
            'THIRD_PARTY_NOTICES.txt': ROOT.parent / 'tunnel/THIRD_PARTY_NOTICES.txt',
            'UPSTREAM_LICENSE': ROOT.parent / 'client/LICENSE',
            'CALLS_THIRD_PARTY_NOTICES.txt': ROOT.parent / 'calls/THIRD_PARTY_NOTICES.txt',
        }
        notice_dir = decoded / 'assets/familygram'
        notice_dir.mkdir(parents=True, exist_ok=True)
        for name, path in notices.items():
            if not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f'Missing packaged license: {path.name}')
            shutil.copy2(path, notice_dir / name)
        call_licenses = ROOT.parent / 'calls/licenses'
        if not call_licenses.is_dir():
            raise ValueError('Missing native call transport licenses')
        for path in call_licenses.rglob('*'):
            if path.is_file():
                target = notice_dir / 'licenses' / path.relative_to(call_licenses)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
    for abi in ([] if args.compile_only else args.abi):
        if not (decoded / 'lib' / abi).is_dir():
            raise ValueError(f'Upstream APK has no {abi} libraries')
        for name in required_native:
            path = args.native_dir / abi / name
            check_elf(path, abi)
    # ABI pruning is explicit and confined to the decoded staging copy.
    for folder in ([] if args.compile_only else (decoded / 'lib').iterdir()):
        if folder.is_dir() and folder.name not in args.abi:
            checked = within_work(folder)
            if checked.parent != (decoded / 'lib').resolve():
                raise ValueError('Unexpected native staging path')
            shutil.rmtree(checked)
    for abi in ([] if args.compile_only else args.abi):
        for name in required_native:
            shutil.copy2(args.native_dir / abi / name, decoded / 'lib' / abi / name)
    suffix = '-check' if args.compile_only else ''
    classes, dex = work / ('classes' + suffix), work / ('overlay-dex' + suffix)
    if args.retry:
        for folder in (classes, dex):
            if folder.exists():
                checked = within_work(folder)
                if checked.parent != work:
                    raise ValueError('Compiler output resolves outside the expected staging directory')
                shutil.rmtree(checked)
    if classes.exists() or dex.exists():
        raise ValueError('Compile output already exists; use a fresh prepared staging directory')
    classes.mkdir()
    dex.mkdir()
    sources = sorted((args.overlay / 'java/org/telegram/messenger/family').glob('*.java'))
    sources += [work / 'generated/R.java', ROOT / 'FamilyBinaryGuard.java', ROOT / 'FamilyBinarySettings.java', ROOT / 'FamilyTelegramPreset.java']
    source_hashes = {str(path.resolve()): digest(path) for path in sources}
    run(args.java_bin / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-classpath', args.android_jar,
        '-d', classes, *sources)
    if source_hashes != {str(path.resolve()): digest(path) for path in sources}:
        raise ValueError('Java input changed during compilation; rebuild from a stable source snapshot')
    # R fields are compile-time constants. Including R.class here would duplicate upstream R.
    module = work / ('family-overlay' + suffix + '.jar')
    with zipfile.ZipFile(module, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in (classes / 'org/telegram/messenger/family').rglob('*.class'):
            archive.write(file, file.relative_to(classes).as_posix())
    run(args.java_bin / 'java.exe', '-cp', args.build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '24', '--lib', args.android_jar, '--output', dex, module,
        *([] if args.extra_jars is None else sorted(args.extra_jars.glob('*.jar'))))
    if args.compile_only:
        print(f'Compiled {len(sources)} Java sources and D8; no APK constructed: {dex}')
        return
    rebuilt = work / 'rebuilt-unsigned.apk'
    # A failed parallel Apktool run may leave a partially written cached DEX.
    # Always rebuild every class set before the independent DEX validation below.
    run(args.java_bin / 'java.exe', '-jar', args.apktool, 'b', '-f', tool_work / 'decoded', '-o', tool_work / rebuilt.name)
    # Verify every pinned public ID, not just the added family resources.
    dump = subprocess.check_output([str(args.build_tools / 'aapt2.exe'), 'dump', 'resources', str(tool_work / rebuilt.name)], encoding='utf-8')
    actual_ids = {name: value.lower() for value, name in re.findall(r'\bresource (0x[0-9a-fA-F]+) (?:[^\s:]+:)?([^\s]+)', dump)}
    public_ids = {node.get('type') + '/' + node.get('name'): node.get('id').lower()
                  for node in ET.parse(decoded / 'res/values/public.xml').getroot()}
    for name, value in public_ids.items():
        if actual_ids.get(name) != value:
            raise ValueError(f'Rebuilt resource ID could not be verified: {name} = {value}')
    report['verified_public_resource_ids'] = len(public_ids)
    if args.extra_jars is not None:
        report['private_record_marker_relocations']=sum(share_marker(item,rebuilt) for item in dex.glob('*.dex'))
    with zipfile.ZipFile(rebuilt, 'a', zipfile.ZIP_DEFLATED) as archive:
        names = archive.namelist()
        highest = max([1 if name == 'classes.dex' else int(re.fullmatch(r'classes(\d+)\.dex', name).group(1))
                       for name in names if re.fullmatch(r'classes(?:\d+)?\.dex', name)])
        for index, file in enumerate(sorted(dex.glob('classes*.dex')), highest + 1):
            target = f'classes{index}.dex'
            if target in names:
                raise ValueError('DEX append would collide')
            archive.write(file, target)
    report['dex_validation'] = verify_unique(rebuilt)
    aligned = work / 'FamilyGram-contingency-UNSIGNED-UNVERIFIED.apk'
    run(args.build_tools / 'zipalign.exe', '-f', '-P', '16', '4', tool_work / rebuilt.name, tool_work / aligned.name)
    run(args.build_tools / 'zipalign.exe', '-c', '-P', '16', '4', tool_work / aligned.name)
    report['status'] = 'unsigned build only; not signed, installed or runtime-verified'
    report['abis'] = args.abi
    report['compiled_source_sha256'] = source_hashes
    report['native_sha256'] = {f'{abi}/{name}': digest(decoded / 'lib' / abi / name)
                               for abi in args.abi for name in required_native}
    report['unsigned_apk'] = str(aligned)
    report['unsigned_sha256'] = digest(aligned)
    (work / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print(aligned)


if __name__ == '__main__':
    main()
