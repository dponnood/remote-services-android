[CmdletBinding()]
param(
    [string]$VersionFilePath = (Join-Path $PSScriptRoot '..\gradle\version.properties'),
    [int]$VersionCode = 0,
    [switch]$IncrementVersionCode,
    [string]$VersionName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (($VersionCode -gt 0) -eq [bool]$IncrementVersionCode) {
    throw 'Specify exactly one of -VersionCode or -IncrementVersionCode.'
}

$resolvedPath = if ([System.IO.Path]::IsPathRooted($VersionFilePath)) {
    [System.IO.Path]::GetFullPath($VersionFilePath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $VersionFilePath))
}
if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
    throw "Version file does not exist: $resolvedPath"
}

$values = [ordered]@{}
foreach ($line in Get-Content -LiteralPath $resolvedPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    $separator = $trimmed.IndexOf('=')
    if ($separator -le 0) { throw "Invalid version property line: $line" }
    $key = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1).Trim()
    if ($values.Contains($key)) { throw "Duplicate version property: $key" }
    $values[$key] = $value
}

$currentCode = 0
if (-not $values.Contains('versionCode') -or
    -not [int]::TryParse([string]$values.versionCode, [ref]$currentCode) -or
    $currentCode -le 0) {
    throw 'versionCode must be a positive integer.'
}
$currentName = [string]$values.versionName
if ([string]::IsNullOrWhiteSpace($currentName)) { throw 'versionName must not be empty.' }

$nextCode = if ($IncrementVersionCode) { $currentCode + 1 } else { $VersionCode }
if ($nextCode -le $currentCode) {
    throw "versionCode must increase (current=$currentCode, requested=$nextCode)."
}
$nextName = if ($PSBoundParameters.ContainsKey('VersionName')) { $VersionName.Trim() } else { $currentName }
if ($nextName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "versionName must use semantic version syntax: $nextName"
}

$parent = Split-Path -Parent $resolvedPath
$temporaryPath = "$resolvedPath.tmp.$([Guid]::NewGuid().ToString('N'))"
$content = @(
    '# Release version source of truth. versionCode must increase for every APK'
    '# accepted by Android PackageInstaller; versionName is user-visible.'
    "versionCode=$nextCode"
    "versionName=$nextName"
) -join [Environment]::NewLine
try {
    [System.IO.File]::WriteAllText($temporaryPath, $content + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryPath -Destination $resolvedPath -Force
} finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
}

[pscustomobject]@{
    Path = $resolvedPath
    PreviousVersionCode = $currentCode
    VersionCode = $nextCode
    PreviousVersionName = $currentName
    VersionName = $nextName
}
