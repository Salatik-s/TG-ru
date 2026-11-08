[CmdletBinding()]
param(
    [Parameter(Mandatory=$true,Position=0)][ValidateSet('invite','list','approve','revoke')][string]$Command,
    [Parameter(Position=1)][string]$DeviceId,
    [ValidateRange(1,720)][int]$Hours=24,
    [string]$SshTarget='root@157.228.191.231',
    [switch]$PlanOnly
)
$ErrorActionPreference='Stop'
if ($SshTarget -notmatch '\A[a-z_][a-z0-9_-]*@[a-zA-Z0-9.-]+\z') { throw 'Invalid SSH target.' }
$Command=$Command.ToLowerInvariant()
if ($Command -in @('approve','revoke')) {
    if ($DeviceId -notmatch '\A[a-fA-F0-9]{64}\z') { throw 'Use the full 64-character device ID from list, not the short fingerprint.' }
    $argument=$DeviceId.ToLowerInvariant()
} elseif ($DeviceId) { throw 'Device ID is only accepted for approve/revoke.' }
elseif ($Command -eq 'invite') { $argument=[string]$Hours }
else { $argument='' }
$remote="cd /opt/familygram/current/deploy && docker compose exec -T access node owner.mjs $Command $argument"
if ($PlanOnly) { Write-Output "Plan only: $SshTarget -> $remote"; return }
& ssh -- $SshTarget $remote
if ($LASTEXITCODE -ne 0) { throw 'Owner command failed. Review SSH/server output; no password is stored.' }
