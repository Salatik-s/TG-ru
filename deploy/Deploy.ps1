[CmdletBinding()]
param(
    [switch]$PlanOnly,
    [switch]$PauseVoiceRooms,
    [string]$SshTarget = 'root@157.228.191.231'
)
$ErrorActionPreference = 'Stop'
if ($SshTarget -notmatch '\A[a-z_][a-z0-9_-]*@[a-zA-Z0-9.-]+\z') { throw 'Invalid SSH target.' }
$familygramRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
foreach ($required in @('server/package.json','server/access.mjs','server/main.mjs','server/gateway.mjs','server/egress.mjs','server/egress-probe.mjs','server/vpn-nodes.mjs','server/vpn-snapshot.mjs','server/http.mjs','server/push.mjs','server/owner.mjs','runtime/xray','runtime/xray.sha256','deploy/compose.yaml','deploy/nginx.conf','deploy/install.sh','deploy/pause-voicerooms.sh','deploy/import-vpn.py','deploy/verify-egress.mjs','deploy/Manage.ps1')) {
    if (-not (Test-Path -LiteralPath (Join-Path $familygramRoot $required) -PathType Leaf)) { throw "Missing release file: $required" }
}
$expectedXrayHash = ((Get-Content -LiteralPath (Join-Path $familygramRoot 'runtime/xray.sha256') -Raw).Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath (Join-Path $familygramRoot 'runtime/xray') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedXrayHash) { throw 'Linux Xray runtime checksum mismatch.' }
foreach ($command in @('node','ssh','scp','tar')) { if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Missing prerequisite: $command" } }
Push-Location (Join-Path $familygramRoot 'server')
try { & node --test; if ($LASTEXITCODE -ne 0) { throw 'Server security tests failed.' } } finally { Pop-Location }
Write-Host "Target: $SshTarget; API: https://24projects.ru:8443; VLESS REALITY: TCP 18443"
Write-Host 'Scope: /opt/familygram, its Compose containers, and its certificate renewal hook.'
Write-Host 'Existing sites/VPNs, host nginx, ports 80/443, volumes and existing firewall rules are preserved.'
Write-Host 'No RAM admission threshold or custom container/heap memory limits. Prebuilt runtimes; no server-side build.'
if ($PauseVoiceRooms) { Write-Host 'Requested: verify and pause only VoiceRooms containers before FamilyGram preflight. VoiceRooms data is retained.' }
Write-Host 'If UFW is active, only missing TCP 8443/18443 allow rules tagged familygram are added. Provider firewall remains separate.'
if ($PlanOnly) {
    Write-Host 'LOCAL PLAN PASSED. No SSH connection, upload, remote command or deployment was performed.'
    Write-Host 'The real run performs read-only remote preflight before installation; certificates/ports/Docker remain unverified locally.'
    return
}
$releaseId = [Guid]::NewGuid().ToString('N')
$localArchive = Join-Path ([IO.Path]::GetTempPath()) "familygram-$releaseId.tar.gz"
$remoteArchive = "/tmp/familygram-$releaseId.tar.gz"
$remoteFolder = "/tmp/familygram-$releaseId"
try {
    $sourceFiles = @('server/package.json','server/access.mjs','server/main.mjs','server/gateway.mjs','server/egress.mjs','server/egress-probe.mjs','server/vpn-nodes.mjs','server/vpn-snapshot.mjs','server/http.mjs','server/push.mjs','server/owner.mjs','server/README.md','server/test/access.test.mjs','server/test/gateway.integration.test.mjs','server/test/egress.test.mjs','server/test/egress-probe.test.mjs','server/test/vpn-snapshot.test.mjs','server/test/push.test.mjs','server/test/deploy.test.mjs','runtime/xray','runtime/xray.sha256','runtime/README.md','runtime/LICENSE-Xray.txt','deploy/compose.yaml','deploy/nginx.conf','deploy/install.sh','deploy/pause-voicerooms.sh','deploy/import-vpn.py','deploy/verify-egress.mjs','deploy/Deploy.ps1','deploy/Manage.ps1')
    & tar -czf $localArchive -C $familygramRoot @sourceFiles
    if ($LASTEXITCODE -ne 0) { throw 'Release packaging failed.' }
    & scp -- $localArchive "${SshTarget}:$remoteArchive"
    if ($LASTEXITCODE -ne 0) { throw 'Upload failed. Check SSH authentication; passwords are never stored by this script.' }
    # All interpolated remote values are generated GUID paths, never arbitrary shell input.
    $pauseCommand = ''
    if ($PauseVoiceRooms) { $pauseCommand = "bash '$remoteFolder/deploy/pause-voicerooms.sh' --pause; " }
    $remoteCommand = "set -eu; umask 077; mkdir '$remoteFolder'; tar -xzf '$remoteArchive' -C '$remoteFolder'; ${pauseCommand}bash '$remoteFolder/deploy/install.sh' --plan; bash '$remoteFolder/deploy/install.sh' --install"
    & ssh -t -- $SshTarget $remoteCommand
    if ($LASTEXITCODE -ne 0) { throw "Deployment failed; review output. Uploaded source remains at $remoteFolder for diagnosis." }
    Write-Host 'Deployment and server-local TLS health check passed. Verify access from the Android network.'
} finally {
    if (Test-Path -LiteralPath $localArchive) { Remove-Item -LiteralPath $localArchive -Force }
}
