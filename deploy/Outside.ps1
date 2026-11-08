[CmdletBinding()]
param([switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$privateDir = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex/private/familygram-network-debug'
$module = Join-Path $PSScriptRoot 'outside-diagnostics.mjs'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required.' }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw 'OpenSSH is required.' }
if ($PlanOnly) {
    & node $module --plan
    if ($LASTEXITCODE -ne 0) { throw 'Outside diagnostic validation failed.' }
    return
}
# Reject redirected directories before placing a reusable device credential there.
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
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $privateDir -AclObject $acl
$env:FAMILYGRAM_OUTSIDE_PRIVATE_DIR = $privateDir
try {
    & node $module --run
    if ($LASTEXITCODE -ne 0) { throw 'Outside diagnostic failed; review the fixed status code above.' }
} finally {
    Remove-Item Env:FAMILYGRAM_OUTSIDE_PRIVATE_DIR -ErrorAction SilentlyContinue
}
