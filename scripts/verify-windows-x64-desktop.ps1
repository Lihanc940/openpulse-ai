#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PackagePath,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$JavaHome = $env:JAVA_HOME
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$package = (Resolve-Path -LiteralPath $PackagePath).Path
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) { throw 'Use a NEW extraction directory, never an existing installation.' }
$settings = Get-Content -Raw (Join-Path $repository 'openpulse-desktop/distribution/portable-inputs.json') | ConvertFrom-Json
$hash = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()
if ((Get-Content -Raw -LiteralPath "$package.sha256").Trim() -cne "$hash  $([IO.Path]::GetFileName($package))") {
    throw 'ZIP SHA-256 mismatch'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($package)
try {
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '^OpenPulse/[^\\:]+$' -or
            $entry.FullName.Split('/') -contains '..' -or -not $seen.Add($entry.FullName)) { throw 'Unsafe or duplicate ZIP entry' }
        if ($entry.LastWriteTime.Date -ne [datetime]'1980-01-01') { throw 'ZIP timestamps are not normalized' }
    }
} finally { $archive.Dispose() }
[IO.Compression.ZipFile]::ExtractToDirectory($package, $destination)
$image = Join-Path $destination 'OpenPulse'
$manifest = Get-Content -Raw -LiteralPath (Join-Path $image 'VERSION.json') | ConvertFrom-Json
if ($manifest.version -cne $settings.version -or $manifest.status -cne 'LOCAL_CANDIDATE_NOT_RELEASED' -or
    $manifest.desktopProtocol -cne '2.0' -or $manifest.cliDefaultProtocol -cne '1.0') { throw 'Candidate metadata mismatch' }
$inventory = @(Get-Content -LiteralPath (Join-Path $image 'FILES.sha256'))
$seen.Clear()
foreach ($line in $inventory) {
    if ($line -notmatch '^([a-f0-9]{64})  (.+)$') { throw 'Invalid file inventory' }
    $fileHash = $Matches[1]; $relative = $Matches[2]
    $file = [IO.Path]::GetFullPath((Join-Path $image $relative))
    if (-not $file.StartsWith($image + '\', [StringComparison]::OrdinalIgnoreCase) -or -not $seen.Add($relative) -or
        (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ine $fileHash) { throw "Inventory mismatch: $relative" }
}
if (@(Get-ChildItem -LiteralPath $image -File -Recurse).Count -ne $inventory.Count + 1) { throw 'Unlisted file in image' }
foreach ($required in @('OpenPulse.exe','LICENSE','README.zh-CN.md','THIRD_PARTY_NOTICES.md',
    'runtime/bin/server/jvm.dll','runtime/bin/javafx/glass.dll','runtime/legal/java.base/LICENSE',
    'runtime/legal/javafx.graphics/LICENSE','runtime/legal/javafx.graphics/ASSEMBLY_EXCEPTION',
    'runtime/legal/javafx.graphics/jpeg_fx.md','runtime/legal/javafx.graphics/mesa3d.md',
    'licenses/Apache-2.0.txt','licenses/TEMURIN-NOTICE','licenses/ANALYZER-NOTICES.md',
    'licenses/analyzer/nlohmann-json.LICENSE','licenses/analyzer/picosha2.LICENSE')) {
    if (-not (Test-Path -LiteralPath (Join-Path $image $required))) { throw "Required file missing: $required" }
}
$actualJars = @(Get-ChildItem -LiteralPath (Join-Path $image 'app') -Filter '*.jar' | ForEach-Object Name | Sort-Object)
$expectedJars = @(@($settings.libraries | ForEach-Object file) + 'openpulse-desktop-0.1.0-SNAPSHOT.jar' | Sort-Object)
if (($actualJars -join '|') -cne ($expectedJars -join '|')) { throw 'Unexpected application dependencies' }
if (Test-Path -LiteralPath (Join-Path $image 'runtime/bin/javac.exe')) { throw 'Compiler should not be shipped' }
$cfg = Get-Content -Raw -LiteralPath (Join-Path $image 'app/OpenPulse.cfg')
if ($cfg -match '(?i)[a-z]:[\\/]|openpulse\.desktop\.app-dir|javafx\.web|javafx\.media' -or
    $cfg -notmatch '\$APPDIR' -or $cfg -notmatch '--add-modules=javafx.controls') { throw 'Nonportable launcher configuration' }
$analyzer = Join-Path $image 'app/bin/openpulse-analyzer.exe'
if ((Get-FileHash -LiteralPath $analyzer -Algorithm SHA256).Hash -ine $manifest.analyzerExeSha256) { throw 'Analyzer hash mismatch' }

$qa = Join-Path $destination 'qa'
$classes = Join-Path $qa 'classes'
$temp = Join-Path $qa 'temp'
New-Item -ItemType Directory $classes, $temp -Force | Out-Null
function Invoke-Isolated([string]$Executable, [string[]]$Arguments) {
    $start = [Diagnostics.ProcessStartInfo]::new($Executable)
    $start.UseShellExecute = $false
    $start.WorkingDirectory = $qa
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    foreach ($key in @($start.Environment.Keys)) {
        if ($key -match '(?i)JAVA|JDK|CLASSPATH|MAVEN|GRADLE|OPENPULSE|VSINSTALL|VCINSTALL|VCTOOLS') { [void]$start.Environment.Remove($key) }
    }
    $start.Environment['PATH'] = Join-Path $env:SystemRoot 'System32'
    $start.Environment['TEMP'] = $temp; $start.Environment['TMP'] = $temp
    foreach ($key in @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY')) { $start.Environment[$key] = 'http://127.0.0.1:9' }
    $start.Environment['NO_PROXY'] = ''
    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(60000)) { $process.Kill($true); throw 'Isolated package probe timed out' }
    $outText = $stdout.GetAwaiter().GetResult(); $errorText = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "Probe exited $($process.ExitCode): $outText $errorText" }
    Write-Host $outText.Trim()
    if ($errorText) { Write-Host $errorText.Trim() }
    $process.Dispose()
    return $outText
}
$java = Join-Path $image 'runtime/bin/java.exe'
$moduleText = Invoke-Isolated $java @('--list-modules')
$modules = @($moduleText.Trim() -split '\r?\n')
if (($modules -join '|') -cne ($manifest.runtimeModules -join '|') -or
    $moduleText -notmatch 'javafx.controls@21.0.13' -or $moduleText -match 'javafx.web|javafx.media|jdk.compiler') { throw 'Unexpected runtime modules' }
$classPath = (Join-Path $image 'app/*') + ';' + $classes
& (Join-Path $JavaHome 'bin/javac.exe') '-encoding' 'UTF-8' '-cp' (Join-Path $image 'app/*') '-d' $classes (Join-Path $PSScriptRoot 'qa/PortableReportCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Maintainer-only report probe compilation failed' }
$fixture = Join-Path $destination '中文 项目'
New-Item -ItemType Directory $fixture -Force | Out-Null
Copy-Item -Path (Join-Path $repository 'docs/fixtures/analyzer-report-v2-contract/repositories/missing-structure/*') -Destination $fixture -Recurse
$cliReport = Join-Path $qa 'cli.v2.json'
$desktopReport = Join-Path $qa 'runner-export.v2.json'
Invoke-Isolated $analyzer @('--protocol','2.0','--path',$fixture,'--output',$cliReport) | Out-Null
Invoke-Isolated $java @('-cp',$classPath,'PortableReportCheck','run',$image,$fixture,$desktopReport) | Out-Null
Invoke-Isolated $java @('-cp',$classPath,'PortableReportCheck','compare',$cliReport,$desktopReport) | Out-Null
$v1 = Join-Path $qa 'default.v1.json'
# v1 has a pre-existing non-UTF8 Windows path limitation. Do not change its protocol
# or broaden desktop scope; check its unchanged default on the established CLI fixture.
$defaultFixture = Join-Path $repository 'docs/fixtures/analyzer-report-v2-contract/repositories/complete'
Invoke-Isolated $analyzer @('--path',$defaultFixture,'--output',$v1) | Out-Null
if ((Get-Content -Raw -LiteralPath $v1 | ConvertFrom-Json).protocolVersion -cne '1.0') { throw 'CLI default protocol changed' }
if (@(Get-ChildItem -LiteralPath $temp -Directory -Filter 'openpulse-analyzer-*').Count -ne 0) { throw 'Runner left temporary directories' }
$result = [ordered]@{ zipSha256=$hash; inventoryFiles=$inventory.Count; image=$image; fixture=$fixture;
    runtimeModules=$modules; classPath=$classPath; strictReader=$true; realAnalyzer=$true; cliFactsEqual=$true;
    environment='Child PATH=Windows/System32; Java/JDK/Maven options removed; TEMP private; dead proxy environment';
    limitation='OS network not disabled; proxy environment is not a network firewall; fresh Windows VM not tested';
    windowAcceptance='Separate real-window acceptance required' }
$result | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $qa 'verification.json')
Write-Host "PASS: candidate extracted and verified at $image"
Write-Host 'Window double-click/cancel/import/export/close must still be tested separately.'
