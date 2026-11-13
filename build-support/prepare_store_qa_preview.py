"""Prepare the Store QA fixes against the pinned, branded preview24 APK."""

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from prepare_repack import A, FAMILY_PACKAGE, digest, within_work
from prepare_route_update import generate_r


BASE_SHA256 = "e00e29122c085fe511c2861ce326750696f47e61a6caeb34c0f799c1d80ee5d7"


def update_intro_text(decoded):
    copy = {
        "values": {
            "Page1Message": "Sign in with your Telegram account to open your chats.",
            "AllowFillNumber": "Allow phone access to verify your number automatically. You can also enter it manually.",
        },
        "values-ru": {
            "Page1Message": "Войдите в аккаунт Telegram, чтобы открыть свои чаты.",
            "AllowFillNumber": "Разрешите доступ к звонкам для автоматического подтверждения номера. Номер также можно ввести вручную.",
        },
    }
    for directory, replacements in copy.items():
        target = decoded / "res" / directory / "strings.xml"
        source = target.read_text("utf-8")
        for name, value in replacements.items():
            source, count = re.subn(
                rf'<string name="{name}">.*?</string>', f'<string name="{name}">{value}</string>',
                source, count=1, flags=re.DOTALL,
            )
            if not count:
                source = source.replace("</resources>", f'    <string name="{name}">{value}</string>\n</resources>')
        target.write_text(source, "utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    work = within_work(args.work)
    decoded = work / "decoded"
    if digest(args.base) != BASE_SHA256 or not (decoded / "classes10.dex").is_file():
        raise ValueError("Decode the pinned preview24 Store APK with Apktool --no-src")

    manifest = ET.parse(decoded / "AndroidManifest.xml").getroot()
    app = manifest.find("application")
    if manifest.get("package") != FAMILY_PACKAGE or app is None or not any(
        node.get(A + "name") == "org.telegram.messenger.family.STORE" for node in app.findall("meta-data")
    ):
        raise ValueError("Unexpected Store APK manifest")
    metadata = decoded / "apktool.yml"
    versions = metadata.read_text("utf-8")
    if versions.count("versionCode: 10025") != 1 or versions.count("versionName: 0.1.0-preview24-store") != 1:
        raise ValueError("Unexpected APK version")
    generate_r(ET.parse(decoded / "res/values/public.xml"), work)
    update_intro_text(decoded)
    metadata.write_text(versions.replace("versionCode: 10025", "versionCode: 10026")
                        .replace("versionName: 0.1.0-preview24-store", "versionName: 0.1.0-preview25-store"), "utf-8")
    (work / "report.json").write_text(json.dumps({"base_sha256": BASE_SHA256, "version_code": 10026}), "utf-8")


if __name__ == "__main__":
    main()
