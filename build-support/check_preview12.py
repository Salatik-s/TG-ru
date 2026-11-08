"""Read-only comparison of the final client update with the delivered preview11."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
old = ROOT / 'release/TG-ru-preview11-test.apk'
new = ROOT / 'release/TG-ru-preview12-test.apk'
assert hashlib.sha256(old.read_bytes()).hexdigest() == '788ae2174ac88ffb433b4ca13e2eafba60f9b2dd303d6edcf66745bbb7bd4659', 'Pinned preview11 baseline required'
report = json.loads((ROOT / 'build-support/work-preview7/report.json').read_text('utf-8'))
assert report['version_code'] == 10012
assert report['version_name'] == '0.1.0-preview12'
result = {'version_code': 10012, 'version_name': '0.1.0-preview12',
          'apk_sha256': hashlib.sha256(new.read_bytes()).hexdigest(), 'apk_bytes': new.stat().st_size,
          'resource_ids': report['verified_public_resource_ids'], 'dex': report['dex_validation']}
with zipfile.ZipFile(old) as before, zipfile.ZipFile(new) as after:
    assert after.testzip() is None
    baseline = {name for name in before.namelist() if name.startswith(('assets/', 'lib/')) and not name.endswith('/')}
    current = {name for name in after.namelist() if name.startswith(('assets/', 'lib/')) and not name.endswith('/')}
    assert baseline == current, 'Native/runtime file set changed'
    changed = [name for name in sorted(baseline) if hashlib.sha256(before.read(name)).digest() != hashlib.sha256(after.read(name)).digest()]
    assert not changed, 'Native/runtime payload changed: ' + ','.join(changed)
    for name, expected in report['preview11_native_sha256'].items():
        assert hashlib.sha256(after.read('lib/' + name)).hexdigest() == expected
    result['unchanged_native_and_runtime_files'] = len(baseline)
    result['preview11_native_hashes_preserved'] = True
    result['native_and_runtime_unchanged'] = True
    result['unchanged_upstream_dex_files'] = sum(before.read('classes' + (str(i) if i > 1 else '') + '.dex') == after.read('classes' + (str(i) if i > 1 else '') + '.dex') for i in range(1, 9))
assert result['unchanged_upstream_dex_files'] == 8, 'Unexpected upstream DEX change'
(ROOT / 'qa/saved-media/results/preview12-integrity.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result, separators=(',', ':')))
