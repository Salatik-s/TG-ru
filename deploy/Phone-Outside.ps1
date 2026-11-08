[CmdletBinding()]
param(
    [switch]$PlanOnly,
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'),
    [string]$XrayBinary = (Join-Path $PSScriptRoot '../runtime/xray-windows.exe'),
    [string]$Component = 'com.salatik.familygram.profilediag/com.salatik.familygram.profilediag.ProfileDiagnostic'
)
$ErrorActionPreference = 'Stop'
$module = Join-Path $PSScriptRoot 'phone-outside.mjs'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required.' }
if ($PlanOnly) {
    & node $module --plan --adb $Adb --binary $XrayBinary --component $Component
    if ($LASTEXITCODE -ne 0) { throw 'Phone outside diagnostic validation failed.' }
    return
}
$privateDir = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex/private/familygram-network-debug'
$walk = $privateDir
while ($walk) {
    if (Test-Path -LiteralPath $walk) {
        $item = Get-Item -LiteralPath $walk -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Private diagnostic directory must not use links.' }
    }
    $parent = Split-Path -Parent $walk
    if ($parent -eq $walk) { break }
    $walk = $parent
}
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($identity in @($sid, (New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))) {
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
}
Set-Acl -LiteralPath $privateDir -AclObject $acl
$env:FAMILYGRAM_OUTSIDE_PRIVATE_DIR = $privateDir
try {
    & node $module --run --adb $Adb --binary $XrayBinary --component $Component
    if ($LASTEXITCODE -ne 0) { throw 'Phone outside diagnostic failed; review the fixed result code.' }
} finally {
    Remove-Item Env:FAMILYGRAM_OUTSIDE_PRIVATE_DIR -ErrorAction SilentlyContinue
}
