[CmdletBinding()]
param(
    [string]$InputApk = "$PSScriptRoot/work-pinned-v1/FamilyGram-contingency-UNSIGNED-UNVERIFIED.apk",
    [string]$OutputApk = "$PSScriptRoot/../release/FamilyGram-0.1.0-preview.apk"
)
$ErrorActionPreference = 'Stop'
$jdk = 'C:/Users/andre/AppData/Local/Programs/Android Studio/jbr'
$buildTools = 'C:/Users/andre/AppData/Local/Android/Sdk/build-tools/37.0.0'
$privateDirectory = 'C:/Users/andre/.codex/private/familygram-release'
$settingsPath = Join-Path $privateDirectory 'signing.json'
$keystorePath = Join-Path $privateDirectory 'familygram-release.jks'
if (!(Test-Path -LiteralPath $InputApk -PathType Leaf)) { throw 'Unsigned APK not found.' }
New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $privateDirectory /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the private signing directory.' }
if (!(Test-Path -LiteralPath $settingsPath)) {
    if (Test-Path -LiteralPath $keystorePath) { throw 'Existing signing key has no settings; refusing to replace it.' }
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    @{ alias = 'familygram'; password = [Convert]::ToBase64String($random) } | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding ascii
}
$settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
$savedPassword = $env:FAMILYGRAM_KEYSTORE_PASS
try {
    $env:FAMILYGRAM_KEYSTORE_PASS = $settings.password
    if (!(Test-Path -LiteralPath $keystorePath)) {
        & "$jdk/bin/keytool.exe" -genkeypair -keystore $keystorePath -storetype JKS -alias $settings.alias -storepass:env FAMILYGRAM_KEYSTORE_PASS -keypass:env FAMILYGRAM_KEYSTORE_PASS -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=FamilyGram, O=FamilyGram, C=RU' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $OutputApk) -Force | Out-Null
    & "$jdk/bin/java.exe" -jar "$buildTools/lib/apksigner.jar" sign --ks $keystorePath --ks-key-alias $settings.alias --ks-pass env:FAMILYGRAM_KEYSTORE_PASS --key-pass env:FAMILYGRAM_KEYSTORE_PASS --min-sdk-version 24 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $OutputApk $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & "$jdk/bin/java.exe" -jar "$buildTools/lib/apksigner.jar" verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & "$buildTools/zipalign.exe" -c -P 16 4 $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment validation failed.' }
    Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256 | Select-Object Hash
    Write-Output "Signed APK: $OutputApk"
    Write-Output 'Keep the private signing directory: future updates require the same key. It is never packaged with the APK.'
} finally {
    $env:FAMILYGRAM_KEYSTORE_PASS = $savedPassword
}
