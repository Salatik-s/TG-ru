"""Fetch pinned Git objects in small packs, avoiding long server pack timeouts."""
import argparse
import concurrent.futures
import os
from pathlib import Path
import subprocess
import time


def run(repo, args, **kwargs):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, **kwargs)


def needed(path):
    if path.startswith((".github/", "assets/", "docs/", "tests/", "Tools/")):
        return False
    if path.startswith("TMessagesProj/jni/"):
        return path.startswith(("TMessagesProj/jni/tgnet/", "TMessagesProj/jni/boringssl/include/")) or path in {
            "TMessagesProj/jni/TgNetWrapper.cpp", "TMessagesProj/jni/jni.c",
            "TMessagesProj/jni/boringssl/lib/arm64-v8a/libcrypto.a",
        }
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repo")
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--batch", type=int, default=80)
    args = parser.parse_args()
    repo = Path(args.repo).resolve()
    tree = run(repo, ["ls-tree", "-r", "HEAD"]).stdout.decode("utf-8")
    objects = {}
    for line in tree.splitlines():
        meta, path = line.split("\t", 1)
        mode, kind, sha = meta.split()
        if kind == "blob" and needed(path):
            objects[sha] = path
    env = dict(os.environ, GIT_NO_LAZY_FETCH="1")
    checks = run(repo, ["cat-file", "--batch-check"], input=("\n".join(objects) + "\n").encode(), env=env).stdout.decode()
    missing = [line.split()[0] for line in checks.splitlines() if line.endswith(" missing")]
    groups = [missing[i:i + args.batch] for i in range(0, len(missing), args.batch)]
    print(f"Pinned objects: {len(objects)}; missing: {len(missing)}; packs: {len(groups)}", flush=True)
    logdir = repo.parent / "build-logs" / "source-fetch"
    logdir.mkdir(parents=True, exist_ok=True)

    def fetch(item):
        index, group = item
        for attempt in range(4):
            try:
                result = run(repo, ["-c", "http.version=HTTP/1.1", "-c", "http.lowSpeedTime=45", "-c", "http.lowSpeedLimit=1000", "-c", "fetch.negotiationAlgorithm=noop", "fetch", "origin", "--no-tags", "--no-write-fetch-head", "--recurse-submodules=no", "--filter=blob:none", "--stdin"], input=("\n".join(group)+"\n").encode(), timeout=150)
                (logdir / f"{index}.log").write_bytes(result.stderr)
                print(f"Pack {index + 1}/{len(groups)}: {len(group)} objects", flush=True)
                return
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                (logdir / f"{index}.log").write_bytes((getattr(error, "stderr", None) or b"")[-10000:])
                if attempt == 3:
                    raise
                time.sleep(2 * (attempt + 1))
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(fetch, enumerate(groups)))
    print("All requested source objects fetched. Checkout remains a separate step.", flush=True)


if __name__ == "__main__":
    main()
