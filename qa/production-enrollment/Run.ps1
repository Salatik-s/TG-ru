[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5582',
    [string]$InviteFile = 'C:/Users/andre/.codex/private/familygram-test-access/invite.json',
    [switch]$Resume
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This runner only targets the task-owned emulator.' }
$adb = 'C:/Users/andre/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$apk = Join-Path $PSScriptRoot 'Production-Enrollment.apk'
$remote = '/sdcard/Android/data/com.salatik.familygram/files/production-invite.json'
if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Build and sign the separate instrumentation APK first.' }
if (!$Resume -and !(Test-Path -LiteralPath $InviteFile -PathType Leaf)) { throw 'Private one-use invitation file is missing.' }
& $adb -s $Serial shell am force-stop com.salatik.familygram
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the test application.' }
& $adb -s $Serial install -r -t $apk
if ($LASTEXITCODE -ne 0) { throw 'Could not install the separate instrumentation APK.' }
try {
    if (!$Resume) {
        & $adb -s $Serial shell mkdir -p /sdcard/Android/data/com.salatik.familygram/files
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare scoped test input.' }
        & $adb -s $Serial push $InviteFile $remote | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not transfer the private invitation.' }
    }
    & $adb -s $Serial shell am instrument -w -r -e invite_file $remote com.salatik.familygram.productionqa/com.salatik.familygram.productionqa.ProductionEnrollment
    if ($LASTEXITCODE -ne 0) { throw 'Instrumentation process failed.' }
} finally {
    & $adb -s $Serial shell rm -f $remote | Out-Null
}
