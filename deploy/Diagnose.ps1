[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'diagnose.mjs'))
$encoded = [Convert]::ToBase64String($source)
$dispatcher = @'
import base64, os, pathlib, re, stat, subprocess, sys
def safe(text):
    lines=[]
    for line in text.splitlines():
        if re.search(r'(?i)authorization|bearer|token|secret|password|private.?key|public.?key|short.?id|cookie|session|invitation|phone|uuid|BEGIN .*KEY|["\x27]code["\x27]\s*:|\b(code|otp)\s*[:=]', line):
            lines.append('[sensitive log line omitted]'); continue
        line=re.sub(r'/v1/push/[^\s"\x27]+','/v1/push/[redacted]',line)
        line=re.sub(r'(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b','[id]',line)
        line=re.sub(r'\b[A-Za-z0-9_+/=-]{30,}\b','[opaque value]',line)
        line=re.sub(r'\+?\b\d{5,}\b','[number]',line)
        lines.append(line[:800])
    return '\n'.join(lines)
def run(args, timeout=8, data=None):
    try:
        r=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout)
        return r.returncode,r.stdout
    except subprocess.TimeoutExpired:
        return 124,'Command timed out; no server changes were made.'
if os.geteuid()!=0: raise SystemExit('Root SSH session required for read-only Docker diagnostics.')
root=pathlib.Path('/opt/familygram')
if root.is_symlink() or (root/'.familygram-owned').read_text().strip()!='familygram-v1': raise SystemExit('FamilyGram ownership marker is missing.')
current=(root/'current').resolve(strict=True)
if not current.is_relative_to(root/'releases') or current==root/'releases': raise SystemExit('Unexpected current release path.')
if current.stat().st_uid!=0: raise SystemExit('Unexpected release owner.')
compose=['docker','compose','--project-directory',str(current/'deploy'),'--env-file',str(current/'deploy/.env'),'-f',str(current/'deploy/compose.yaml')]
print('Recent FamilyGram service state:',flush=True)
code,output=run(compose+['ps','-a']);print(safe(output),flush=True)
print('Existing 24projects VPN service inventory (name and status only):',flush=True)
code,output=run(['docker','ps','-a','--filter','label=com.docker.compose.project=24projects','--filter','label=com.docker.compose.service=vpn','--format','{{.Names}}: {{.Status}}'])
print(safe(output) if output.strip() else 'No matching VPN container found.',flush=True)
print('Recent 15-minute FamilyGram logs (last 100 lines per service; sensitive lines omitted):',flush=True)
code,output=run(compose+['logs','--since','15m','--tail','100','--no-color']);print(safe(output),flush=True)
code,output=run(compose+['ps','-q','access'])
container=output.strip()
if code or not re.fullmatch('[a-f0-9]{12,64}',container): raise SystemExit('No unique running FamilyGram access container; inspect service state above.')
code,label=run(['docker','inspect','-f','{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.service"}}',container])
if code or label.strip()!='familygram access': raise SystemExit('Container scope verification failed.')
print('Configuration, direct probes (six seconds) and server-chain probe (up to 20 seconds):',flush=True)
source=base64.b64decode('__NODE_SOURCE__').decode('utf-8')
code,output=run(['docker','exec','-i',container,'node','--input-type=module','-','--familygram-run'],timeout=40,data=source)
# The embedded Node program emits only fixed labels, booleans, counts and allowlisted error codes.
print(output,flush=True)
sys.exit(code)
'@
$dispatcher = $dispatcher.Replace('__NODE_SOURCE__', $encoded)
Write-Host 'Reads recent FamilyGram logs and checks direct Telegram connectivity. No configuration changes or account data.'
if ($PlanOnly) { Write-Host 'LOCAL DIAGNOSTIC PLAN PASSED. No SSH or network probes.'; return }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw 'OpenSSH is required.' }
$dispatcher | & ssh -T -o ConnectTimeout=15 root@157.228.191.231 'python3 - 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic command could not finish; review the sanitized output above.' }
