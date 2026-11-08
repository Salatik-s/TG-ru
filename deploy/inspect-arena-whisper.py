"""Read-only inventory; never imports Arena code or reads credentials/content."""
import concurrent.futures
import hashlib
import json
import os
import pathlib
import re
import subprocess
import urllib.request

MATCH = re.compile(r'arena|whisper|mafia|negotiat|speech|(?:^|[-_])stt(?:$|[-_])', re.I)
HTTP_PORTS = {3000, 5000, 5173, 8000, 8001, 8080, 8081, 9000}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def emit(check, **data):
    print(json.dumps({'check': check, **data}, ensure_ascii=True), flush=True)


def command(args):
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=8, encoding='utf-8', errors='replace')
        return result.stdout[:262144] if result.returncode == 0 else ''
    except Exception:
        return ''


def safe_path(value):
    # These are source/destination paths only, never their file contents.
    return str(value)[:400].replace('\n', '?').replace('\r', '?')


def tcp_ports(pid):
    inodes = set()
    try:
        for descriptor in pathlib.Path(f'/proc/{pid}/fd').iterdir():
            try:
                target = os.readlink(descriptor)
                if target.startswith('socket:['):
                    inodes.add(target[8:-1])
            except OSError:
                pass
    except OSError:
        pass
    ports = set()
    for name in ('tcp', 'tcp6'):
        try:
            for line in pathlib.Path(f'/proc/{pid}/net/{name}').read_text().splitlines()[1:]:
                fields = line.split()
                if len(fields) >= 10 and fields[3] == '0A' and fields[9] in inodes:
                    ports.add(int(fields[1].split(':')[-1], 16))
        except OSError:
            pass
    return sorted(ports)


def process_metadata(pid):
    value = {'pid': pid, 'listen_ports': tcp_ports(pid)}
    try:
        maps = pathlib.Path(f'/proc/{pid}/maps').read_text()
        value['whisper_or_ctranslate_mapped'] = len(set(line.split()[-1] for line in maps.splitlines()
                                                        if re.search(r'whisper|ctranslate|/model\.bin', line, re.I)))
        status = pathlib.Path(f'/proc/{pid}/status').read_text()
        found = re.search(r'^VmRSS:\s+(\d+)\s+kB$', status, re.M)
        value['rss_kib'] = int(found.group(1)) if found else None
    except OSError:
        pass
    return value


def inspect_public_files(directory):
    if not directory or directory == '/':
        return
    root = pathlib.Path(directory)
    # Only fixed public source names and model file metadata; no imports, DB or env.
    for relative in ('backend/app/voice.py', 'backend/app/speech_service.py', 'app/voice.py',
                     'app/speech_service.py', 'worker/worker.py'):
        target = root / relative
        try:
            if target.is_file() and target.stat().st_size < 262144:
                raw = target.read_bytes()
                emit('ARENA_PUBLIC_SOURCE', path=safe_path(target), sha256=hashlib.sha256(raw).hexdigest(),
                     faster_whisper=b'faster_whisper' in raw, outbound_worker=b'/api/stt-workers/' in raw,
                     max_40_seconds=b'1_280_000' in raw or b'1280000' in raw,
                     private_asr_hook=b'familygram' in raw.lower())
        except OSError:
            pass
    roots = {root, root.parent, root / 'backend'}
    for parent in roots:
        for suffix in ('models', 'data/models', 'shared/models'):
            for name in ('tiny', 'base', 'small', 'large-v3-turbo'):
                model = parent / suffix / ('whisper-' + name) / 'model.bin'
                try:
                    if model.is_file():
                        emit('ARENA_MODEL_PRESENT', path=safe_path(model), bytes=model.stat().st_size)
                except OSError:
                    pass


def openapi(url):
    try:
        with opener.open(url + '/openapi.json', timeout=4) as response:
            if response.geturl() != url + '/openapi.json':
                return {'endpoint': url, 'available': False, 'code': 'REDIRECT_REJECTED'}
            raw = response.read(2 * 1024 * 1024 + 1)
        if len(raw) > 2 * 1024 * 1024:
            raise ValueError()
        document = json.loads(raw)
        paths = []
        for path, methods in document.get('paths', {}).items():
            if not re.search(r'stt|transcrib|speech|voice', path, re.I):
                continue
            for method, operation in methods.items():
                if method.lower() not in {'get', 'put', 'post', 'delete'} or not isinstance(operation, dict):
                    continue
                paths.append({'path': path[:180], 'method': method.upper(),
                              'security_declared': bool(operation.get('security', document.get('security', [])))})
        return {'endpoint': url, 'available': True, 'routes': paths[:100],
                'note': 'security_declared is OpenAPI metadata, not an authentication bypass test'}
    except Exception:
        return {'endpoint': url, 'available': False, 'code': 'NO_OPENAPI'}


def main():
    if os.geteuid() != 0:
        emit('ARENA_INVENTORY', error='ROOT_REQUIRED')
        return 1
    urls = set()
    raw = command(['systemctl', 'list-units', '--type=service', '--all', '--no-legend', '--plain'])
    units = sorted(set(line.split()[0] for line in raw.splitlines() if line.split() and MATCH.search(line.split()[0])))[:20]
    for unit in units:
        fields = {}
        data = command(['systemctl', 'show', unit, '-p', 'LoadState', '-p', 'ActiveState', '-p', 'MainPID', '-p', 'WorkingDirectory'])
        for line in data.splitlines():
            if '=' in line:
                key, value = line.split('=', 1)
                fields[key] = value
        pid = int(fields.get('MainPID', '0') or '0')
        emit('ARENA_SYSTEMD', unit=unit, active=fields.get('ActiveState'),
             working_directory=safe_path(fields.get('WorkingDirectory', '')), **(process_metadata(pid) if pid else {}))
        inspect_public_files(fields.get('WorkingDirectory'))
        if pid:
            urls.update(f'http://127.0.0.1:{p}' for p in tcp_ports(pid) if p in HTTP_PORTS)
    ids = []
    for line in command(['docker', 'ps', '-a', '--format', '{{json .}}']).splitlines():
        try:
            info = json.loads(line)
            emit('DOCKER_SERVICE', name=info.get('Names', '')[:150], status=info.get('Status', '')[:150])
            if MATCH.search(info.get('Names', '') + ' ' + info.get('Image', '')) and re.fullmatch('[a-f0-9]{12,64}', info.get('ID', '')):
                ids.append(info['ID'])
        except Exception:
            pass
    for cid in ids[:15]:
        # Full inspect is parsed in memory. Only this explicit allowlist is printed.
        try:
            info = json.loads(command(['docker', 'inspect', cid]))[0]
            cfg, state = info.get('Config', {}), info.get('State', {})
            emit('ARENA_CONTAINER', name=info.get('Name', '').lstrip('/'), image=cfg.get('Image'),
                 working_directory=safe_path(cfg.get('WorkingDir', '')),
                 networks=sorted(info.get('NetworkSettings', {}).get('Networks', {}).keys()),
                 mounts=[{'source': safe_path(m.get('Source', '')), 'destination': safe_path(m.get('Destination', '')), 'readonly': not m.get('RW', False)} for m in info.get('Mounts', [])],
                 **(process_metadata(state['Pid']) if state.get('Pid') else {}))
            for mount in info.get('Mounts', []):
                if mount.get('Type') == 'bind' and mount.get('Destination') in {'/app', '/opt/arena', '/workspace', '/srv/app'}:
                    inspect_public_files(mount.get('Source'))
            for port, bindings in info.get('NetworkSettings', {}).get('Ports', {}).items():
                if port.endswith('/tcp') and int(port.split('/')[0]) in HTTP_PORTS:
                    for binding in bindings or []:
                        host_port = binding.get('HostPort', '')
                        if host_port.isdecimal():
                            urls.add(f'http://127.0.0.1:{int(host_port)}')
            if state.get('Running'):
                ports = set(tcp_ports(state['Pid'])) & HTTP_PORTS
                for network in info.get('NetworkSettings', {}).get('Networks', {}).values():
                    address = network.get('IPAddress', '')
                    if re.fullmatch(r'(?:\d{1,3}\.){3}\d{1,3}', address):
                        urls.update(f'http://{address}:{p}' for p in ports)
        except Exception:
            emit('ARENA_CONTAINER', error='METADATA_UNAVAILABLE')
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(openapi, sorted(urls)[:16]):
            emit('ARENA_OPENAPI', **result)
    emit('ARENA_INVENTORY_COMPLETE', changed=False, probed_endpoints=min(len(urls), 16))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception:
        emit('ARENA_INVENTORY', error='INSPECTION_FAILED')
        raise SystemExit(1)
