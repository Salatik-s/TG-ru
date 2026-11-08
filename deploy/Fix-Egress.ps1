[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$payload = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'egress-repair.json'))
$metadata = [Text.Encoding]::UTF8.GetString($payload) | ConvertFrom-Json
if ($metadata.staging -notmatch '\A/tmp/familygram-[a-f0-9]{32}\z') { throw 'Invalid staged upload path.' }
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'repair-egress.py'))
if (-not $template.Contains('__FAMILYGRAM_EGRESS_PAYLOAD__')) { throw 'Invalid repair template.' }
$remoteScript = $template.Replace('__FAMILYGRAM_EGRESS_PAYLOAD__', [Convert]::ToBase64String($payload))
Write-Host 'Repairs FamilyGram egress using saved native VPN profiles; regular locations first, BS only as fallback.'
Write-Host 'Reuses the previous upload. Existing VPN configuration and subscription identities are preserved.'
if ($PlanOnly) { Write-Host 'LOCAL EGRESS REPAIR PLAN PASSED. No SSH connection or remote change.'; return }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw 'OpenSSH is required.' }
$remoteScript | & ssh -T -o ConnectTimeout=15 -o ServerAliveInterval=20 -o ServerAliveCountMax=6 root@157.228.191.231 'python3 - 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'FamilyGram VPN egress repair failed; review the actual import/startup/MTProto result above.' }
