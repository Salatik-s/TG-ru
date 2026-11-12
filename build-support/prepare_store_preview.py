"""Prepare a Store APK from the verified clean Android preview."""

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from prepare_repack import A, FAMILY_PACKAGE, digest, within_work
from prepare_route_update import generate_r


PREVIEW20 = "de059a2365b83d09e8abb72d564852110a7f60941c364eb7a0633a6d74818b94"
ACTIVITY = "org.telegram.messenger.family.FamilyStoreGateActivity"
MARKER = "org.telegram.messenger.family.STORE"
LOCAL_MARKER = MARKER + "_LOCAL_QA"
VERSION_CODE = 10023
VERSION_NAME = "0.1.0-preview22-store"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--public", action="store_true", help="Use the production HTTPS origin")
    args = parser.parse_args()
    work = within_work(args.work)
    decoded = work / "decoded"
    if digest(args.base) != PREVIEW20 or not (decoded / "classes.dex").is_file():
        raise ValueError("Use the verified preview20 APK and Apktool --no-src")

    metadata_path = decoded / "apktool.yml"
    metadata = metadata_path.read_text("utf-8")
    if metadata.count("versionCode: 10021") != 1 or metadata.count("versionName: 0.1.0-preview20") != 1:
        raise ValueError("Unexpected version metadata")
    manifest_path = decoded / "AndroidManifest.xml"
    manifest = ET.parse(manifest_path)
    root = manifest.getroot()
    app = root.find("application")
    if root.get("package") != FAMILY_PACKAGE or app is None or app.get(A + "networkSecurityConfig") != "@xml/fg_network_security":
        raise ValueError("Unexpected package or network security configuration")
    if any(node.get(A + "name") in (MARKER, LOCAL_MARKER, ACTIVITY) for node in app):
        raise ValueError("Store preview has already been prepared")

    security_path = decoded / "res/xml/fg_network_security.xml"
    security = ET.parse(security_path)
    base_security = security.getroot().find("base-config")
    if base_security is None or base_security.get("cleartextTrafficPermitted") != "false":
        raise ValueError("Cleartext must stay blocked outside loopback")

    root.set(A + "versionCode", str(VERSION_CODE))
    root.set(A + "versionName", VERSION_NAME)
    ET.SubElement(app, "activity", {
        A + "name": ACTIVITY, A + "exported": "false",
        A + "theme": "@style/FamilyGramTheme", A + "windowSoftInputMode": "adjustResize",
    })
    ET.SubElement(app, "meta-data", {A + "name": MARKER, A + "value": "true"})
    if not args.public:
        ET.SubElement(app, "meta-data", {A + "name": LOCAL_MARKER, A + "value": "true"})
        local = ET.SubElement(security.getroot(), "domain-config", {"cleartextTrafficPermitted": "true"})
        ET.SubElement(local, "domain", {"includeSubdomains": "false"}).text = "127.0.0.1"

    generate_r(ET.parse(decoded / "res/values/public.xml"), work)
    manifest.write(manifest_path, "utf-8", xml_declaration=True)
    security.write(security_path, "utf-8", xml_declaration=True)
    metadata_path.write_text(metadata.replace("versionCode: 10021", f"versionCode: {VERSION_CODE}")
                             .replace("versionName: 0.1.0-preview20", f"versionName: {VERSION_NAME}"), "utf-8")
    (work / "report.json").write_text(json.dumps({
        "base_sha256": PREVIEW20, "version_code": VERSION_CODE, "local_store_qa": not args.public,
    }, indent=2), "utf-8")
    print("Prepared Store APK for public HTTPS" if args.public else "Prepared Store APK for loopback QA")


if __name__ == "__main__":
    main()
