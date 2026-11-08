"""Check the narrow preview5 -> preview6 artifact delta without modifying APKs."""
import hashlib
import json
import zipfile
from pathlib import Path
from prepare_repack import ROOT, digest

work = ROOT / 'work-preview6'
report_path = work / 'report.json'
report = json.loads(report_path.read_text(encoding='utf-8'))
baseline = ROOT.parent / 'release/FamilyGram-0.1.0-preview5.apk'
candidate = ROOT.parent / 'release/FamilyGram-0.1.0-preview6.apk'
if digest(baseline) != report['previous_preview5_signed_sha256']:
    raise ValueError('Preview5 baseline changed')
old_sources = {Path(path).name: value for path, value in report['previous_preview5_compiled_source_sha256'].items()}
new_sources = {Path(path).name: value for path, value in report['compiled_source_sha256'].items()}
if old_sources.keys() != new_sources.keys():
    raise ValueError('Unexpected Java source inventory change')
source_delta = sorted(name for name in old_sources if old_sources[name] != new_sources[name])
if source_delta != ['FamilyAccess.java']:
    raise ValueError('Unexpected compiled Java delta: ' + str(source_delta))
native_delta = sorted(name for name, value in report['native_sha256'].items()
                      if report['previous_preview5_native_sha256'].get(name) != value)
if native_delta != ['arm64-v8a/libfamilytunnel.so', 'x86_64/libfamilytunnel.so']:
    raise ValueError('Unexpected native delta: ' + str(native_delta))
with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(candidate) as after:
    if before.testzip() or after.testzip():
        raise ValueError('APK ZIP CRC failed')
    before_names, after_names = set(before.namelist()), set(after.namelist())
    if before_names != after_names:
        raise ValueError('APK entry inventory changed')
    delta = sorted(name for name in before_names if hashlib.sha256(before.read(name)).digest() != hashlib.sha256(after.read(name)).digest())
    allowed = {'AndroidManifest.xml', 'classes9.dex', 'META-INF/com/android/build/gradle/app-metadata.properties',
               'lib/arm64-v8a/libfamilytunnel.so', 'lib/x86_64/libfamilytunnel.so',
               'assets/familygram/THIRD_PARTY_NOTICES.txt'}
    unexpected = [name for name in delta if name not in allowed]
    if unexpected:
        raise ValueError('Unexpected packaged payload delta: ' + str(unexpected))
report['preview6_delta_verified'] = {
    'compiled_java_changed': source_delta,
    'native_changed': native_delta,
    'apk_entries_changed': delta,
    'all_upstream_dex_and_ui_resources_identical_to_preview5': True,
    'signed_sha256': digest(candidate),
    'signed_bytes': candidate.stat().st_size,
    'zip_crc': 'PASS',
}
report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(report['preview6_delta_verified'], indent=2))
