"""Prepare a public, hash-bound retry for the exact upload reported by the owner."""
import base64
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
original = json.loads((root / "build-support/retry-original-hashes.json").read_text())
payload = {"staging": "/tmp/familygram-3cfc4992b0494f6fbdc25c191822d563", "updates": {}, "unchanged": {}}
for name, old in original.items():
    data = (root / name).read_bytes()
    payload["updates"][name] = {"old_sha256": old, "new_sha256": hashlib.sha256(data).hexdigest(), "content": base64.b64encode(data).decode("ascii")}
for path in sorted((root / "server").glob("*.mjs")) + [root / "server/package.json", root / "runtime/xray", root / "runtime/xray.sha256"]:
    payload["unchanged"][path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
(root / "deploy/retry-staged.json").write_text(json.dumps(payload, separators=(",", ":")) + "\n", encoding="ascii")
print("Prepared hash-verified retry for", payload["staging"])
