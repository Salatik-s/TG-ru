"""Preserve the verified preview5 staging copy and derive preview6 without UI edits."""
import json
import re
import shutil
from pathlib import Path
from prepare_repack import ROOT, digest, within_work

BASE_SHA = 'dfab4cf49465dd819f616f5be4ebe7233e0ee33e81bde9845c02db373cd8c13f'
base = within_work(ROOT / 'work-pinned-v1')
work = within_work(ROOT / 'work-preview6')
apk = ROOT.parent / 'release/FamilyGram-0.1.0-preview5.apk'
if digest(apk) != BASE_SHA:
    raise ValueError('The verified preview5 APK has changed')
report = json.loads((base / 'report.json').read_text(encoding='utf-8'))
if report.get('version_code') != 10005 or report.get('version_name') != '0.1.0-preview5':
    raise ValueError('Expected verified preview5 staging metadata')
if work.exists():
    raise ValueError('Refusing to replace existing preview6 staging')
work.mkdir()
def ignore_root_cache(directory, names):
    return {'build', 'dist'} & set(names) if Path(directory) == base / 'decoded' else set()

shutil.copytree(base / 'decoded', work / 'decoded', ignore=ignore_root_cache)
shutil.copytree(base / 'generated', work / 'generated')
version_file = work / 'decoded/apktool.yml'
version = version_file.read_text(encoding='utf-8')
version, code_count = re.subn(r'(?m)^(\s*versionCode:) 10005$', r'\g<1> 10006', version)
version, name_count = re.subn(r'(?m)^(\s*versionName:) 0\.1\.0-preview5$', r'\g<1> 0.1.0-preview6', version)
if code_count != 1 or name_count != 1:
    raise ValueError('Unexpected version metadata')
version_file.write_text(version, encoding='utf-8')
report['previous_preview5_native_sha256'] = report['native_sha256'].copy()
report['previous_preview5_compiled_source_sha256'] = report['compiled_source_sha256'].copy()
report['previous_preview5_signed_sha256'] = BASE_SHA
report['version_code'] = 10006
report['version_name'] = '0.1.0-preview6'
report['status'] = 'prepared from preview5; waiting for frozen Java and WSS native libraries'
report.pop('unsigned_apk', None)
report.pop('unsigned_sha256', None)
report['preview6_change_scope'] = 'FamilyAccess fallback selection and libfamilytunnel WSS; UI/resources/native tgnet/calls unchanged'
(work / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print('Prepared preview6 from verified preview5; original staging and signed APK preserved.')
