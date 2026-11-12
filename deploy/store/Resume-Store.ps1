[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('\A[a-f0-9]{32}\z')]
    [string]$StagingId,
    [Parameter(Mandatory)]
    [ValidatePattern('\A[a-f0-9]{64}\z')]
    [string]$ExpectedSha256,
    [ValidatePattern('\A[a-z_][a-z0-9_-]*@[a-zA-Z0-9.-]+\z')]
    [string]$SshTarget = 'root@157.228.191.231',
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$remote = "/tmp/tg-ru-store-$StagingId"
$steps = @(
    'set -euo pipefail',
    'umask 077',
    "test -d '$remote'",
    "test ! -L '$remote'",
    "test `$(readlink -f '$remote') = '$remote'",
    "test -f '$remote/TG-ru.apk'",
    "test ! -L '$remote/TG-ru.apk'",
    "printf '%s  %s\n' '$ExpectedSha256' '$remote/TG-ru.apk' | sha256sum -c --status -",
    "test ! -L '$remote/deploy'",
    "test ! -L '$remote/deploy/store'",
    "test ! -L '$remote/local-store'"
)

foreach ($relative in 'local-store/schema.sql','local-store/manual-grants.sql','deploy/store/install.sh') {
    $local = Join-Path $repo $relative
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($local))
    $digest = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
    $target = "$remote/$relative"
    $steps += "test ! -L '$target'"
    $steps += "tmp=`$(mktemp '$target.retry.XXXXXXXX')"
    $steps += "printf %s '$base64' | base64 -d > `$tmp"
    $steps += "printf '%s  %s\n' '$digest' `$tmp | sha256sum -c --status -"
    $steps += "mv -Tf -- `$tmp '$target'"
}

$steps += "bash '$remote/deploy/store/install.sh' --install '$ExpectedSha256'"
$steps += "rm -f -- '$remote.tar.gz'"
$steps += "rm -rf -- '$remote'"
$body = $steps -join "`n"
$bytes = [Text.Encoding]::UTF8.GetBytes($body)
$buffer = [IO.MemoryStream]::new()
try {
    $zip = [IO.Compression.GZipStream]::new($buffer, [IO.Compression.CompressionLevel]::Optimal, $true)
    try { $zip.Write($bytes, 0, $bytes.Length) } finally { $zip.Dispose() }
    $payload = [Convert]::ToBase64String($buffer.ToArray())
} finally { $buffer.Dispose() }
$remoteCommand = "set -o pipefail; printf %s '$payload' | base64 -d | gzip -d | bash"
Write-Host 'Verifies the uploaded APK, adds the two database migrations and retries Store. No APK upload.'
if ($PlanOnly) {
    $bash = Join-Path $env:ProgramFiles 'Git/bin/bash.exe'
    if (-not (Test-Path -LiteralPath $bash -PathType Leaf)) { throw 'Git Bash is required to validate the remote retry command.' }
    $check = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($check, $body, [Text.UTF8Encoding]::new($false))
        & $bash -n $check
        if ($LASTEXITCODE -ne 0) { throw 'Store retry command did not pass the shell syntax check.' }
    } finally { Remove-Item -LiteralPath $check -Force }
    Write-Host 'Retry command syntax verified. Host unchanged.'
    return
}
& ssh.exe -t -- $SshTarget $remoteCommand
if ($LASTEXITCODE -ne 0) { throw "Store retry failed. Uploaded release retained at $remote for review." }
