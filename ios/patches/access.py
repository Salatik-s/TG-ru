"""Install the native family gate after the checked Personal-Team storage patch."""
import argparse
import hashlib
from pathlib import Path

APP = 'submodules/TelegramUI/Sources/AppDelegate.swift'
BUILD = 'submodules/TelegramUI/BUILD'
APP_PIN = 'ebccd1fe2d7cf57b7118249bb29049900f0f86659341f841ac4812b691e01c3c'
BUILD_PIN = 'a1a0ba5f2f24e0252431a3a335c03b5277e5956268861ae767b7871ba52e55d7'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def patch(app, build):
    if digest(app.encode()) != APP_PIN or digest(build.encode()) != BUILD_PIN:
        raise ValueError('Access bootstrap source hash mismatch; no partial patch')
    imports = 'import UIKit\n'
    created = '        self.nativeWindow = window\n'
    shown = '        self.window?.makeKeyAndVisible()\n'
    for anchor in (imports, created, shown):
        if app.count(anchor) != 1:
            raise ValueError('Access bootstrap application anchor changed')
    app = app.replace(imports, imports + 'import FamilyUI\n', 1)
    app = app.replace(created, created + '        FamilyApplication.install(host: window)\n', 1)
    app = app.replace(shown, shown + '        if let window = self.window { FamilyApplication.install(host: window) }\n', 1)
    if build.count('    deps = [\n') != 1:
        raise ValueError('Access bootstrap BUILD anchor changed')
    build = build.replace('    deps = [\n', '    deps = [\n        "//family-ui:FamilyUI",\n', 1)
    return app, build


def apply(source):
    source = Path(source).resolve()
    paths = [source / APP, source / BUILD]
    for path in paths:
        if path.is_symlink() or not path.resolve().is_relative_to(source):
            raise ValueError('Access patch path leaves source')
    output = patch(*(path.read_text(encoding='utf-8') for path in paths))
    for path, value in zip(paths, output):
        path.write_text(value, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    apply(parser.parse_args().source)
    print('Native access gate and FamilyUI dependency installed from pinned sources')
