import base64
import hashlib
import json
from pathlib import Path

root=Path(__file__).resolve().parent.parent
original=json.loads((root/'build-support/transport-original-hashes.json').read_text())
payload={'staging':'/tmp/familygram-3cfc4992b0494f6fbdc25c191822d563','updates':{},'unchanged':{}}
for name,old in original.items():
    data=(root/name).read_bytes()
    payload['updates'][name]={'old_sha256':old,'new_sha256':hashlib.sha256(data).hexdigest(),'content':base64.b64encode(data).decode('ascii')}
for name in ['runtime/xray','runtime/xray.sha256','server/main.mjs','server/egress.mjs','server/egress-probe.mjs','server/vpn-nodes.mjs','server/vpn-snapshot.mjs','server/access.mjs','server/push.mjs','server/owner.mjs','server/package.json','deploy/compose.yaml','deploy/import-vpn.py']:
    payload['unchanged'][name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
(root/'deploy/transport-repair.json').write_text(json.dumps(payload,separators=(',',':'))+'\n',encoding='ascii')
print('Prepared verified WSS transport repair without private credential material.')
