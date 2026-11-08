[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Ndk,
    [string]$CMake = 'cmake',
    [string]$Ninja = 'ninja',
    [string]$Source = (Join-Path $PSScriptRoot 'upstream'),
    [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'arm64-v8a',
    [string]$BuildRoot = (Join-Path $PSScriptRoot 'build'),
    [int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$ndkRoot = [IO.Path]::GetFullPath($Ndk)
$toolchain = Join-Path $ndkRoot 'build/cmake/android.toolchain.cmake'
if (-not (Test-Path -LiteralPath $toolchain)) { throw 'NDK android.toolchain.cmake is missing.' }
$nativeBuild = Join-Path ([IO.Path]::GetFullPath($BuildRoot)) $Abi
$generated = Join-Path $nativeBuild 'generated'
& node (Join-Path $PSScriptRoot 'prepare.mjs') --source $Source --out $generated --abi $Abi
if ($LASTEXITCODE -ne 0) { throw 'Pinned source verification failed.' }
& $CMake -S $PSScriptRoot -B $nativeBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$Abi" '-DANDROID_PLATFORM=android-24' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release' "-DFAMILYGRAM_UPSTREAM=$Source" "-DFAMILYGRAM_GENERATED=$generated"
if ($LASTEXITCODE -ne 0) { throw 'Native CMake configuration failed.' }
& $CMake --build $nativeBuild --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Native compile/link failed.' }
$readelf = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
& node (Join-Path $PSScriptRoot 'verify-elf.mjs') --readelf $readelf --library (Join-Path $nativeBuild 'libfamilytgnet.so') --abi $Abi
if ($LASTEXITCODE -ne 0) { throw 'ELF isolation/security validation failed.' }
Write-Host "Built and validated: $nativeBuild/libfamilytgnet.so"
Write-Host 'Package only with the matching pinned original libtmessages.49.so and the matching libfamilytunnel.so.'
