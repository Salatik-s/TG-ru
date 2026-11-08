[CmdletBinding()]
param([switch]$CheckOnly, [switch]$SelfTest)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Test-ApiValue([Security.SecureString]$Value, [string]$Kind) {
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ($Kind -eq 'id') {
            return ($plain -cmatch '^[1-9][0-9]{0,9}$' -and [long]$plain -lt 2147483647)
        }
        return ($plain -cmatch '^[a-fA-F0-9]{32}$' -and $plain -ne ('0' * 32))
    } finally {
        $plain = $null
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
}

if ($SelfTest) {
    $sample = ConvertTo-SecureString ('a' * 32) -AsPlainText -Force
    try {
        $cipher = ConvertFrom-SecureString $sample
        $roundtrip = ConvertTo-SecureString $cipher
        try {
            if (-not (Test-ApiValue $roundtrip 'hash')) { throw 'DPAPI roundtrip failed' }
            if ($cipher.Contains('a' * 32)) { throw 'Plaintext persistence detected' }
        } finally { $roundtrip.Dispose() }
    } finally { $sample.Dispose() }
    Write-Output 'DPAPI_SELF_TEST_OK: synthetic values only; no files written'
    exit 0
}

$profileRoot = [IO.Path]::GetFullPath($env:USERPROFILE).TrimEnd('\')
$targetDir = [IO.Path]::GetFullPath((Join-Path $profileRoot '.codex/private/tg-ru-ios'))
if (-not $targetDir.StartsWith($profileRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Private directory leaves user profile'
}
$walk = $targetDir
while ($walk -and $walk.Length -ge $profileRoot.Length) {
    if (Test-Path -LiteralPath $walk) {
        if (((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Private path must not contain links or junctions'
        }
    }
    if ($walk -eq $profileRoot) { break }
    $walk = Split-Path -Parent $walk
}
$path = Join-Path $targetDir 'telegram-api.dpapi.json'
if (Test-Path -LiteralPath $path) {
    if (((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Encrypted configuration must not be a link'
    }
}
if ($CheckOnly) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'API configuration is not saved' }
    $document = [IO.File]::ReadAllText($path) | ConvertFrom-Json
    if ($document.version -ne 1 -or $document.scope -ne 'Windows-DPAPI-CurrentUser') { throw 'Invalid encrypted configuration format' }
    $id = ConvertTo-SecureString $document.api_id_dpapi
    $hash = ConvertTo-SecureString $document.api_hash_dpapi
    try {
        if (-not (Test-ApiValue $id 'id') -or -not (Test-ApiValue $hash 'hash')) { throw 'Invalid stored API configuration' }
    } finally { $id.Dispose(); $hash.Dispose() }
    Write-Output 'API_CONFIGURATION_READY: values not printed'
    exit 0
}

[IO.Directory]::CreateDirectory($targetDir) | Out-Null
$user = [Security.Principal.WindowsIdentity]::GetCurrent().User
$system = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetOwner($user)
$acl.SetAccessRuleProtection($true, $false)
foreach ($identity in @($user, $system)) {
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl',
        'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $targetDir -AclObject $acl
if (Test-Path -LiteralPath $path) {
    if (((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Encrypted configuration must not be a link'
    }
}
Write-Host 'Enter your own Telegram API values from my.telegram.org/apps. Input is hidden.'
$id = Read-Host 'api_id' -AsSecureString
$hash = Read-Host 'api_hash' -AsSecureString
$temporary = Join-Path $targetDir ('configuration-' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    if (-not (Test-ApiValue $id 'id') -or -not (Test-ApiValue $hash 'hash')) {
        throw 'API values have an invalid format; nothing was saved'
    }
    $document = [ordered]@{
        version = 1
        scope = 'Windows-DPAPI-CurrentUser'
        created_at = [DateTime]::UtcNow.ToString('o')
        api_id_dpapi = (ConvertFrom-SecureString $id)
        api_hash_dpapi = (ConvertFrom-SecureString $hash)
    }
    [IO.File]::WriteAllText($temporary, ($document | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    if (Test-Path -LiteralPath $path) { [IO.File]::Replace($temporary, $path, $null) }
    else { [IO.File]::Move($temporary, $path) }
    # Replace may preserve an earlier destination ACL: explicitly restore the same private scope.
    $fileAcl = New-Object Security.AccessControl.FileSecurity
    $fileAcl.SetOwner($user); $fileAcl.SetAccessRuleProtection($true, $false)
    foreach ($identity in @($user, $system)) {
        $fileAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')))
    }
    Set-Acl -LiteralPath $path -AclObject $fileAcl
    Write-Output ('SAVED_ENCRYPTED: ' + $path)
    Write-Output 'Only this Windows user can decrypt it. No values were sent to GitHub.'
} finally {
    $id.Dispose(); $hash.Dispose()
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
