[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$payload = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'transport-repair.json'))
$metadata = [Text.Encoding]::UTF8.GetString($payload) | ConvertFrom-Json
if ($metadata.staging -notmatch '\A/tmp/familygram-[a-f0-9]{32}\z') { throw 'Invalid staged upload path.' }
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'repair-transport.py'))
if (-not $template.Contains('__FAMILYGRAM_TRANSPORT_PAYLOAD__')) { throw 'Invalid transport repair template.' }
$remoteScript = $template.Replace('__FAMILYGRAM_TRANSPORT_PAYLOAD__', [Convert]::ToBase64String($payload))
Write-Host 'Enables authenticated WSS through the existing trusted HTTPS entry point on TCP8443.'
Write-Host 'Preserves existing REALITY, device approvals and VPN profiles; reuses the previous runtime upload.'
if ($PlanOnly) { Write-Host 'LOCAL TRANSPORT REPAIR PLAN PASSED. No SSH connection or remote change.'; return }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw 'OpenSSH is required.' }
$remoteScript | & ssh -T -o ConnectTimeout=15 -o ServerAliveInterval=20 -o ServerAliveCountMax=6 root@157.228.191.231 'python3 - 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Transport repair failed; review actual nginx/WSS/MTProto diagnostics above.' }
