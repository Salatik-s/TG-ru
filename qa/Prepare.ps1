[CmdletBinding()]
param([string]$OpenSSL = 'C:/Program Files/Git/usr/bin/openssl.exe')
$ErrorActionPreference = 'Stop'
$privateDir = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex/private/familygram-qa'
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls $privateDir /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict QA private directory ACL.' }
& node (Join-Path $PSScriptRoot 'prepare.mjs') --openssl $OpenSSL
if ($LASTEXITCODE -ne 0) { throw 'QA TLS preparation failed.' }
