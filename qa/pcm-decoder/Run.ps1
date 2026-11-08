$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$java='C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin'
$android='C:/Users/andre/AppData/Local/Android/Sdk/platforms/android-35/android.jar'
$classes=Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& "$java/javac.exe" --release 17 -encoding UTF-8 -classpath $android -d $classes "$root/overlay/java/org/telegram/messenger/family/FamilyPcmDecoder.java" "$PSScriptRoot/PcmDecoderTest.java"
if($LASTEXITCODE -ne 0){throw 'PCM fixtures did not compile'}
& "$java/java.exe" -cp "$classes;$android" org.telegram.messenger.family.PcmDecoderTest
if($LASTEXITCODE -ne 0){throw 'PCM fixture failed'}
