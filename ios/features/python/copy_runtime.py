"""Copy the already pinned local Android runtime; no network or Android writes."""
from pathlib import Path
import argparse, hashlib, json, shutil

def copy_runtime(source: Path, destination: Path):
    provenance = json.loads((source / 'runtime/provenance.json').read_text('utf-8'))
    if provenance['version'] != '314.0.7' or provenance['memory_maximum_bytes'] != 268435456:
        raise ValueError('Unexpected Python runtime version or memory bound')
    for name, expected in provenance['files'].items():
        if Path(name).name != name:
            raise ValueError('Unsafe runtime filename')
        if hashlib.sha256((source / 'runtime' / name).read_bytes()).hexdigest() != expected:
            raise ValueError('Runtime hash mismatch: ' + name)
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source / 'runtime', destination / 'runtime', dirs_exist_ok=True)
    shutil.copy2(source / 'worker.mjs', destination / 'worker.mjs')
    controller = (source / 'controller.mjs').read_text('utf-8')
    old = "console.log((globalThis.SAA_PREFIX||'SAA_RUNNER:')+JSON.stringify({id,type,phase,code,text:String(text).slice(0,65536)}))"
    if controller.count(old) != 1:
        raise ValueError('Controller bridge changed')
    controller = controller.replace(old, "window.webkit.messageHandlers.familyPython.postMessage({id,type,phase,code,text:String(text).slice(0,65536)})")
    controller += "\nwindow.webkit.messageHandlers.familyPython.postMessage({type:'controller_ready'});\n"
    (destination / 'controller.mjs').write_text(controller, 'utf-8', newline='\n')
    (destination / 'index.html').write_text('''<!doctype html><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; worker-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
<title>Local Python</title><script type="module" src="/runner/controller.mjs"></script>
''', 'utf-8', newline='\n')
    files = {p.relative_to(destination).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in destination.rglob('*') if p.is_file()}
    (destination.parent.parent / 'runtime-lock.json').write_text(json.dumps({'version': provenance['version'], 'files': files}, indent=2) + '\n', 'utf-8')
    print('Verified and copied', len(files), 'local runtime files; version', provenance['version'])

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    copy_runtime(args.source, Path(__file__).parent / 'Resources/family-python/runner')
