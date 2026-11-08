"""Import the owner's saved VPN profiles into FamilyGram only; never print secrets."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile


def command(args, timeout=15, data=None):
    return subprocess.run(args, input=data, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout)


def private_write(path, value):
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid not in {0, 10001}:
            raise ValueError("Unexpected existing FamilyGram egress file")
    fd, temporary = tempfile.mkstemp(prefix=".egress-import-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.chown(temporary, 10001, 10001)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def import_profiles(source, output):
    result = command(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=24projects",
                      "--filter", "label=com.docker.compose.service=vpn"])
    ids = result.stdout.split()
    if result.returncode or len(ids) != 1 or not re.fullmatch(r"[a-f0-9]{12,64}", ids[0]):
        raise ValueError("Expected one running, owned 24projects VPN container")
    container = ids[0]
    result = command(["docker", "inspect", "-f", '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Running}}', container])
    if result.returncode or result.stdout.strip() != "24projects|vpn|true":
        raise ValueError("Existing VPN container identity changed")
    parser = base64.b64encode((source / "server/vpn-nodes.mjs").read_bytes()).decode("ascii")
    snapshot_code = (source / "server/vpn-snapshot.mjs").read_text(encoding="utf-8")
    snapshot_code = snapshot_code.replace("'./vpn-nodes.mjs'", "'data:text/javascript;base64," + parser + "'")
    snapshot_url = "data:text/javascript;base64," + base64.b64encode(snapshot_code.encode()).decode("ascii")
    # A locally saved export is optional. Never fetch a provider URL or create another identity.
    raw = ""
    export = Path("/etc/24projects/secrets/vpn_node_export")
    if export.is_file() and not export.is_symlink() and export.stat().st_size <= 2 * 1024 * 1024:
        raw = export.read_text(encoding="utf-8-sig")
    script = """
import {readFileSync,statSync} from 'node:fs';
import {join} from 'node:path';
const {buildSnapshot}=await import(__SNAPSHOT_URL__);
const bounded=path=>{if(statSync(path).size>2*1024*1024)throw Error('OVERSIZED_PROFILE');return readFileSync(path,'utf8');};
const read=path=>{try{return JSON.parse(bounded(path));}catch{return null;}};
const dir=process.env.VPN_STATE_DIR||'/var/lib/24projects-vpn';
let raw=Buffer.from(__RAW__,'base64').toString('utf8');
if(!raw&&process.env.VPN_SUBSCRIPTION_FILE&&!/^https?:/i.test(process.env.VPN_SUBSCRIPTION_FILE)){
 try{raw=bounded(process.env.VPN_SUBSCRIPTION_FILE);}catch{}
}
const result=buildSnapshot({raw,cached:read(join(dir,'nodes.json')),selection:read(join(dir,'selection.json'))});
if(!result.snapshot.candidates.length)throw Error('NO_SUPPORTED_SAVED_VPN_PROFILES');
process.stdout.write(JSON.stringify(result));
""".replace("__SNAPSHOT_URL__", json.dumps(snapshot_url)).replace("__RAW__", json.dumps(base64.b64encode(raw.encode()).decode("ascii")))
    # Use the VPN service's own UID: its cache is 0600, and cap_drop=ALL means
    # a UID0 exec is not guaranteed to bypass that service user's permissions.
    result = command(["docker", "exec", "-i", container, "node", "--input-type=module", "-"], data=script)
    if result.returncode:
        # The child may include profile contents in a syntax-error excerpt. Never forward stderr.
        raise ValueError("Saved VPN profile import failed; no private details printed")
    if len(result.stdout.encode()) > 2 * 1024 * 1024:
        raise ValueError("Saved VPN snapshot is too large")
    imported = json.loads(result.stdout)
    snapshot = imported["snapshot"]
    if snapshot.get("version") != 1 or not 1 <= len(snapshot.get("candidates", [])) <= 256:
        raise ValueError("Invalid saved VPN snapshot")
    private_write(output / "egress.json", snapshot)
    regular = sum(item["tier"] == "regular" for item in snapshot["candidates"])
    fallback = sum(item["tier"] == "bs" for item in snapshot["candidates"])
    print(f"Imported saved native VPN profiles: {regular} regular, {fallback} BS fallback. Existing VPN left unchanged.", flush=True)
    counts = imported.get("stats", {})
    print(f"Profile import summary: {int(counts.get('unsupported', 0))} unsupported, {int(counts.get('invalid', 0))} invalid entries skipped.", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if os.geteuid() != 0 or args.output != Path("/opt/familygram/data"):
        raise ValueError("Unexpected owner or FamilyGram data path")
    base = args.output.parent
    if base.is_symlink() or (base / ".familygram-owned").read_text().strip() != "familygram-v1":
        raise ValueError("FamilyGram ownership marker missing")
    if args.output.is_symlink() or not args.output.is_dir():
        raise ValueError("Unexpected FamilyGram data directory")
    import_profiles(Path(__file__).resolve().parent.parent, args.output)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.TimeoutExpired, KeyError, json.JSONDecodeError):
        raise SystemExit("FamilyGram could not import the existing saved VPN profiles; existing services and private keys were preserved.")
