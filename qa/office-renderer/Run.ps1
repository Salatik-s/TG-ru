$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$java='C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin'
$classes=Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& "$java/javac.exe" --release 17 -encoding UTF-8 -d $classes "$root/overlay/java/org/telegram/messenger/family/FamilyOfficeRenderer.java" "$PSScriptRoot/OfficeRendererTest.java"
if($LASTEXITCODE -ne 0){throw 'Renderer fixtures did not compile'}
& "$java/java.exe" -cp "$classes;$root/tools/docs-jars/*" OfficeRendererTest "$root/qa/saved-media/fixtures"
if($LASTEXITCODE -ne 0){throw 'Renderer fixture failed'}
