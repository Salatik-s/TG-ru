[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'inspect-arena-whisper.py'))
if ($PlanOnly) { Write-Host 'ARENA WHISPER INVENTORY PLAN OK: read-only service metadata, model presence and OpenAPI route names. No SSH.'; return }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw 'OpenSSH is required.' }
Write-Host 'Read-only Arena/Whisper inventory. No credentials, .env, database contents, logs or process arguments are printed.'
$source | & ssh -T -o ConnectTimeout=15 root@157.228.191.231 'python3 - 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Arena inventory could not finish; only fixed errors and public service metadata are shown above.' }
