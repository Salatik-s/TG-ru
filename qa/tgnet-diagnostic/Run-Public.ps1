[CmdletBinding()]
param(
  [ValidateSet('public-fqdn','public-ip','both')][string]$Mode='both',
  [string]$Profile='C:/Users/andre/.codex/private/familygram-network-debug/profile.json'
)
$ErrorActionPreference='Stop'
$adb='C:/Users/andre/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$serial='emulator-5582'
$remote='/sdcard/Android/data/com.salatik.familygram/files/network-debug/profile.json'
if(!(Test-Path -LiteralPath $Profile -PathType Leaf)){throw 'Owner diagnostic profile is not available.'}
if((Get-Item -LiteralPath $Profile).Length -gt 16384){throw 'Diagnostic profile is oversized.'}
$emulated=(& $adb -s $serial shell getprop ro.kernel.qemu).Trim()
if($emulated -ne '1'){throw 'Only the task-owned emulator may run this diagnostic.'}
$variants=if($Mode -eq 'both'){@('public-fqdn','public-ip')}else{@($Mode)}
foreach($variant in $variants){
  try{
    & $adb -s $serial shell am force-stop com.salatik.familygram
    & $adb -s $serial shell mkdir -p /sdcard/Android/data/com.salatik.familygram/files/network-debug
    # adb push attempts fchown on scoped FUSE storage on some Android images.
    # Stream via non-PTY stdin instead: profile never enters argv or tool output.
    Get-Content -Raw -LiteralPath $Profile | & $adb -s $serial shell -T "cat > $remote"
    if($LASTEXITCODE -ne 0){throw 'Could not stage diagnostic profile.'}
    & $adb -s $serial shell am instrument -w -e mode $variant com.salatik.familygram.tgnetqa/com.salatik.familygram.tgnetqa.TgnetInstrumentation
    if($LASTEXITCODE -ne 0){throw 'Instrumentation command failed.'}
  }finally{
    & $adb -s $serial shell rm -f $remote
    & $adb -s $serial shell am force-stop com.salatik.familygram
  }
}
