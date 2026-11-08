[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$privateDir = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex/private/familygram-test-access'
if ($PlanOnly) { Write-Host 'Creates one private test invitation through the owner SSH session. No remote action in plan mode.'; return }
if (Test-Path -LiteralPath $privateDir) {
    if ((Get-Item -LiteralPath $privateDir -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected test access directory link.' }
}
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls $privateDir /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the private test directory.' }
& node (Join-Path $PSScriptRoot 'test-access.mjs') $privateDir
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare a test invitation; no invitation was printed.' }
