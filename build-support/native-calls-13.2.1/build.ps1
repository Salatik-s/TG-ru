param([ValidateSet('arm64-v8a','x86_64')][string]$Abi='x86_64',[string]$WorkspaceRoot=(Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference='Stop'
$source=$PSScriptRoot
$toolbin=Join-Path $WorkspaceRoot 'tools/ndk-subset/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/bin'
$cc=Join-Path $toolbin 'clang.exe'
$cxx=Join-Path $toolbin 'clang++.exe'
$target=@{'arm64-v8a'='aarch64-linux-android24';'x86_64'='x86_64-linux-android24'}[$Abi]
$objects=Join-Path $source "build-direct/$Abi"
$out=Join-Path $source "out/$Abi"
New-Item -ItemType Directory -Force $objects,$out | Out-Null
$common=@("--target=$target",'-O2','-D_GNU_SOURCE','-DANDROID','-fPIC','-fvisibility=hidden','-ffunction-sections','-fdata-sections',"-I$source/third_party/xhook/libxhook/jni")
function Compile([string]$name,[switch]$Strict){
    $inputPath=Join-Path $source $name
    $outputPath=Join-Path $objects (($name -replace '[/\\]','_')+'.o')
    $compiler=$cc
    $flags=$common
    if($name.EndsWith('.cc')){$compiler=$cxx;$flags+=@('-std=c++17')}
    if($Strict){$flags+=@('-Wall','-Wextra','-Werror')}
    & $compiler @flags -c $inputPath -o $outputPath
    if($LASTEXITCODE -ne 0){throw "Compile failed: $name"}
    return $outputPath
}
$adapter=Compile 'adapter.cc' -Strict
$gate=Compile 'syscall_gate.S'
$install=Compile 'install.cc' -Strict
$hookObjects=@()
foreach($name in @('xhook','xh_core','xh_elf','xh_log','xh_util','xh_version')){$hookObjects+=Compile "third_party/xhook/libxhook/jni/$name.c"}
$link=@("--target=$target",'-static-libstdc++','-Wl,-z,max-page-size=16384','-Wl,--gc-sections','-Wl,-Bsymbolic','-ldl','-llog','-latomic')
& $cxx @link "-Wl,--version-script=$source/exports.map" -shared $adapter $gate $install @hookObjects -o "$out/libfamilycalls.so"
if($LASTEXITCODE -ne 0){throw 'Adapter link failed'}
$test=Compile 'tests/adapter_smoke.cc' -Strict
& $cxx @link $adapter $gate $test -o "$out/fgcalls-adapter-smoke"
if($LASTEXITCODE -ne 0){throw 'Smoke link failed'}
$audit=Compile 'tests/audit.cc' -Strict
& $cxx @link $audit -o "$out/fgcalls-audit"
if($LASTEXITCODE -ne 0){throw 'Audit link failed'}
Get-Item "$out/libfamilycalls.so","$out/fgcalls-adapter-smoke","$out/fgcalls-audit"
