[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$payloadPath = Join-Path $PSScriptRoot 'retry-staged.json'
$scriptPath = Join-Path $PSScriptRoot 'retry-staged.py'
$payload = [IO.File]::ReadAllBytes($payloadPath)
$metadata = [Text.Encoding]::UTF8.GetString($payload) | ConvertFrom-Json
if ($metadata.staging -notmatch '\A/tmp/familygram-[a-f0-9]{32}\z') { throw 'Invalid staged upload path.' }
$template = [IO.File]::ReadAllText($scriptPath)
if (-not $template.Contains('__FAMILYGRAM_RETRY_PAYLOAD__')) { throw 'Invalid retry template.' }
$remoteScript = $template.Replace('__FAMILYGRAM_RETRY_PAYLOAD__', [Convert]::ToBase64String($payload))
Write-Host "Reusing upload: $($metadata.staging)"
Write-Host 'Updates three verified public files; no RAM threshold or custom memory caps. No APK/runtime upload.'
if ($PlanOnly) { Write-Host 'LOCAL RETRY PLAN PASSED. No SSH connection or remote change.'; return }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw 'OpenSSH is required.' }
# ASCII Python source travels on stdin, avoiding Windows command-line length limits.
# Password entry stays in OpenSSH's normal console prompt; no credentials enter the script.
$remoteScript | & ssh -T -o ConnectTimeout=15 root@157.228.191.231 'python3 - 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Remote retry failed. Review the actual preflight/runtime diagnostics above.' }
