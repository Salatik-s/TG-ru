import base64
import hashlib
import json
from pathlib import Path

root=Path(__file__).resolve().parent.parent
original=json.loads((root/'build-support/egress-original-hashes.json').read_text())
names=['server/main.mjs','server/gateway.mjs','server/egress.mjs','server/egress-probe.mjs','server/vpn-nodes.mjs','server/vpn-snapshot.mjs','deploy/install.sh','deploy/compose.yaml','deploy/Deploy.ps1','deploy/import-vpn.py','deploy/verify-egress.mjs']
payload={'staging':'/tmp/familygram-3cfc4992b0494f6fbdc25c191822d563','updates':{},'unchanged':{}}
for name in names:
    data=(root/name).read_bytes()
    payload['updates'][name]={'old_sha256':original.get(name),'new_sha256':hashlib.sha256(data).hexdigest(),'content':base64.b64encode(data).decode('ascii')}
for name in ['runtime/xray','runtime/xray.sha256','server/access.mjs','server/http.mjs','server/push.mjs','server/owner.mjs','server/package.json']:
    payload['unchanged'][name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
(root/'deploy/egress-repair.json').write_text(json.dumps(payload,separators=(',',':'))+'\n',encoding='ascii')
print('Prepared public egress repair; no VPN credentials included.')
