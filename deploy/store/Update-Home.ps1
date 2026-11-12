[CmdletBinding()]
param(
    [ValidatePattern('\A[a-z_][a-z0-9_-]*@[a-zA-Z0-9.-]+\z')]
    [string]$SshTarget = 'root@157.228.191.231',
    [ValidatePattern('\Ademo_start_[a-f0-9]{8}\z')]
    [string]$DemoStartLogin,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$files = @('deploy/store/update-home.sh','web/local-store/pages.mjs','web/local-store/store.css','web/local-store/store.mjs',
    'i18n/ru.json','local-store/store.mjs','local-store/owner.mjs')
& node --test (Join-Path $repo 'web/local-store/pages.test.mjs') (Join-Path $repo 'local-store/store.test.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Store page tests failed.' }
if ($PlanOnly) { return }

$id = [Guid]::NewGuid().ToString('N')
$archive = Join-Path ([IO.Path]::GetTempPath()) "tg-ru-home-$id.tar.gz"
$remote = "/tmp/tg-ru-home-$id"
try {
    & tar.exe -czf $archive -C $repo @files
    if ($LASTEXITCODE -ne 0) { throw 'Store frontend packaging failed.' }
    $checksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    & scp.exe -- $archive "${SshTarget}:${remote}.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw 'Store frontend upload failed.' }
    $command = "set -eu; umask 077; printf '%s  %s\n' '$checksum' '$remote.tar.gz' | sha256sum -c --status -; mkdir '$remote'; tar -xzf '$remote.tar.gz' -C '$remote'; bash '$remote/deploy/store/update-home.sh'; rm -f -- '$remote.tar.gz'; rm -rf -- '$remote'"
    if ($DemoStartLogin) {
        $command += "; docker exec tg-ru-store-web-1 node local-store/owner.mjs grant '$DemoStartLogin' 30 --reason test --if-inactive || { echo 'Website updated; START demo grant failed.' >&2; exit 1; }"
    }
    & ssh.exe -t -- $SshTarget $command
    if ($LASTEXITCODE -ne 0) { throw 'Store homepage update failed. See the rollback diagnostics above.' }
    $page = & curl.exe -fsS --max-time 12 'https://tg.24projects.ru/'
    if ($LASTEXITCODE -ne 0 -or ($page -join "`n") -notmatch 'rel="icon" type="image/png"') {
        throw 'The new Store homepage could not be verified from this PC.'
    }
    Write-Host 'The updated homepage is reachable publicly. Only the Store web container was restarted.'
} finally {
    if (Test-Path -LiteralPath $archive -PathType Leaf) { Remove-Item -LiteralPath $archive -Force }
}
