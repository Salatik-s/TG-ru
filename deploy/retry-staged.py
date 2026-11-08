"""Apply verified public-file updates to this task's existing upload, then install."""
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tempfile


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(1024 * 1024):
            value.update(data)
    return value.hexdigest()


def owned(path, directory=False):
    info = path.lstat()
    if info.st_uid != 0 or (not stat.S_ISDIR(info.st_mode) if directory else not stat.S_ISREG(info.st_mode)):
        raise ValueError("Unexpected owner or file type: " + str(path))


def checked(root, name):
    parts = PurePosixPath(name)
    if parts.is_absolute() or ".." in parts.parts or not parts.parts or parts.parts[0] not in {"deploy", "server", "runtime"}:
        raise ValueError("Unexpected release path")
    current = root
    for part in parts.parts[:-1]:
        current = current / part
        owned(current, directory=True)
    path = root.joinpath(*parts.parts)
    owned(path)
    return path


def apply_updates(root, payload):
    owned(root, directory=True)
    expected = {"deploy/install.sh", "deploy/compose.yaml", "deploy/Deploy.ps1"}
    if set(payload["updates"]) != expected:
        raise ValueError("Unexpected patch file set")
    prepared = []
    # Validate every precondition, including the existing runtime, before any write.
    for name, checksum in payload["unchanged"].items():
        if digest(checked(root, name)) != checksum:
            raise ValueError("Existing release input differs: " + name)
    for name, update in payload["updates"].items():
        path = checked(root, name)
        data = base64.b64decode(update["content"], validate=True)
        if hashlib.sha256(data).hexdigest() != update["new_sha256"]:
            raise ValueError("Invalid patch payload: " + name)
        old = digest(path)
        if old not in {update["old_sha256"], update["new_sha256"]}:
            raise ValueError("Existing public file differs: " + name)
        prepared.append((path, data))
    for path, data in prepared:
        fd, temporary = tempfile.mkstemp(prefix=".familygram-retry-", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temporary, 0o700 if path.name == "install.sh" else 0o644)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def main(payload):
    if os.geteuid() != 0:
        raise ValueError("Run through the owner's root SSH session")
    name = payload["staging"]
    if not re.fullmatch(r"/tmp/familygram-[a-f0-9]{32}", name):
        raise ValueError("Unexpected staging directory")
    root = Path(name)
    apply_updates(root, payload)
    print("Verified existing upload; patched three public files. No runtime re-upload.", flush=True)
    subprocess.run(["bash", str(root / "deploy/install.sh"), "--plan"], check=True)
    subprocess.run(["bash", str(root / "deploy/install.sh"), "--install"], check=True)


if __name__ == "__main__":
    main(json.loads(base64.b64decode("__FAMILYGRAM_RETRY_PAYLOAD__")))
