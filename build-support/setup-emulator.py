"""Install the pinned official Google ATD image and create a task-owned QA AVD."""
import hashlib
from pathlib import Path
import zipfile

IMAGE = Path("C:/Users/andre/.cache/familygram/emulator-google-atd30.zip")
EXPECTED = "b3137c9ad0e3dfea315f53c8a81c5b480591732d"
SDK_IMAGE = Path("C:/Users/andre/AppData/Local/Android/Sdk/system-images/android-30/google_atd")
AVD_ROOT = Path("C:/Users/andre/.android/avd")
AVD_NAME = "FamilyGram_QA_API30"
AVD = AVD_ROOT / (AVD_NAME + ".avd")
digest = hashlib.sha1()
with IMAGE.open("rb") as stream:
    while data := stream.read(1024 * 1024): digest.update(data)
if digest.hexdigest() != EXPECTED:
    raise ValueError("Official image SHA1 mismatch")
SDK_IMAGE.mkdir(parents=True, exist_ok=True)
marker = SDK_IMAGE / ".familygram-qa-image"
if not marker.exists() and any(SDK_IMAGE.iterdir()):
    raise ValueError("Image directory belongs to an existing installation")
with zipfile.ZipFile(IMAGE) as archive:
    for entry in archive.infolist():
        target = (SDK_IMAGE / entry.filename).resolve()
        if not target.is_relative_to(SDK_IMAGE.resolve()):
            raise ValueError("Unsafe image archive entry")
    archive.extractall(SDK_IMAGE)
marker.write_text(EXPECTED, encoding="ascii")
if AVD.exists() and not (AVD / ".familygram-qa").exists():
    raise ValueError("QA AVD name is already owned")
AVD.mkdir(parents=True, exist_ok=True)
(AVD / ".familygram-qa").write_text("familygram-v1", encoding="ascii")
(AVD / "config.ini").write_text("""AvdId=FamilyGram_QA_API30
avd.ini.displayname=FamilyGram QA API30
avd.ini.encoding=UTF-8
abi.type=x86_64
disk.dataPartition.size=2G
fastboot.forceColdBoot=yes
hw.accelerometer=yes
hw.audioInput=no
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.dPad=no
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=1920
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=2048
hw.sdCard=no
image.sysdir.1=system-images/android-30/google_atd/x86_64/
showDeviceFrame=no
skin.dynamic=yes
tag.display=Google APIs ATD
tag.id=google_atd
target=android-30
vm.heapSize=256
""", encoding="ascii")
(AVD_ROOT / (AVD_NAME + ".ini")).write_text("avd.ini.encoding=UTF-8\npath=" + str(AVD) + "\ntarget=android-30\n", encoding="ascii")
print("Verified official image and created task-owned QA AVD:", AVD_NAME)
