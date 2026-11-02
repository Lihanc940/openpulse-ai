#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AnalyzerPackage,
    [Parameter(Mandatory)][string]$JavaFxJmods,
    [string]$JavaHome = $env:JAVA_HOME
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if (-not $IsWindows -or -not [Environment]::Is64BitProcess) { throw 'Windows x64 PowerShell 7 is required.' }
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$desktop = Join-Path $repository 'openpulse-desktop'
$settings = Get-Content -Raw -Encoding UTF8 (Join-Path $desktop 'distribution/portable-inputs.json') | ConvertFrom-Json
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$JavaFxJmods = (Resolve-Path -LiteralPath $JavaFxJmods).Path
$AnalyzerPackage = (Resolve-Path -LiteralPath $AnalyzerPackage).Path
$release = Get-Content -Raw -LiteralPath (Join-Path $JavaHome 'release')
if ($release -notmatch 'JAVA_VERSION="21\.' -or $release -notmatch 'OS_ARCH="(x86_64|amd64)"') {
    throw 'A Windows x64 JDK 21 is required.'
}
if ($release -notmatch 'IMPLEMENTOR="Eclipse Adoptium"') { throw 'This notice inventory supports Eclipse Temurin JDK 21 only; review notices before changing vendor.' }
foreach ($tool in @('java', 'jlink', 'jpackage', 'jmod', 'jdeps')) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin/$tool.exe"))) { throw "JDK tool missing: $tool" }
}
foreach ($module in $settings.javafxModules.PSObject.Properties) {
    $path = Join-Path $JavaFxJmods $module.Name
    if (-not (Test-Path -LiteralPath $path) -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine $module.Value) {
        throw "OpenJFX module hash mismatch: $($module.Name)"
    }
}
if ((Get-FileHash -LiteralPath $AnalyzerPackage -Algorithm SHA256).Hash -ine $settings.analyzerArchiveSha256) {
    throw 'Analyzer archive is not the reviewed 0.2.2 candidate.'
}

function Invoke-DesktopChecked([string]$Label, [string]$Executable, [string[]]$Arguments) {
    Write-Host "[RUN] $Label"
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE" }
}
function Copy-ZipLegal([string]$Jar, [string]$Destination) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '(?i)(^|/)(LICENSE|NOTICE|COPYRIGHT)[^/]*$') { continue }
            $name = $entry.FullName.Replace('/', '_')
            New-Item -ItemType Directory -Path $Destination -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $Destination $name), $true)
        }
    } finally { $archive.Dispose() }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$output = Join-Path $desktop 'out/windows-x64'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$output = (Resolve-Path -LiteralPath $output).Path
$work = Join-Path $output ('.stage-' + [Guid]::NewGuid().ToString('N'))
$archiveName = "OpenPulse-$($settings.version)-windows-x64.zip"
$zipPath = Join-Path $output $archiveName
$hashPath = "$zipPath.sha256"
try {
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    # Only replace this named candidate; an interrupted build must not leave a stale success artifact.
    Remove-Item -LiteralPath $zipPath, $hashPath -Force -ErrorAction SilentlyContinue
    $analyzerStage = Join-Path $work 'analyzer'
    [IO.Compression.ZipFile]::ExtractToDirectory($AnalyzerPackage, $analyzerStage)
    $analyzerRoot = Join-Path $analyzerStage "openpulse-analyzer-$($settings.analyzerVersion)"
    $analyzer = Join-Path $analyzerRoot 'bin/openpulse-analyzer.exe'
    if (-not (Test-Path -LiteralPath $analyzer)) { throw 'Analyzer archive layout is invalid.' }
    # The archive is pinned to the already accepted CLI candidate. Desktop tests below
    # use this exact binary; the desktop package verifier exercises the strict Reader
    # with the bundled runtime, without bootstrapping Platform or a database.
    $savedJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $JavaHome
    Push-Location (Join-Path $repository 'openpulse-platform')
    try {
        Invoke-DesktopChecked 'Desktop + shared client tests and JARs' '.\mvnw.cmd' @(
            '-f', '..\pom.xml', '-pl', 'openpulse-desktop', '-am', 'clean', 'package',
            "-Dopenpulse.analyzer.test.executable=$analyzer", '-Dproject.build.outputTimestamp=2026-11-02T00:00:00Z')
    } finally { Pop-Location; $env:JAVA_HOME = $savedJavaHome }

    $appInput = Join-Path $work 'input'
    New-Item -ItemType Directory -Path (Join-Path $appInput 'bin') -Force | Out-Null
    $mainJar = 'openpulse-desktop-0.1.0-SNAPSHOT.jar'
    Copy-Item -LiteralPath (Join-Path $desktop "target/$mainJar") -Destination $appInput
    Copy-Item -LiteralPath $analyzer -Destination (Join-Path $appInput 'bin/openpulse-analyzer.exe')
    $runtimeJars = Join-Path $desktop 'target/runtime'
    $actual = @(Get-ChildItem -LiteralPath $runtimeJars -Filter '*.jar' | Where-Object Name -NotLike 'javafx-*' | ForEach-Object Name | Sort-Object)
    $expected = @($settings.libraries | ForEach-Object file | Sort-Object)
    if (($actual -join '|') -cne ($expected -join '|')) { throw 'Runtime libraries changed; review the distribution dependency inventory.' }
    foreach ($library in $settings.libraries) {
        Copy-Item -LiteralPath (Join-Path $runtimeJars $library.file) -Destination $appInput
    }
    $classPath = @($settings.libraries | ForEach-Object { Join-Path $appInput $_.file }) -join ';'
    $fxPath = @(Get-ChildItem -LiteralPath $runtimeJars -Filter 'javafx-*-win.jar' | ForEach-Object FullName) -join ';'
    $detected = & (Join-Path $JavaHome 'bin/jdeps.exe') '-J-Duser.language=en' '--multi-release' '21' `
        '--ignore-missing-deps' '--print-module-deps' '--module-path' $fxPath '--class-path' $classPath (Join-Path $appInput $mainJar)
    if ($LASTEXITCODE -ne 0 -or ($detected -join '') -notmatch '^java\.base,') { throw 'jdeps module analysis failed.' }
    $roots = @((($detected -join '').Split(',')) + 'jdk.crypto.ec' | Sort-Object -Unique)
    $runtime = Join-Path $work 'runtime'
    Invoke-DesktopChecked 'Link private Java + JavaFX runtime' (Join-Path $JavaHome 'bin/jlink.exe') @(
        '--module-path', ((Join-Path $JavaHome 'jmods') + ';' + $JavaFxJmods),
        '--add-modules', ($roots -join ','), '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2', '--output', $runtime)
    $imageDestination = Join-Path $work 'image'
    Invoke-DesktopChecked 'Create Windows application image' (Join-Path $JavaHome 'bin/jpackage.exe') @(
        '--type', 'app-image', '--name', 'OpenPulse', '--app-version', $settings.appVersion,
        '--vendor', 'OpenPulse AI contributors', '--description', 'OpenPulse local repository analysis candidate',
        '--runtime-image', $runtime, '--input', $appInput, '--main-jar', $mainJar,
        '--main-class', 'io.github.lihanc940.openpulse.desktop.DesktopLauncher',
        '--java-options', '--add-modules=javafx.controls', '--dest', $imageDestination)
    $image = Join-Path $imageDestination 'OpenPulse'
    Copy-Item -LiteralPath (Join-Path $repository 'LICENSE') -Destination $image
    Copy-Item -LiteralPath (Join-Path $repository 'docs/windows-x64-desktop.zh-CN.md') -Destination (Join-Path $image 'README.zh-CN.md')
    $licenses = Join-Path $image 'licenses'
    New-Item -ItemType Directory -Path $licenses -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $analyzerRoot 'licenses') -Destination (Join-Path $licenses 'analyzer') -Recurse
    Copy-Item -LiteralPath (Join-Path $analyzerRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $licenses 'ANALYZER-NOTICES.md')
    Copy-Item -LiteralPath (Join-Path $JavaHome 'NOTICE') -Destination (Join-Path $licenses 'TEMURIN-NOTICE')
    foreach ($library in $settings.libraries) {
        Copy-ZipLegal (Join-Path $appInput $library.file) (Join-Path $licenses $library.file)
    }
    # Some Apache-licensed JARs omit a separate license text. Preserve their notices/metadata,
    # and supply the full Apache 2.0 text already shipped by Jackson, without inventing copyright owners.
    $jackson = [IO.Compression.ZipFile]::OpenRead((Join-Path $appInput 'jackson-databind-3.1.4.jar'))
    try { [IO.Compression.ZipFileExtensions]::ExtractToFile($jackson.GetEntry('META-INF/LICENSE'), (Join-Path $licenses 'Apache-2.0.txt'), $true) }
    finally { $jackson.Dispose() }
    $notices = @('# OpenPulse desktop candidate - actual bundled components', '',
        "Version: $($settings.version). LOCAL CANDIDATE; NOT A PUBLIC RELEASE.", '',
        'OpenPulse desktop/shared client: MIT, see LICENSE. Analyzer notices: licenses/ANALYZER-NOTICES.md.',
        'The private Eclipse Temurin JDK 21 runtime retains its legal/ tree and licenses/TEMURIN-NOTICE.',
        "OpenJFX $($settings.javafxVersion) base/graphics/controls are linked from official Gluon Windows x64 jmods:",
        $settings.javafxSource,
        'See runtime/legal/javafx.* for GPLv2 + Classpath Exception and the actual native third-party notices.', '',
        '| JAR | License | Source |', '| --- | --- | --- |')
    foreach ($library in $settings.libraries) { $notices += "| $($library.file) | $($library.license) | $($library.source) |" }
    $notices += @('', 'JAR-internal notices are retained and extracted under licenses/<jar-name> when present.',
        'Apache 2.0 full text: licenses/Apache-2.0.txt. SLF4J MIT text: licenses/slf4j-api-2.0.17.jar/.',
        'No Spring, database driver, JUnit, AssertJ, JavaFX Web or media module is bundled.',
        'Keep this notice, LICENSE, licenses/, and runtime/legal/ with the application.')
    [IO.File]::WriteAllLines((Join-Path $image 'THIRD_PARTY_NOTICES.md'), $notices, [Text.UTF8Encoding]::new($false))
    $modules = @(& (Join-Path $runtime 'bin/java.exe') '--list-modules')
    if ($LASTEXITCODE -ne 0) { throw 'Private runtime cannot execute.' }
    $manifest = [ordered]@{
        formatVersion = 'openpulse-desktop-candidate@1'; version = $settings.version; status = 'LOCAL_CANDIDATE_NOT_RELEASED'
        sourceBaseCommit = ((& git -C $repository rev-parse HEAD) -join '').Trim()
        packagingWorktreeDirty = @(& git -C $repository status --porcelain --untracked-files=all -- scripts openpulse-desktop docs README.md).Count -gt 0
        buildScriptsSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
        jdkRelease = @($release.Split("`n") | Where-Object { $_ -match '^(IMPLEMENTOR|JAVA_VERSION|OS_ARCH|IMPLEMENTOR_VERSION)=' } | ForEach-Object Trim)
        javafxVersion = $settings.javafxVersion; javafxSource = $settings.javafxSource; javafxJmodSha256 = $settings.javafxModules
        runtimeModules = $modules; detectedModuleRoots = $roots; libraries = $settings.libraries
        analyzerVersion = $settings.analyzerVersion; analyzerArchiveSha256 = $settings.analyzerArchiveSha256
        analyzerExeSha256 = (Get-FileHash -LiteralPath $analyzer -Algorithm SHA256).Hash.ToLowerInvariant()
        desktopProtocol = '2.0'; cliDefaultProtocol = '1.0'; jarOutputTimestamp = '2026-11-02T00:00:00Z'
    }
    [IO.File]::WriteAllText((Join-Path $image 'VERSION.json'), ($manifest | ConvertTo-Json -Depth 10) + "`n", [Text.UTF8Encoding]::new($false))
    $files = @(Get-ChildItem -LiteralPath $image -Recurse -File | Sort-Object FullName)
    $lines = @($files | ForEach-Object {
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetRelativePath($image, $_.FullName).Replace('\', '/')
    })
    [IO.File]::WriteAllLines((Join-Path $image 'FILES.sha256'), $lines, [Text.UTF8Encoding]::new($false))
    $stream = [IO.File]::Open($zipPath, [IO.FileMode]::CreateNew)
    try {
        $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            foreach ($file in (Get-ChildItem -LiteralPath $image -Recurse -File | Sort-Object FullName)) {
                $relative = [IO.Path]::GetRelativePath($image, $file.FullName).Replace('\', '/')
                $entry = $zip.CreateEntry("OpenPulse/$relative", [IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
                $source = [IO.File]::OpenRead($file.FullName)
                try { $destination = $entry.Open(); try { $source.CopyTo($destination) } finally { $destination.Dispose() } }
                finally { $source.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
    $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($hashPath, "$hash  $archiveName`n", [Text.Encoding]::ASCII)
    Write-Host "Candidate: $zipPath"
    Write-Host "Bytes: $((Get-Item -LiteralPath $zipPath).Length); SHA-256: $hash"
} catch {
    Remove-Item -LiteralPath $zipPath, $hashPath -Force -ErrorAction SilentlyContinue
    throw
} finally {
    $resolvedWork = [IO.Path]::GetFullPath($work)
    if ($resolvedWork.StartsWith($output.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedWork) -match '^\.stage-[a-f0-9]{32}$' -and (Test-Path -LiteralPath $resolvedWork)) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
