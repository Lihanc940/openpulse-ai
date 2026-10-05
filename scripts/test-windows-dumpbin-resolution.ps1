#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-dumpbin.ps1')

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'openpulse-vswhere-test-' + [Guid]::NewGuid().ToString('N'))
try {
    $cppDumpbin = Join-Path $temporaryRoot 'VS Build Tools/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/dumpbin.exe'
    $ssmsDumpbin = Join-Path $temporaryRoot 'SSMS 22/VC/Tools/MSVC/bin/Hostx64/x64/dumpbin.exe'
    New-Item -ItemType Directory -Path (Split-Path -Parent $cppDumpbin) -Force | Out-Null
    [IO.File]::WriteAllBytes($cppDumpbin, [byte[]]@())
    $fakeVsWhere = Join-Path $temporaryRoot 'vswhere.ps1'
    [IO.File]::WriteAllText($fakeVsWhere, @'
if ($args -notcontains '-products' -or $args -notcontains '-latest' -or $args -notcontains '-find') {
    throw 'Expected vswhere selection arguments were not supplied'
}
$findIndex = [Array]::IndexOf($args, '-find')
if ($args[$findIndex + 1] -cne 'VC\Tools\MSVC\**\Hostx64\x64\dumpbin.exe') {
    throw 'Expected x64 dumpbin search pattern was not supplied'
}
$requiresIndex = [Array]::IndexOf($args, '-requires')
if ($requiresIndex -ge 0 -and $args[$requiresIndex + 1] -ceq 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64') {
    $env:OPENPULSE_TEST_CPP_DUMPBIN
} else {
    # Simulate a newer SSMS installation selected by unfiltered -latest.
    $env:OPENPULSE_TEST_SSMS_DUMPBIN
}
'@, [Text.UTF8Encoding]::new($false))
    $env:OPENPULSE_TEST_CPP_DUMPBIN = $cppDumpbin
    $env:OPENPULSE_TEST_SSMS_DUMPBIN = $ssmsDumpbin
    $unfiltered = & $fakeVsWhere -products '*' -latest -find 'VC\Tools\MSVC\**\Hostx64\x64\dumpbin.exe'
    if ($unfiltered -cne $ssmsDumpbin -or (Test-Path -LiteralPath $unfiltered)) {
        throw 'The fake VS Installer scenario did not reproduce the SSMS selection failure'
    }
    $selected = Resolve-WindowsDumpbin -VsWherePath $fakeVsWhere
    if ($selected -cne $cppDumpbin) {
        throw 'C++ Build Tools dumpbin was not selected when a newer SSMS is installed'
    }
    Write-Host 'VS Installer selection regression: passed'
} finally {
    Remove-Item Env:OPENPULSE_TEST_CPP_DUMPBIN, Env:OPENPULSE_TEST_SSMS_DUMPBIN -ErrorAction SilentlyContinue
    $safeTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolvedTemp = [IO.Path]::GetFullPath($temporaryRoot)
    if ($resolvedTemp.StartsWith($safeTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
