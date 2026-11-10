from pathlib import Path
import hashlib
import json
import os
import re

ROOT = Path(__file__).resolve().parent.parent
LOCK = json.loads((ROOT / 'upstream-lock.json').read_text(encoding='utf-8'))
# Public, restricted test pair documented by Telegram Desktop.
# https://github.com/telegramdesktop/tdesktop/blob/dev/docs/api_credentials.md
TEST_API = ('17349', '344583e45741c457fe1862106095a5eb')

def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

def owned():
    if (ROOT / '.tg-ru-ios-owned').read_text().strip() != 'tg-ru-ios-v1':
        raise ValueError('Workspace ownership marker mismatch')

def credentials(environment=None, compile_only=False, test_login=False):
    if compile_only and test_login:
        raise ValueError('Choose one test build mode')
    if compile_only:
        return '2147483647', '0' * 32
    if test_login:
        return TEST_API
    environment = os.environ if environment is None else environment
    api_id, api_hash = environment.get('TG_API_ID', ''), environment.get('TG_API_HASH', '')
    if not re.fullmatch(r'[1-9][0-9]{0,9}', api_id) or int(api_id) > 2147483647:
        raise ValueError('Own TG_API_ID is missing or invalid')
    if not re.fullmatch(r'[a-fA-F0-9]{32}', api_hash):
        raise ValueError('Own TG_API_HASH is missing or invalid')
    if api_hash == '0' * 32 or api_id == '2147483647':
        raise ValueError('Compile-only dummy API values are not accepted for own-api builds')
    if api_id == TEST_API[0]:
        raise ValueError('The public sample API ID is limited to test-login builds')
    return api_id, api_hash

def contained(base, relative):
    base = Path(base).resolve()
    if Path(relative).is_absolute() or '..' in Path(relative).parts:
        raise ValueError('Unsafe relative path')
    target = (base / relative).resolve()
    if target == base or base not in target.parents:
        raise ValueError('Path leaves expected directory')
    return target

def verify_sources(source):
    for relative, expected in LOCK['files'].items():
        if digest(contained(source, relative)) != expected:
            raise ValueError('Upstream source hash mismatch: ' + relative)

def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
