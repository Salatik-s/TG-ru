[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$payload = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'owner-repair.json'))
$metadata = [Text.Encoding]::UTF8.GetString($payload) | ConvertFrom-Json
if ($metadata.staging -notmatch '\A/tmp/familygram-[a-f0-9]{32}\z' -or $metadata.phone_owner_id -notmatch '\A[a-f0-9]{64}\z') { throw 'Invalid verified installation binding.' }
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'repair-owner.py'))
if (-not $template.Contains('__FAMILYGRAM_OWNER_PAYLOAD__')) { throw 'Invalid owner repair template.' }
$remoteScript = $template.Replace('__FAMILYGRAM_OWNER_PAYLOAD__', [Convert]::ToBase64String($payload))
Write-Host 'Enables phone device management only for the verified installation on your connected phone.'
Write-Host 'Uses signed one-use proofs, preserves WSS/VPN/device approvals and reuses the existing runtime upload.'
if ($PlanOnly) { Write-Host 'LOCAL OWNER REPAIR PLAN PASSED. No SSH connection or remote change.'; return }
$remoteScript | & ssh -T -o ConnectTimeout=15 -o ServerAliveInterval=20 -o ServerAliveCountMax=6 root@157.228.191.231 'python3 - 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Owner setup failed; review actual diagnostics above.' }
