#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$Generator = 'Visual Studio 17 2022',
    [string]$NlohmannSource
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -and $PSVersionTable.PSEdition -eq 'Core') { throw 'Windows x64 is required' }
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$analyzerRoot = Join-Path $repositoryRoot 'openpulse-analyzer'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $analyzerRoot 'out/windows-x64' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('openpulse-package-' + [Guid]::NewGuid().ToString('N'))
$buildRoot = Join-Path $temporaryRoot 'build'
$installRoot = Join-Path $temporaryRoot 'install'
$zipPath = $null
$hashPath = $null

function Invoke-Checked([string]$Label, [string]$Exe, [string[]]$Arguments) {
    Write-Host $Label
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE" }
}

try {
    New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
    $cmake = (Get-Command cmake.exe -ErrorAction Stop).Source
    $ctest = (Get-Command ctest.exe -ErrorAction Stop).Source
    $configure = @('-S', $analyzerRoot, '-B', $buildRoot, '-G', $Generator, '-A', 'x64', '-DBUILD_TESTS=ON')
    if ($NlohmannSource) {
        $NlohmannSource = (Resolve-Path -LiteralPath $NlohmannSource).Path
        $git = (Get-Command git.exe -ErrorAction Stop).Source
        $head = (& $git -C $NlohmannSource rev-parse HEAD).Trim()
        $tag = (& $git -C $NlohmannSource rev-parse 'refs/tags/v3.11.3^{commit}').Trim()
        if ($LASTEXITCODE -ne 0 -or $head -cne $tag -or
            @(& $git -C $NlohmannSource status --porcelain).Count -ne 0 -or
            -not (Test-Path -LiteralPath (Join-Path $NlohmannSource 'LICENSE.MIT'))) {
            throw 'Explicit nlohmann/json source must be a clean checkout of tag v3.11.3'
        }
        Write-Host "Verified nlohmann/json v3.11.3 commit: $head"
        $configure += "-DFETCHCONTENT_SOURCE_DIR_NLOHMANN_JSON=$NlohmannSource"
    }
    Invoke-Checked 'Configure Release x64' $cmake $configure
    $versionHeader = Get-Content -LiteralPath (Join-Path $buildRoot 'generated/Version.h') -Raw
    if ($versionHeader -cnotmatch '#define OPENPULSE_ANALYZER_VERSION "([0-9]+\.[0-9]+\.[0-9]+)"') {
        throw 'CMake-generated analyzer version is missing'
    }
    $version = $Matches[1]
    $rootName = "openpulse-analyzer-$version"
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $zipName = "$rootName-windows-x64.zip"
    $zipPath = Join-Path $OutputDirectory $zipName
    $hashPath = "$zipPath.sha256"
    # Invalidate old outputs before any later build or test failure.
    Remove-Item -LiteralPath $zipPath, $hashPath -Force -ErrorAction SilentlyContinue
    Invoke-Checked 'Build Release x64' $cmake @('--build', $buildRoot, '--config', 'Release', '--parallel')
    Invoke-Checked 'Run complete CTest' $ctest @('--test-dir', $buildRoot, '-C', 'Release', '--output-on-failure')

    $stage = Join-Path $installRoot $rootName
    Invoke-Checked 'Install package tree' $cmake @('--install', $buildRoot, '--config', 'Release', '--prefix', $stage, '--component', 'Runtime')
    $expected = @('README.md', 'THIRD_PARTY_NOTICES.md', 'bin/openpulse-analyzer.exe', 'licenses/nlohmann-json.LICENSE', 'licenses/picosha2.LICENSE')
    $actual = @(Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($stage, $_.FullName).Replace('\', '/') } | Sort-Object)
    if ([string]::Join('|', $actual) -cne [string]::Join('|', ($expected | Sort-Object))) {
        throw "Unexpected install tree: $([string]::Join(', ', $actual))"
    }

    Add-Type -AssemblyName System.IO.Compression
    $stream = [IO.File]::Open($zipPath, [IO.FileMode]::CreateNew)
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            foreach ($relative in ($actual | Sort-Object)) {
                $entry = $archive.CreateEntry("$rootName/$relative", [IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
                $inputStream = [IO.File]::OpenRead((Join-Path $stage $relative))
                try {
                    $outputStream = $entry.Open()
                    try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
                } finally { $inputStream.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
    $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($hashPath, "$hash  $zipName`n", [Text.Encoding]::ASCII)
    Write-Host "Package: $zipPath"
    Write-Host "Bytes: $((Get-Item -LiteralPath $zipPath).Length)"
    Write-Host "SHA-256: $hash"
} catch {
    if ($zipPath) { Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue }
    if ($hashPath) { Remove-Item -LiteralPath $hashPath -Force -ErrorAction SilentlyContinue }
    throw
} finally {
    $safeTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolvedTemp = [IO.Path]::GetFullPath($temporaryRoot)
    if ($resolvedTemp.StartsWith($safeTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
