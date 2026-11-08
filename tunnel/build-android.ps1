param(
    [string]$Ndk = "$PSScriptRoot\..\tools\ndk-subset\android-ndk-r27c",
    [string]$Go = "$PSScriptRoot\..\tools\go-verified\go\bin\go.exe",
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')][string]$Abi = 'arm64-v8a'
)
$ErrorActionPreference = 'Stop'
$compilerRoot = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$arch = @{ 'arm64-v8a' = 'arm64'; 'armeabi-v7a' = 'arm'; 'x86_64' = 'amd64' }[$Abi]
$target = @{ 'arm64-v8a' = 'aarch64-linux-android'; 'armeabi-v7a' = 'armv7a-linux-androideabi'; 'x86_64' = 'x86_64-linux-android' }[$Abi]
$compiler = Join-Path $compilerRoot 'clang.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw "Missing NDK compiler: $compiler" }
if (-not (Test-Path -LiteralPath $Go)) { throw "Missing Go toolchain: $Go" }
$Go = (Resolve-Path -LiteralPath $Go).Path
$out = Join-Path $PSScriptRoot "out\$Abi"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$saved = @{}
foreach ($name in @('GOOS','GOARCH','GOARM','CGO_ENABLED','CGO_LDFLAGS','CC','GOCACHE','GOMODCACHE')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:GOOS = 'android'
    $env:GOARCH = $arch
    $env:CGO_ENABLED = '1'
    $env:CC = '"' + (Resolve-Path -LiteralPath $compiler).Path + '" --target=' + $target + '24'
    $env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
    if ($arch -eq 'arm') { $env:GOARM = '7' }
    $env:GOCACHE = Join-Path $PSScriptRoot '..\tools\go-cache'
    $env:GOMODCACHE = Join-Path $PSScriptRoot '..\tools\go-mod'
    Push-Location $PSScriptRoot
    try {
        & $Go build -trimpath -buildmode=c-shared -ldflags='-s -w -buildid= -checklinkname=0' -o "$out\libfamilytunnel.so" ./cmd/fgtransport
        if ($LASTEXITCODE -ne 0) { throw "Go Android build failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
Get-Item "$out\libfamilytunnel.so", "$out\libfamilytunnel.h"
