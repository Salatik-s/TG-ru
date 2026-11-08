"""Prepare a hash-bound owner-role update using the existing WSS upload."""
import base64
import hashlib
import json
import re
from pathlib import Path

root=Path(__file__).resolve().parent.parent
original=json.loads((root/'build-support/owner-original-hashes.json').read_text())
identity=(root/'build-support/owner-phone-id.txt').read_text().strip()
if not re.fullmatch('[a-f0-9]{64}',identity):raise ValueError('Verified physical installation identity required')
payload={'staging':'/tmp/familygram-3cfc4992b0494f6fbdc25c191822d563','phone_owner_id':identity,'updates':{},'unchanged':{}}
for name,old in original.items():
    data=(root/name).read_bytes()
    payload['updates'][name]={'old_sha256':old,'new_sha256':hashlib.sha256(data).hexdigest(),'content':base64.b64encode(data).decode('ascii')}
for name in ['runtime/xray','runtime/xray.sha256','server/gateway.mjs','server/egress.mjs','server/egress-probe.mjs','server/vpn-nodes.mjs','server/vpn-snapshot.mjs','server/push.mjs','server/package.json','deploy/compose.yaml','deploy/install.sh','deploy/nginx.conf','deploy/verify-egress.mjs','deploy/import-vpn.py']:
    payload['unchanged'][name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
(root/'deploy/owner-repair.json').write_text(json.dumps(payload,separators=(',',':'))+'\n',encoding='ascii')
template=(root/'deploy/repair-transport.py').read_text()
template=template.replace('__FAMILYGRAM_TRANSPORT_PAYLOAD__','__FAMILYGRAM_OWNER_PAYLOAD__')
template=template.replace('{"server/gateway.mjs", "server/http.mjs", "deploy/nginx.conf", "deploy/install.sh", "deploy/verify-egress.mjs"}', '{"server/access.mjs", "server/http.mjs", "server/main.mjs", "server/owner.mjs"}')
template=template.replace('Verified existing upload; enabled authenticated WSS on the existing HTTPS entry point. No runtime re-upload.', 'Verified existing upload; updating installation-bound phone management. No runtime re-upload.')
needle='    subprocess.run(["bash", str(root / "deploy/install.sh"), "--install"], check=True)'
replacement=needle+'''
    identity=payload["phone_owner_id"]
    if not re.fullmatch(r"[a-f0-9]{64}",identity):raise ValueError("Invalid owner installation identity")
    base=Path("/opt/familygram")
    if base.is_symlink() or (base/".familygram-owned").read_text().strip()!="familygram-v1":raise ValueError("FamilyGram marker missing")
    current=(base/"current").resolve(strict=True)
    if not current.is_relative_to(base/"releases") or current==base/"releases":raise ValueError("Invalid active release")
    compose=["docker","compose","--project-directory",str(current/"deploy"),"--env-file",str(current/"deploy/.env"),"-f",str(current/"deploy/compose.yaml")]
    subprocess.run(compose+["exec","-T","access","node","owner.mjs","set-owner",identity],check=True)
    print("Phone management bound to the verified Android Keystore installation. Other approved devices remain regular family devices.",flush=True)
'''
if template.count(needle)!=1:raise ValueError('Installer template changed')
template=template.replace(needle,replacement)
(root/'deploy/repair-owner.py').write_text(template,encoding='utf-8')
print('Prepared four-file update; owner identity came from a signed proof on the connected physical phone.')
