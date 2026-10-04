#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PackagePath,
    [string]$JavaHome = $env:JAVA_HOME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$package = (Resolve-Path -LiteralPath $PackagePath).Path
$name = [IO.Path]::GetFileName($package)
if ($name -cnotmatch '^openpulse-analyzer-([0-9]+\.[0-9]+\.[0-9]+)-windows-x64\.zip$') { throw 'Unexpected package name' }
$version = $Matches[1]
$rootName = "openpulse-analyzer-$version"
$hashFile = "$package.sha256"
if (-not (Test-Path -LiteralPath $hashFile -PathType Leaf)) { throw 'SHA-256 file is missing' }
$expectedHash = "$(Get-Content -LiteralPath $hashFile -Raw)".Trim()
$actualHashLine = '{0}  {1}' -f ((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()), $name
if ($expectedHash -cne $actualHashLine) {
    throw 'SHA-256 file does not match the ZIP'
}

Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::OpenRead($package)
try {
    $expectedEntries = @('README.md', 'THIRD_PARTY_NOTICES.md', 'bin/openpulse-analyzer.exe', 'licenses/nlohmann-json.LICENSE', 'licenses/picosha2.LICENSE') |
        ForEach-Object { "$rootName/$_" } | Sort-Object
    $entries = @($archive.Entries | ForEach-Object FullName)
    if ([string]::Join('|', $entries) -cne [string]::Join('|', $expectedEntries)) { throw 'ZIP entry list or ordering is invalid' }
    foreach ($entry in $archive.Entries) {
        if ($entry.LastWriteTime.Date -ne [datetime]'1980-01-01') { throw 'ZIP timestamps are not normalized' }
    }
} finally { $archive.Dispose() }

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('openpulse-verify-' + [Guid]::NewGuid().ToString('N'))
function Invoke-Analyzer([string]$Exe, [string[]]$Arguments, [int]$ExpectedCode) {
    $result = (& $Exe @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne $ExpectedCode) { throw "CLI exit code $LASTEXITCODE; expected $ExpectedCode" }
    return $result
}

try {
    New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($package, $temporaryRoot)
    $exe = Join-Path $temporaryRoot "$rootName/bin/openpulse-analyzer.exe"
    $complete = Join-Path $repositoryRoot 'docs/fixtures/analyzer-report-v2-contract/repositories/complete'
    $missing = Join-Path $repositoryRoot 'docs/fixtures/analyzer-report-v2-contract/repositories/missing-structure'
    $reports = Join-Path $temporaryRoot 'reports'
    New-Item -ItemType Directory -Path (Join-Path $reports 'run-1'), (Join-Path $reports 'run-2') -Force | Out-Null

    $help = Invoke-Analyzer $exe @('--help') 0
    if ($help -notmatch '--protocol' -or $help -notmatch '--summary') { throw 'Packaged help is incomplete' }
    $defaultV1 = Join-Path $reports 'run-1/default-v1.json'
    $explicitV1 = Join-Path $reports 'run-1/explicit-v1.json'
    Invoke-Analyzer $exe @('--path', $complete, '--output', $defaultV1) 0 | Out-Null
    Invoke-Analyzer $exe @('--protocol', '1.0', '--path', $complete, '--output', $explicitV1) 0 | Out-Null
    if ((Get-Content -LiteralPath $defaultV1 -Raw | ConvertFrom-Json).protocolVersion -cne '1.0') { throw 'Default v1 changed' }
    if ((Get-Content -LiteralPath $explicitV1 -Raw | ConvertFrom-Json).protocolVersion -cne '1.0') { throw 'Explicit v1 failed' }

    $summaries = @()
    foreach ($run in 1..2) {
        $runRoot = Join-Path $reports "run-$run"
        $completeReport = Join-Path $runRoot 'complete.v2.json'
        $missingReport = Join-Path $runRoot 'missing-structure.v2.json'
        $summaries += Invoke-Analyzer $exe @('--protocol', '2.0', '--summary', '--path', $complete, '--output', $completeReport) 0
        $missingSummary = Invoke-Analyzer $exe @('--protocol', '2.0', '--summary', '--path', $missing, '--output', $missingReport) 0
        if ($missingSummary -notmatch 'Findings: 3' -or $missingSummary -match [regex]::Escape($missing)) {
            throw 'Packaged findings summary is incomplete or leaks the repository path'
        }
        $report = Get-Content -LiteralPath $completeReport -Raw | ConvertFrom-Json
        if ($report.protocolVersion -cne '2.0' -or $report.status -cne 'SUCCESS') { throw 'Packaged v2 report is invalid' }
        # Publishing a second complete report to the same path must not leave
        # a temporary file or fail on Windows replacement semantics.
        Invoke-Analyzer $exe @('--protocol', '2.0', '--path', $complete, '--output', $completeReport) 0 | Out-Null
    }
    if ($summaries[0] -cne $summaries[1]) { throw 'Repeated summaries differ' }
    foreach ($case in @('complete', 'missing-structure')) {
        $normalized = @()
        foreach ($run in 1..2) {
            $document = Get-Content -LiteralPath (Join-Path $reports "run-$run/$case.v2.json") -Raw | ConvertFrom-Json -AsHashtable
            [void]$document.Remove('taskId')
            [void]$document.Remove('generatedAt')
            $normalized += ($document | ConvertTo-Json -Depth 100 -Compress)
        }
        if ($normalized[0] -cne $normalized[1]) { throw "Repeated $case report differs after allowed normalization" }
    }
    if ($summaries[0] -match [regex]::Escape($temporaryRoot) -or $summaries[0] -match '(?i)(token\s*[:=]|authorization\s*:|stdout|stderr|stack trace)') {
        throw 'Summary exposes a local path or diagnostic text'
    }
    $spaceRepo = Join-Path $temporaryRoot 'repo with spaces'
    New-Item -ItemType Directory -Path $spaceRepo -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $spaceRepo 'README.md'), 'fixture')
    $spaceReport = Join-Path $reports 'report with spaces.json'
    Invoke-Analyzer $exe @('--protocol', '2.0', '--summary', '--path', $spaceRepo, '--output', $spaceReport) 0 | Out-Null
    if (-not (Test-Path -LiteralPath $spaceReport)) { throw 'Space-containing report path failed' }

    Invoke-Analyzer $exe @('--protocol', '1.0', '--summary', '--path', $complete, '--output', (Join-Path $reports 'invalid.json')) 1 | Out-Null
    Invoke-Analyzer $exe @('--protocol', '9.9', '--path', $complete, '--output', (Join-Path $reports 'invalid.json')) 1 | Out-Null
    Invoke-Analyzer $exe @('--protocol') 1 | Out-Null
    Invoke-Analyzer $exe @('--protocol', '2.0', '--path', (Join-Path $temporaryRoot 'missing-repo'), '--output', (Join-Path $reports 'invalid.json')) 2 | Out-Null
    $unwritable = Join-Path $temporaryRoot 'missing-parent/report.json'
    $outputError = Invoke-Analyzer $exe @('--protocol', '2.0', '--summary', '--path', $complete, '--output', $unwritable) 4
    if ($outputError -match [regex]::Escape($temporaryRoot)) { throw 'Failed summary disclosed a local path' }
    if (Test-Path -LiteralPath $unwritable) { throw 'A failed output left a report' }
    if (@(Get-ChildItem -LiteralPath $reports -Recurse -File -Filter '*.tmp.*').Count -ne 0) { throw 'A temporary report was left behind' }

    $vswhere = 'C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe'
    $dumpbin = @(& $vswhere -products '*' -latest -find 'VC\Tools\MSVC\**\Hostx64\x64\dumpbin.exe' | Select-Object -Last 1)
    if ($dumpbin.Count -ne 1 -or -not (Test-Path -LiteralPath $dumpbin[0])) { throw 'dumpbin is unavailable; runtime dependency check is required' }
    $dependencies = (& $dumpbin[0] /dependents $exe | Out-String)
    $headers = (& $dumpbin[0] /headers $exe | Out-String)
    if ($headers -notmatch '(?i)8664 machine \(x64\)') { throw 'Packaged executable is not x64' }
    if ($dependencies -match '(?i)(VCRUNTIME|MSVCP|CONCRT|UCRTBASE)\.DLL') { throw 'Unexpected dynamic Visual C++ runtime dependency' }
    Write-Host 'Runtime dependencies:'
    $dependencies.Split("`n") | Where-Object { $_ -match '^\s+[A-Z0-9_-]+\.dll\s*$' } | ForEach-Object { Write-Host $_.Trim() }

    if (-not $JavaHome) { throw 'JavaHome must point to a JDK 21 or newer for Java Reader verification' }
    $java = Join-Path $JavaHome 'bin/java.exe'
    $maven = Join-Path $repositoryRoot 'openpulse-platform/mvnw.cmd'
    if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $maven)) { throw 'JDK or Maven wrapper unavailable for packaged report reader verification' }
    $env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
    Push-Location (Join-Path $repositoryRoot 'openpulse-platform')
    try {
        & $maven '-q' '-Dtest=AnalyzerReportV2ContractTest#c03CompleteProductionCliReportPassesReaderAndSnapshotWhitelist+c04MissingStructureUsesTheProductionCatalogAndFixedIds' "-Dopenpulse.contract.report.dir=$reports" 'test'
        if ($LASTEXITCODE -ne 0) { throw 'Java Reader rejected packaged v2 reports' }
    } finally { Pop-Location }
    Write-Host "Package verified: $name"
    Write-Host "SHA-256: $((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant())"
} finally {
    $safeTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolvedTemp = [IO.Path]::GetFullPath($temporaryRoot)
    if ($resolvedTemp.StartsWith($safeTemp, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
