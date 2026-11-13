"""Apply the shared TG-ru artwork to the pinned Store APK's brand resources."""

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

from prepare_repack import A, FAMILY_PACKAGE, digest, within_work


BASE_SHA256 = "26ded0ec939baa63ee4cb8e77ed5f56cf95aed0b2855f56014f7729c13cee2e4"
VERSION_CODE = 10025
VERSION_NAME = "0.1.0-preview24-store"
ARTWORK = Path(__file__).resolve().parents[1] / "web/local-store/tg-ru-mark.png"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
ANDROID_NS = "http://schemas.android.com/apk/res/android"
PLANE_PATH = "M4,11.5L19.6,5.5Q20.8,5 20.5,6.4L17.9,18.8Q17.7,20 16.7,19.1L11.4,14.7L16.9,8.6L7.8,12.9L4,11.5ZM7.8,12.9L9.6,18.5Q9.9,19.3 10.5,18.7L13.2,16.2M9.6,18.5L11.4,14.7"


def write_image(artwork, destination, size, shape=None):
    image = artwork.resize(size, Image.Resampling.LANCZOS)
    if shape:
        width, height = size
        mask = Image.new("L", (width * 4, height * 4))
        bounds = (0, 0, width * 4 - 1, height * 4 - 1)
        draw = ImageDraw.Draw(mask)
        if shape == "round":
            draw.ellipse(bounds, fill=255)
        else:
            draw.rounded_rectangle(bounds, radius=width, fill=255)
        image.putalpha(mask.resize(size, Image.Resampling.LANCZOS))
    image.save(destination, lossless=True)


def vector(width, path_width, scale=1, offset=0):
    return f'''<vector xmlns:android="{ANDROID_NS}" android:width="{width}dp"
    android:height="{width}dp" android:viewportWidth="24" android:viewportHeight="24">
    <group android:scaleX="{scale}" android:scaleY="{scale}"
        android:pivotX="12" android:pivotY="12" android:translateY="{offset}">
        <path android:strokeColor="#ffffffff" android:strokeWidth="{path_width}"
            android:strokeLineJoin="round" android:strokeLineCap="round" android:pathData="{PLANE_PATH}" />
    </group>
</vector>
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    decoded = within_work(args.work) / "decoded"
    if digest(args.base) != BASE_SHA256 or not (decoded / "classes10.dex").is_file():
        raise ValueError("Decode the signed preview23 Store APK with Apktool --no-src")

    manifest = ET.parse(decoded / "AndroidManifest.xml")
    app = manifest.getroot().find("application")
    if manifest.getroot().get("package") != FAMILY_PACKAGE or app is None:
        raise ValueError("Unexpected application package")
    metadata = decoded / "apktool.yml"
    versions = metadata.read_text("utf-8")
    if versions.count("versionCode: 10024") != 1 or versions.count("versionName: 0.1.0-preview23-store") != 1:
        raise ValueError("Unexpected APK version")
    launcher_names = {
        ref.removeprefix("@mipmap/")
        for element in app.iter()
        for name in ("icon", "roundIcon")
        if (ref := element.get(A + name, "")).startswith("@mipmap/")
    }
    if "ic_launcher_exteraless" not in launcher_names:
        raise ValueError("The expected Store launcher is missing")

    res = decoded / "res"
    adaptive = res / "mipmap-anydpi-v26"
    common_icon = (adaptive / "ic_launcher_exteraless.xml").read_text("utf-8")
    drawable = res / "drawable"
    # The photograph fills the adaptive layer. Android applies the launcher mask.
    (drawable / "exteraless_icon_monochrome.xml").write_text(vector(108, 1.7, 0.72), "utf-8")
    (drawable / "exteraless_icon_tile.xml").write_text(vector(24, 1.85), "utf-8")
    (res / "drawable-anydpi/exteraless_notification.xml").write_text(vector(24, 1.85), "utf-8")
    with Image.open(ARTWORK) as source:
        artwork = source.convert("RGBA")
        for name in sorted(launcher_names):
            (adaptive / f"{name}.xml").write_text(common_icon, "utf-8")
        for density, scale in DENSITIES.items():
            for name in sorted(launcher_names):
                shape = "round" if name.endswith("_round") else "rounded"
                write_image(artwork, res / f"mipmap-{density}/{name}.png", (int(48 * scale),) * 2, shape)
            directory = res / f"drawable-{density}"
            for name, shape in (("exteraless_icon.png", "rounded"), ("exteraless_icon_foreground.png", None),
                                ("logo_middle.webp", "round")):
                target = directory / name
                if target.is_file():
                    with Image.open(target) as existing:
                        size = existing.size
                    write_image(artwork, target, size, shape)
    metadata.write_text(versions.replace("versionCode: 10024", f"versionCode: {VERSION_CODE}")
                        .replace("versionName: 0.1.0-preview23-store", f"versionName: {VERSION_NAME}"), "utf-8")
    print(f"Updated {len(launcher_names)} launcher resources, in-app artwork and monochrome icons.")


if __name__ == "__main__":
    main()
