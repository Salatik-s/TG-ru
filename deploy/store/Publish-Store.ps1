[CmdletBinding()]
param(
    [string]$ApkPath,
    [ValidatePattern('\A[a-fA-F0-9]{64}\z')]
    [string]$ExpectedSha256,
    [ValidatePattern('\A[a-z_][a-z0-9_-]*@[a-zA-Z0-9.-]+\z')]
    [string]$SshTarget = 'root@157.228.191.231',
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $ApkPath) { $ApkPath = Join-Path $repo 'release/TG-ru-preview22-store-public.apk' }
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/37.0.0'
$java = Join-Path $env:LOCALAPPDATA 'Programs/Android Studio/jbr/bin/java.exe'
$aapt = Join-Path $sdk 'aapt.exe'
$apksigner = Join-Path $sdk 'lib/apksigner.jar'
$signerSha = 'e89c8af3771d781f6ab3beb63a6d3030b2926c770a96770475315040115b959a'
$hostName = 'tg.24projects.ru'
$paths = @(
    'deploy/store/install.sh','deploy/store/compose.yaml','deploy/store/route.py','deploy/store/verify.mjs',
    'server/store-gateway-main.mjs','server/store-gateway.mjs','server/gateway.mjs','server/egress.mjs','server/egress-probe.mjs',
    'local-store/main.mjs','local-store/database-path.mjs','local-store/http.mjs','local-store/store.mjs',
    'local-store/schema.sql','local-store/manual-grants.sql',
    'local-store/gateway-client.mjs','local-store/owner.mjs',
    'web/local-store/pages.mjs','web/local-store/store.css','web/local-store/store.mjs',
    'web/local-store/Inter-OFL.txt','web/local-store/inter-cyrillic.woff2','web/local-store/inter-latin.woff2','i18n/ru.json'
)
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
foreach ($file in @($apk,$java,$aapt,$apksigner)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file missing: $file" }
}
foreach ($relative in $paths) {
    if (-not (Test-Path -LiteralPath (Join-Path $repo $relative) -PathType Leaf)) {
        throw "Required Store source file missing: $relative"
    }
}
$verified = & $java -jar $apksigner verify --print-certs $apk
if ($LASTEXITCODE -ne 0 -or ($verified -join "`n") -notmatch "certificate SHA-256 digest: $signerSha(?:\r?\n|$)") {
    throw 'APK does not verify with the existing TG-ru signing certificate.'
}
$manifest = (& $aapt dump xmltree $apk AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0 -or $manifest -notmatch '="org\.telegram\.messenger\.family\.STORE"' -or
    $manifest -match 'org\.telegram\.messenger\.family\.STORE_LOCAL_QA') {
    throw 'A production Store APK without the loopback QA flag is required.'
}
$badging = (& $aapt dump badging $apk) -join "`n"
if ($LASTEXITCODE -ne 0 -or $badging -notmatch "(?m)^package: name='com\.salatik\.familygram' versionCode='(\d+)' versionName='([A-Za-z0-9._-]{1,64})'") {
    throw 'The APK package or version metadata is invalid.'
}
$versionCode = [int]$Matches[1]
$versionName = $Matches[2]
if ($versionCode -le 10018 -or $badging -notmatch "(?m)^sdkVersion:'(\d+)'") {
    throw 'The APK is not newer than the published legacy release or has no minimum Android SDK.'
}
$metadata = [ordered]@{
    version_name = $versionName
    version_code = $versionCode
    min_sdk = [int]$Matches[1]
    released_at = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
    bytes = (Get-Item -LiteralPath $apk).Length
    sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
}
if ($ExpectedSha256 -and $metadata.sha256 -ne $ExpectedSha256) { throw 'Store APK SHA-256 differs from the requested artifact.' }

Write-Host "Verified Store APK $versionName ($versionCode), SHA-256 $($metadata.sha256)."
Write-Host 'Publishes the Store website, console-managed access, and new APK on tg.24projects.ru.'
Write-Host 'The legacy APK, legacy update metadata and legacy gateway ports retain their existing routes.'
if ($PlanOnly) {
    Write-Host 'Local validation passed. Host access and publication are pending.'
    return
}
foreach ($command in @('ssh.exe','scp.exe','tar.exe','curl.exe')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Missing local tool: $command" }
}
$releaseId = [Guid]::NewGuid().ToString('N')
$tmpRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$staging = Join-Path $tmpRoot "tg-ru-store-$releaseId"
$archive = Join-Path $staging 'store.tar.gz'
$remote = "/tmp/tg-ru-store-$releaseId"
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    Copy-Item -LiteralPath $apk -Destination (Join-Path $staging 'TG-ru.apk')
    $json = $metadata | ConvertTo-Json -Compress
    [IO.File]::WriteAllText((Join-Path $staging 'store-release.json'), "$json`n", [Text.UTF8Encoding]::new($false))
    & tar.exe -czf $archive -C $repo @paths -C $staging TG-ru.apk store-release.json
    if ($LASTEXITCODE -ne 0) { throw 'Store release packaging failed.' }
    $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    & scp.exe -- $archive "${SshTarget}:${remote}.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw 'Store upload failed. Authenticate in the OpenSSH prompt.' }
    $remoteCommand = "set -eu; umask 077; printf '%s  %s\n' '$archiveHash' '$remote.tar.gz' | sha256sum -c --status -; mkdir '$remote'; tar -xzf '$remote.tar.gz' -C '$remote'; bash '$remote/deploy/store/install.sh' --install '$($metadata.sha256)'; rm -f -- '$remote.tar.gz'; rm -rf -- '$remote'"
    & ssh.exe -t -- $SshTarget $remoteCommand
    if ($LASTEXITCODE -ne 0) { throw "Store deployment failed. Uploaded release retained at $remote for review." }
    $site = & curl.exe -fsS --max-time 12 "https://$hostName/"
    if ($LASTEXITCODE -ne 0 -or ($site -join "`n") -notmatch 'href="/store\.css"') {
        throw 'The server-local deployment passed, but the public site did not return the Store frontend.'
    }
    $socket = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $socket.ConnectAsync($hostName, 28443)
        if (-not $pending.Wait(6000) -or -not $socket.Connected) {
            throw 'TCP connection failed.'
        }
    } catch {
        throw 'The Store gateway TCP port 28443 is unreachable externally. Check the hosting-provider firewall.'
    } finally {
        $socket.Dispose()
    }
    Write-Host 'Store website is reachable publicly and TCP 28443 is open. The legacy site routes were verified on the host.'
} finally {
    $resolved = (Resolve-Path -LiteralPath $staging).Path
    if ([IO.Path]::GetDirectoryName($resolved).TrimEnd('\','/') -eq $tmpRoot.TrimEnd('\','/') -and
        [IO.Path]::GetFileName($resolved) -eq "tg-ru-store-$releaseId") {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
