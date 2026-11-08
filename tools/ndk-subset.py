"""Extract the Windows/arm64 build subset from the official NDK ZIP over HTTPS.

Uses the same resumable 1 MiB range cache as download.py. ZIP CRCs are checked;
this is deliberately not represented as verification of the whole archive hash.
"""
import concurrent.futures
import io
from pathlib import Path
import threading
import sys
import time
import urllib.request
import zipfile
import zlib

URL = "https://dl.google.com/android/repository/android-ndk-r27c-windows.zip"
HERE = Path(__file__).resolve().parent
CACHE = HERE / "ndk-r27c.zip.parts"
BLOCK = 1024 * 1024
SIZE = 781511249
OUTPUT = HERE / "ndk-subset"


def chunk(index):
    CACHE.mkdir(exist_ok=True)
    part = CACHE / str(index)
    start, end = index * BLOCK, min(SIZE, (index + 1) * BLOCK) - 1
    if part.exists() and part.stat().st_size == end - start + 1:
        return part.read_bytes()
    for attempt in range(5):
        try:
            req = urllib.request.Request(URL, headers={"Range": f"bytes={start}-{end}", "User-Agent": "FamilyGram-Build/1"})
            with urllib.request.urlopen(req, timeout=60) as response:
                if response.status != 206:
                    raise ValueError("Range unsupported")
                data = response.read(end - start + 2)
            if len(data) != end - start + 1:
                raise ValueError("Truncated official archive range")
            part.write_bytes(data)
            return data
        except Exception:
            if attempt == 4:
                raise
            time.sleep(attempt + 1)


class Archive(io.RawIOBase):
    def __init__(self):
        self.offset = 0
    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.offset
    def seek(self, value, whence=0):
        self.offset = value if whence == 0 else self.offset + value if whence == 1 else SIZE + value
        return self.offset
    def read(self, length=-1):
        if length < 0: length = SIZE - self.offset
        end = min(SIZE, self.offset + length)
        result = []
        while self.offset < end:
            index, within = divmod(self.offset, BLOCK)
            data = chunk(index)
            take = min(end - self.offset, len(data) - within)
            result.append(data[within:within + take])
            self.offset += take
        return b"".join(result)


def needed(name):
    root = "android-ndk-r27c/"
    if not name.startswith(root) or name.endswith("/"): return False
    name = name[len(root):]
    prefix = "toolchains/llvm/prebuilt/windows-x86_64/"
    if name.startswith(("build/", "meta/", "sources/android/cpufeatures/")):
        return True
    if name in {"source.properties", "NOTICE", "NOTICE.toolchain", "package.xml"}:
        return True
    if not name.startswith(prefix): return False
    name = name[len(prefix):]
    if name.startswith("bin/"):
        # clang driver, linker, archiver/strip and their dependent DLLs.
        leaf = name.split("/")[-1]
        return (leaf.endswith(".dll") and leaf != "liblldb.dll") or leaf.endswith(".cmd") or leaf in {"clang.exe", "clang++.exe", "clang-18.exe", "ld.lld.exe", "lld.exe", "llvm-ar.exe", "llvm-ranlib.exe", "llvm-strip.exe", "llvm-readelf.exe", "llvm-objdump.exe", "llvm-nm.exe", "llvm-objcopy.exe"}
    if name.startswith("sysroot/usr/include/"):
        return True
    if name.startswith(("lib/clang/", "lib64/clang/")):
        return "/include/" in name or name.endswith(("libclang_rt.builtins-aarch64-android.a", "libclang_rt.builtins-x86_64-android.a")) or (
            any(arch in name for arch in ("/aarch64/", "/x86_64/")) and name.endswith(("/libatomic.a", "/libunwind.a")))
    for arch in ("aarch64-linux-android", "x86_64-linux-android"):
        prefix = f"sysroot/usr/lib/{arch}/"
        if name.startswith(prefix):
            relative = name[len(prefix):]
            return "/" not in relative or relative.startswith(("24/", "27/"))
    return False


with zipfile.ZipFile(Archive()) as archive:
    files = [item for item in archive.infolist() if needed(item.filename)]
    if "--inspect" in sys.argv:
        for item in sorted(files, key=lambda item: item.compress_size, reverse=True)[:50]:
            print(item.compress_size, item.file_size, hex(item.CRC), item.filename)
        raise SystemExit(0)
    indexes = set()
    for item in files:
        # Include local header, filename, ZIP extra fields, and compressed data.
        start = item.header_offset
        end = start + 30 + len(item.filename.encode("utf-8")) + len(item.extra) + item.compress_size + 256
        indexes.update(range(start // BLOCK, min(SIZE - 1, end) // BLOCK + 1))
    print(f"NDK subset: {len(files)} files; {len(indexes)} MiB ranges including cached data", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for number, _ in enumerate(pool.map(chunk, sorted(indexes)), 1):
            if number % 20 == 0 or number == len(indexes):
                print(f"NDK subset ranges: {number}/{len(indexes)}", flush=True)
    OUTPUT.mkdir(exist_ok=True)
    for item in files:
        target = (OUTPUT / item.filename).resolve()
        if not target.is_relative_to(OUTPUT.resolve()):
            raise ValueError("Unsafe official archive member")
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.stat().st_size == item.file_size:
            checksum = 0
            with target.open("rb") as existing:
                while block := existing.read(1024 * 1024): checksum = zlib.crc32(block, checksum)
            if checksum & 0xffffffff == item.CRC:
                continue
        with archive.open(item) as source, target.open("wb") as destination:
            while block := source.read(1024 * 1024): destination.write(block)
    print(f"Extracted checked ZIP members to {OUTPUT}; full archive SHA not yet verified.", flush=True)
