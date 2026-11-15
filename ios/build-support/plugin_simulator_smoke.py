"""Compile and launch the actual plugin UIKit screens in a temporary iPhone Simulator."""

import json
from pathlib import Path
import platform
import plistlib
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "features/plugins"
BUNDLE = "ru.tgru.plugin-smoke"


def run(*arguments, **kwargs):
    return subprocess.run([str(value) for value in arguments], check=True, text=True, **kwargs)


def capture(*arguments):
    return run(*arguments, capture_output=True).stdout.strip()


def main():
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise RuntimeError("Apple Silicon macOS with an iOS Simulator SDK is required")
    output = ROOT / "dist/plugin-simulator"
    output.mkdir(parents=True, exist_ok=True)
    sdk = capture("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path")
    inventory = json.loads(capture("xcrun", "simctl", "list", "--json"))
    runtimes = [item for item in inventory["runtimes"]
                if item.get("isAvailable") and item["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
    if not runtimes:
        raise RuntimeError("No available iOS Simulator runtime")
    runtime = max(runtimes, key=lambda item: tuple(map(int, item["version"].split("."))))
    devices = [item for item in inventory["devices"].get(runtime["identifier"], [])
               if item.get("isAvailable") and item["name"].startswith("iPhone") and item.get("deviceTypeIdentifier")]
    if not devices:
        raise RuntimeError("No installed iPhone Simulator for " + runtime["name"])
    device_type = devices[0]["deviceTypeIdentifier"]
    simulator = None
    try:
        with tempfile.TemporaryDirectory(prefix="tgru-plugin-simulator-") as temporary:
            app = Path(temporary) / "TgruPluginSmoke.app"
            app.mkdir()
            executable = app / "TgruPluginSmoke"
            sources = [PLUGIN / "Sources/TgruPlugin.swift", PLUGIN / "TgruPluginDownload.swift",
                       PLUGIN / "TgruPluginController.swift", PLUGIN / "Simulator/PluginSmokeApp.swift"]
            run("xcrun", "swiftc", "-swift-version", "5", "-parse-as-library",
                "-target", "arm64-apple-ios17.0-simulator", "-sdk", sdk,
                "-framework", "UIKit", "-o", executable, *sources)
            info = {
                "CFBundleIdentifier": BUNDLE, "CFBundleName": "TG-ru Plugin Check",
                "CFBundleExecutable": executable.name, "CFBundlePackageType": "APPL",
                "CFBundleVersion": "1", "CFBundleShortVersionString": "1.0",
                "MinimumOSVersion": "17.0", "UIDeviceFamily": [1],
                "UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": False},
                "UILaunchScreen": {},
            }
            (app / "Info.plist").write_bytes(plistlib.dumps(info))
            run("codesign", "--force", "--sign", "-", app)
            simulator = capture("xcrun", "simctl", "create", "TG-ru plugin smoke", device_type, runtime["identifier"])
            run("xcrun", "simctl", "boot", simulator)
            run("xcrun", "simctl", "bootstatus", simulator, "-b")
            run("xcrun", "simctl", "install", simulator, app)
            run("xcrun", "simctl", "launch", "--terminate-running-process", simulator, BUNDLE)
            container = Path(capture("xcrun", "simctl", "get_app_container", simulator, BUNDLE, "data"))
            result_path = container / "Documents/smoke-result.json"
            deadline = time.monotonic() + 45
            while not result_path.is_file() and time.monotonic() < deadline:
                time.sleep(1)
            run("xcrun", "simctl", "io", simulator, "screenshot", output / "plugins.png")
            if not result_path.is_file():
                raise RuntimeError("Plugin app did not report a result within 45 seconds")
            shutil.copyfile(result_path, output / "result.json")
            result = json.loads(result_path.read_text())
            if result.get("ok") is not True:
                raise RuntimeError("Plugin UI check failed: " + result.get("error", "unknown"))
            print("PLUGIN_SIMULATOR_SMOKE_PASS")
            for check in result["checks"]:
                print("PASS " + check)
    finally:
        if simulator:
            subprocess.run(["xcrun", "simctl", "shutdown", simulator], check=False, capture_output=True)
            subprocess.run(["xcrun", "simctl", "delete", simulator], check=False, capture_output=True)


if __name__ == "__main__":
    main()
