"""Resumable, bounded parallel HTTP downloader for public build dependencies."""
import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import time
import urllib.request


def download(url, output, workers=12, expected=None):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "FamilyGram-Build/1"})
    with urllib.request.urlopen(request, timeout=40) as response:
        size = int(response.headers["Content-Length"])
    chunks = output.with_name(output.name + ".parts")
    chunks.mkdir(exist_ok=True)
    block = 1024 * 1024
    total = (size + block - 1) // block

    def fetch(index):
        start, end = index * block, min(size, (index + 1) * block) - 1
        part = chunks / str(index)
        if part.exists() and part.stat().st_size == end - start + 1:
            return
        for attempt in range(5):
            try:
                req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}", "User-Agent": "FamilyGram-Build/1"})
                with urllib.request.urlopen(req, timeout=60) as response:
                    if response.status != 206:
                        raise ValueError("Server did not honor byte range")
                    data = response.read(end - start + 2)
                if len(data) != end - start + 1:
                    raise ValueError("Truncated range")
                part.write_bytes(data)
                return
            except Exception:
                if attempt == 4:
                    raise
                time.sleep(attempt + 1)

    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for _ in pool.map(fetch, range(total)):
            done += 1
            if done % 20 == 0 or done == total:
                print(f"{output.name}: {done}/{total} MiB chunks", flush=True)
    target = output.with_name(output.name + ".assembling")
    digest = hashlib.sha256()
    with target.open("wb") as stream:
        for index in range(total):
            data = (chunks / str(index)).read_bytes()
            digest.update(data)
            stream.write(data)
    if expected and digest.hexdigest().lower() != expected.lower():
        raise ValueError("SHA256 mismatch")
    os.replace(target, output)
    print(f"Completed {output.name}; bytes={size}; sha256={digest.hexdigest()}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("output")
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--sha256")
    args = parser.parse_args()
    download(args.url, args.output, args.workers, args.sha256)
