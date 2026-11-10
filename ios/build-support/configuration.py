"""Emit only the build variables required by the pinned native Telegram target."""
import os
from common import credentials

def emit(source, bazel, environment=None, compile_only=False, test_login=False):
    api_id, api_hash = credentials(environment, compile_only=compile_only, test_login=test_login)
    folder = source / 'build-input/configuration-repository'
    folder.mkdir(parents=True, exist_ok=True)
    os.chmod(folder, 0o700)
    (folder / 'provisioning').mkdir(exist_ok=True)
    for name, text in {'WORKSPACE': '', 'BUILD': '',
                       'MODULE.bazel': 'module(name = "build_configuration")\n',
                       'provisioning/BUILD': 'exports_files([])\n'}.items():
        (folder / name).write_text(text)
    values = {
        'telegram_bazel_path': str(bazel), 'telegram_use_xcode_managed_codesigning': False,
        'telegram_bundle_id': 'com.salatik.tgru', 'telegram_api_id': api_id,
        'telegram_api_hash': api_hash, 'telegram_team_id': '0000000000',
        'telegram_is_internal_build': 'false', 'telegram_is_appstore_build': 'false',
        'telegram_appstore_id': '0', 'telegram_app_specific_url_scheme': 'tgru',
        'telegram_premium_iap_product_id': '', 'telegram_aps_environment': '',
        'telegram_enable_siri': False, 'telegram_enable_icloud': False, 'telegram_enable_watch': False,
    }
    import json
    text = ''.join(key + ' = ' + (str(value) if isinstance(value, bool) else json.dumps(value)) + '\n' for key, value in values.items())
    file = folder / 'variables.bzl'
    file.write_text(text, encoding='utf-8')
    os.chmod(file, 0o600)
    return file
