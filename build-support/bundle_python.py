"""Bundle a pinned official Pyodide runtime; lower only the WASM memory maximum."""
import hashlib,json,shutil
from pathlib import Path
root=Path(__file__).resolve().parent.parent
source=root/'tools/pyodide-314.0.7/pyodide'
target=root/'overlay/assets/familygram/runner/runtime'
target.mkdir(parents=True,exist_ok=True)
for name in ['pyodide.mjs','pyodide.asm.mjs','python_stdlib.zip','pyodide-lock.json']:
    shutil.copy2(source/name,target/name)

def decode(data,at):
    value=shift=0
    while True:
        b=data[at];at+=1;value|=(b&127)<<shift
        if b<128:return value,at
        shift+=7
        if shift>35:raise ValueError('Invalid WASM integer')
def encode(value):
    out=bytearray()
    while True:
        b=value&127;value>>=7;out.append(b|(128 if value else 0))
        if not value:return bytes(out)
original=(source/'pyodide.asm.wasm').read_bytes()
if original[:8]!=b'\0asm\1\0\0\0':raise ValueError('Unexpected WASM module')
out=bytearray(original[:8]);at=8;changed=0
while at<len(original):
    kind=original[at];at+=1;size,at=decode(original,at);body=original[at:at+size];at+=size
    if kind==5:
        count,pos=decode(body,0)
        if count!=1:raise ValueError('Expected exactly one WASM memory')
        flags,pos=decode(body,pos);minimum,pos=decode(body,pos)
        if flags!=1:raise ValueError('Expected bounded nonshared 32-bit WASM memory')
        maximum,pos=decode(body,pos)
        if pos!=len(body) or minimum>4096 or maximum<4096:raise ValueError('Unexpected WASM memory bounds')
        body=encode(count)+encode(flags)+encode(minimum)+encode(4096);changed+=1
    out.append(kind);out.extend(encode(len(body)));out.extend(body)
if changed!=1:raise ValueError('No bounded memory section patched')
(target/'pyodide.asm.wasm').write_bytes(out)
provenance=json.loads((root/'tools/pyodide-314.0.7/provenance.json').read_text())
provenance.update({'memory_maximum_bytes':256*1024*1024,'derivation':'Only section 5 memory.maximum changed to 4096 pages; code/data sections unchanged',
 'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in target.iterdir() if p.is_file()}})
(target/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
print('Bundled offline Python runtime; code/data preserved and memory maximum bounded to 256 MiB.')
