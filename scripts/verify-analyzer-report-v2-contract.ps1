[CmdletBinding()]
param(
    [switch]$OutputSafetySelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $PSCommandPath
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot '..')).Path
$analyzerRoot = Join-Path $repositoryRoot 'openpulse-analyzer'
$platformRoot = Join-Path $repositoryRoot 'openpulse-platform'
$fixtureRoot = Join-Path $repositoryRoot 'docs/fixtures/analyzer-report-v2-contract'
$schemaPath = Join-Path $repositoryRoot 'docs/protocol/analyzer-report-v2.schema.json'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'openpulse-v2-contract-' + [Guid]::NewGuid().ToString('N'))
$buildRoot = Join-Path $temporaryRoot 'build'
$reportRoot = Join-Path $temporaryRoot 'reports'
$logPath = Join-Path $temporaryRoot 'verification.log'
$maximumSuccessOutputCharacters = 4000
$maximumFailureOutputCharacters = 2000
$maximumDiagnosticLineCharacters = 2000
$credentialLikePattern = [regex]'(?i)(authorization\s*:|bearer\s+|api[_ -]?key\s*[:=]|token\s*[:=]|password\s*[:=]|passwd\s*[:=]|secret\s*[:=]|private[_ -]?key\s*[:=]|-----BEGIN [A-Z ]*PRIVATE KEY-----)'
$diagnosticPattern = [regex]'(?i)(error|fail|exception)'

function Resolve-RequiredTool {
    param(
        [Parameter(Mandatory)][string]$Name,
        [string[]]$Candidates = @()
    )
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    foreach ($candidate in $Candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    throw "Required tool is unavailable: $Name"
}

function ConvertTo-SafeDiagnosticText {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory)][int]$MaximumCharacters
    )
    if ([string]::IsNullOrEmpty($Text)) {
        return ''
    }

    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    $safeLines = @(
        foreach ($line in $normalized.Split("`n")) {
            if ($credentialLikePattern.IsMatch($line)) {
                '[REDACTED credential-like output]'
            } else {
                $line
            }
        }
    )
    $safeText = [string]::Join([Environment]::NewLine, $safeLines)
    if ($safeText.Length -le $MaximumCharacters) {
        return $safeText
    }

    $marker = [Environment]::NewLine + '...[truncated]'
    if ($MaximumCharacters -le $marker.Length) {
        return $marker.Substring(0, $MaximumCharacters)
    }
    return $safeText.Substring(0, $MaximumCharacters - $marker.Length) + $marker
}

function Get-CapturedOutputSafety {
    param([AllowEmptyString()][string]$Text)

    $credentialDetected = $credentialLikePattern.IsMatch($Text)
    $longDiagnosticDetected = $false
    foreach ($line in [regex]::Split($Text, "`r`n|`n|`r")) {
        if ($line.Length -gt $maximumDiagnosticLineCharacters -and
            $diagnosticPattern.IsMatch($line)) {
            $longDiagnosticDetected = $true
            break
        }
    }
    return [pscustomobject]@{
        CredentialDetected = $credentialDetected
        LongDiagnosticDetected = $longDiagnosticDetected
    }
}

function Publish-CapturedOutput {
    param(
        [Parameter(Mandatory)][string]$Step,
        [Parameter(Mandatory)][int]$ExitCode,
        [AllowEmptyString()][string]$CapturedOutput
    )

    # Inspect the complete capture before anything from the child process is
    # written to the terminal or verification log.
    $safety = Get-CapturedOutputSafety $CapturedOutput
    $maximumCharacters = if ($ExitCode -eq 0) {
        $maximumSuccessOutputCharacters
    } else {
        $maximumFailureOutputCharacters
    }
    $safeOutput = ConvertTo-SafeDiagnosticText $CapturedOutput $maximumCharacters
    $logRecord = "[$Step exit=$ExitCode]" + [Environment]::NewLine +
        $safeOutput + [Environment]::NewLine
    [IO.File]::AppendAllText($logPath, $logRecord, [Text.UTF8Encoding]::new($false))

    if ($ExitCode -ne 0) {
        Write-Host "[FAIL] $Step exited with code $ExitCode; sanitized bounded output follows"
        if (-not [string]::IsNullOrEmpty($safeOutput)) {
            Write-Host $safeOutput
        }
        $safetySummary = @()
        if ($safety.CredentialDetected) {
            $safetySummary += 'credential-like output was redacted'
        }
        if ($safety.LongDiagnosticDetected) {
            $safetySummary += 'an overlong diagnostic was truncated'
        }
        $suffix = if ($safetySummary.Count -eq 0) {
            ''
        } else {
            '; ' + [string]::Join('; ', $safetySummary)
        }
        throw "$Step failed with exit code $ExitCode after output safety checks$suffix"
    }

    if ($safety.CredentialDetected -or $safety.LongDiagnosticDetected) {
        Write-Host "[BLOCKED] $Step output failed safety checks; sanitized bounded output follows"
        if (-not [string]::IsNullOrEmpty($safeOutput)) {
            Write-Host $safeOutput
        }
        throw "$Step output failed C09 safety checks"
    }

    if (-not [string]::IsNullOrEmpty($safeOutput)) {
        Write-Host $safeOutput
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$Step,
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )
    Write-Host "[RUN] $Step"
    try {
        if ([IO.Path]::GetExtension($FilePath) -ieq '.cmd') {
            Push-Location $WorkingDirectory
            try {
                $capturedRecords = @(& $FilePath @Arguments 2>&1)
                $exitCode = $LASTEXITCODE
                $capturedOutput = [string]::Join(
                    [Environment]::NewLine,
                    @($capturedRecords | ForEach-Object { [string]$_ }))
            } finally {
                Pop-Location
            }
        } else {
            # The Codex host can expose both Path and PATH. MSBuild treats those
            # case variants as duplicate dictionary keys, so native build tools
            # receive a normalized child environment with exactly one PATH entry.
            $startInfo = [Diagnostics.ProcessStartInfo]::new()
            $startInfo.FileName = $FilePath
            $startInfo.WorkingDirectory = $WorkingDirectory
            $startInfo.UseShellExecute = $false
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true
            foreach ($argument in $Arguments) {
                $startInfo.ArgumentList.Add($argument)
            }
            $startInfo.Environment.Clear()
            $pathAdded = $false
            foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
                if ($entry.Key -ieq 'PATH') {
                    if (-not $pathAdded) {
                        $startInfo.Environment['PATH'] = $env:PATH
                        $pathAdded = $true
                    }
                } elseif (-not $startInfo.Environment.ContainsKey([string]$entry.Key)) {
                    $startInfo.Environment[[string]$entry.Key] = [string]$entry.Value
                }
            }
            $process = [Diagnostics.Process]::new()
            try {
                $process.StartInfo = $startInfo
                $null = $process.Start()
                $standardOutput = $process.StandardOutput.ReadToEndAsync()
                $standardError = $process.StandardError.ReadToEndAsync()
                $process.WaitForExit()
                $output = $standardOutput.GetAwaiter().GetResult()
                $errorOutput = $standardError.GetAwaiter().GetResult()
                $exitCode = $process.ExitCode
                $capturedOutput = if ([string]::IsNullOrEmpty($output)) {
                    $errorOutput
                } elseif ([string]::IsNullOrEmpty($errorOutput)) {
                    $output
                } else {
                    $output + [Environment]::NewLine + $errorOutput
                }
            } finally {
                $process.Dispose()
            }
        }
    } catch {
        $safeLaunchError = ConvertTo-SafeDiagnosticText ([string]$_.Exception.Message) 500
        throw "$Step could not start safely: $safeLaunchError"
    }
    Publish-CapturedOutput -Step $Step -ExitCode $exitCode -CapturedOutput $capturedOutput
}

function Assert-OutputSafetyFailureProbe {
    param(
        [Parameter(Mandatory)][string]$Step,
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Arguments
    )
    [IO.File]::WriteAllText($logPath, '', [Text.UTF8Encoding]::new($false))
    $visibleRecords = @(
        & {
            try {
                Invoke-Checked $Step $FilePath $Arguments $repositoryRoot
            } catch {
                Write-Host ("Contract verification failed: " + $_.Exception.Message)
            }
        } 6>&1
    )
    $visible = [string]::Join(
        [Environment]::NewLine,
        @($visibleRecords | ForEach-Object { [string]$_ }))
    $logged = Get-Content -LiteralPath $logPath -Raw -Encoding UTF8
    foreach ($surface in @($visible, $logged)) {
        if ($surface.Contains('contract-secret-should-never-appear') -or
            $surface.Contains('Authorization:', [StringComparison]::OrdinalIgnoreCase) -or
            $surface.Contains('Bearer ', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Output safety self-test exposed credential-like probe content'
        }
        if (-not $surface.Contains('[REDACTED credential-like output]')) {
            throw 'Output safety self-test did not record the redaction marker'
        }
        if (-not $surface.Contains('...[truncated]')) {
            throw 'Output safety self-test did not record the truncation marker'
        }
    }
    if ($visible.Length -gt ($maximumFailureOutputCharacters + 1000)) {
        throw 'Output safety self-test exceeded the bounded failure display'
    }
    if (-not $visible.Contains('after output safety checks')) {
        throw 'Output safety self-test did not execute the checked failure path'
    }
}

function Invoke-OutputSafetySelfTest {
    $hostExecutable = (Get-Process -Id $PID).Path
    $probeCommand = @'
[Console]::Out.WriteLine("probe-safe-line")
[Console]::Out.WriteLine("Authorization: Bearer contract-secret-should-never-appear")
[Console]::Error.WriteLine("error " + ("x" * 5000))
exit 7
'@
    Assert-OutputSafetyFailureProbe 'native output safety failure probe' $hostExecutable @(
        '-NoProfile', '-NonInteractive', '-Command', $probeCommand)

    $cmdProbe = Join-Path $temporaryRoot 'output-safety-probe.cmd'
    $cmdLines = @(
        '@echo off',
        'echo probe-safe-line',
        'echo Authorization: Bearer contract-secret-should-never-appear',
        ('"' + $hostExecutable +
            '" -NoProfile -NonInteractive -Command "[Console]::Error.WriteLine(''error '' + (''x'' * 5000))"'),
        'exit /b 7'
    )
    [IO.File]::WriteAllLines($cmdProbe, $cmdLines, [Text.UTF8Encoding]::new($false))
    Assert-OutputSafetyFailureProbe 'cmd output safety failure probe' $cmdProbe @()

    Write-Host '[PASS] Output safety self-test: native and cmd failure output redacted, truncated, and bounded'
}

function Get-Executable {
    param([Parameter(Mandatory)][string]$Name)
    $candidates = @(
        (Join-Path $buildRoot $Name),
        (Join-Path $buildRoot ($Name + '.exe')),
        (Join-Path $buildRoot ('Release/' + $Name)),
        (Join-Path $buildRoot ('Release/' + $Name + '.exe'))
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    throw "Built executable is missing: $Name"
}

function Get-NormalizedJson {
    param([Parameter(Mandatory)][string]$Path)
    $root = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
    if (-not $root.Contains('taskId') -or -not $root.Contains('generatedAt')) {
        throw "Report is missing a normalization field: $Path"
    }
    $root.Remove('taskId')
    $root.Remove('generatedAt')
    return ($root | ConvertTo-Json -Compress -Depth 100)
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Text)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [Security.Cryptography.SHA256]::HashData($bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

function Get-CanonicalGoldenJson {
    param([Parameter(Mandatory)][string]$Path)

    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -eq 0) {
        throw "Normalized golden is empty: $Path"
    }
    if (($bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) -or
        ($bytes.Length -ge 2 -and
            (($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) -or
             ($bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF)))) {
        throw "Normalized golden must not contain a BOM: $Path"
    }

    try {
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    } catch {
        throw "Normalized golden must be valid UTF-8: $Path"
    }
    if ($text.EndsWith("`r", [StringComparison]::Ordinal) -or
        $text.EndsWith("`n", [StringComparison]::Ordinal)) {
        throw "Normalized golden must not end with a newline: $Path"
    }
    if ([char]::IsWhiteSpace($text[0]) -or [char]::IsWhiteSpace($text[$text.Length - 1])) {
        throw "Normalized golden must not contain leading or trailing whitespace: $Path"
    }

    try {
        $parsed = $text | ConvertFrom-Json -AsHashtable
        $canonical = $parsed | ConvertTo-Json -Compress -Depth 100
    } catch {
        throw "Normalized golden must contain valid JSON: $Path"
    }
    if ($text -cne $canonical) {
        throw "Normalized golden must be compact JSON without extra whitespace: $Path"
    }
    return $text
}

function Assert-ManifestExpectations {
    param(
        [Parameter(Mandatory)][string]$CaseId,
        [Parameter(Mandatory)][string]$ReportPath,
        [Parameter(Mandatory)][System.Collections.IDictionary]$ManifestCase
    )

    $report = Get-Content -LiteralPath $ReportPath -Raw -Encoding UTF8 |
        ConvertFrom-Json -AsHashtable
    foreach ($field in @(
            'expectedProtocolVersion',
            'expectedStatus',
            'expectedReviewability',
            'expectedRuleIds')) {
        if (-not $ManifestCase.Contains($field)) {
            throw "cases.json entry '$CaseId' is missing $field"
        }
    }
    if ($report.protocolVersion -cne $ManifestCase.expectedProtocolVersion) {
        throw "Protocol version differs from cases.json: $CaseId"
    }
    if ($report.status -cne $ManifestCase.expectedStatus) {
        throw "Status differs from cases.json: $CaseId"
    }
    if ($report.reviewability -cne $ManifestCase.expectedReviewability) {
        throw "Reviewability differs from cases.json: $CaseId"
    }

    $actualRuleIds = @($report.findings | ForEach-Object { $_.ruleId })
    $expectedRuleIds = @($ManifestCase.expectedRuleIds)
    if ($actualRuleIds.Count -ne $expectedRuleIds.Count) {
        throw "Rule ID count differs from cases.json: $CaseId"
    }
    for ($index = 0; $index -lt $expectedRuleIds.Count; $index++) {
        if ($actualRuleIds[$index] -cne $expectedRuleIds[$index]) {
            throw "Rule ID order differs from cases.json at index $index`: $CaseId"
        }
    }
}

function Assert-ContractGolden {
    param(
        [Parameter(Mandatory)][string]$CaseId,
        [Parameter(Mandatory)][System.Collections.IDictionary]$ManifestCase
    )
    $firstPath = Join-Path $reportRoot "run-1/$CaseId.v2.json"
    $secondPath = Join-Path $reportRoot "run-2/$CaseId.v2.json"
    Assert-ManifestExpectations -CaseId $CaseId -ReportPath $firstPath -ManifestCase $ManifestCase
    Assert-ManifestExpectations -CaseId $CaseId -ReportPath $secondPath -ManifestCase $ManifestCase
    $first = Get-NormalizedJson $firstPath
    $second = Get-NormalizedJson $secondPath
    if ($first -cne $second) {
        throw "C07 determinism failed for $CaseId"
    }
    $goldenPath = Join-Path $fixtureRoot $ManifestCase.normalizedGolden
    $golden = Get-CanonicalGoldenJson $goldenPath
    if ($first -cne $golden) {
        throw "Normalized report differs from the reviewed golden: $CaseId"
    }
    $actualHash = Get-Sha256 $first
    if ($actualHash -cne $ManifestCase.normalizedSha256) {
        throw "Normalized SHA-256 differs from cases.json: $CaseId"
    }
    Write-Host "[PASS] C07 $CaseId deterministic SHA-256=$actualHash"
}

function Assert-SafeArtifacts {
    param([Parameter(Mandatory)][string[]]$Paths)
    $userName = [Environment]::UserName
    $fixtureAbsolute = (Resolve-Path -LiteralPath $fixtureRoot).Path
    $sensitive = [regex]'(?i)(authorization\s*:|bearer\s+|api[_ -]?key\s*[:=]|token\s*[:=]|password\s*[:=]|passwd\s*[:=]|secret\s*[:=]|private[_ -]?key\s*[:=]|-----BEGIN [A-Z ]*PRIVATE KEY-----|\bstdout\b|\bstderr\b|stack\s*trace|exception\s*:)'
    foreach ($path in $Paths) {
        $text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        if ($text.Contains($fixtureAbsolute) -or
            $text.Contains($fixtureAbsolute.Replace('\', '/')) -or
            (-not [string]::IsNullOrWhiteSpace($userName) -and
                $text.Contains($userName, [StringComparison]::OrdinalIgnoreCase)) -or
            $sensitive.IsMatch($text)) {
            throw "C09 sensitive or machine-specific content found in: $path"
        }
    }
}

New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $reportRoot 'run-1') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $reportRoot 'run-2') -Force | Out-Null

try {
    if ($OutputSafetySelfTest) {
        Invoke-OutputSafetySelfTest
        return
    }

    $cmake = Resolve-RequiredTool 'cmake.exe' @(
        'C:/Program/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe',
        (Join-Path $repositoryRoot '.tools/cmake-3.31.6/cmake-3.31.6-windows-x86_64/bin/cmake.exe'),
        'C:/Program Files/CMake/bin/cmake.exe',
        'C:/Program Files/Microsoft Visual Studio/2022/BuildTools/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
    )
    $ctest = Resolve-RequiredTool 'ctest.exe' @(
        'C:/Program/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/ctest.exe',
        (Join-Path $repositoryRoot '.tools/cmake-3.31.6/cmake-3.31.6-windows-x86_64/bin/ctest.exe'),
        'C:/Program Files/CMake/bin/ctest.exe',
        'C:/Program Files/Microsoft Visual Studio/2022/BuildTools/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/ctest.exe'
    )
    $preferredJava = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java.exe'
    if (Test-Path -LiteralPath $preferredJava -PathType Leaf) {
        $java = $preferredJava
    } else {
        $java = Resolve-RequiredTool 'java.exe'
    }
    $mavenWrapper = Join-Path $platformRoot 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
        throw 'Maven Wrapper is missing'
    }
    $javaHome = Split-Path -Parent (Split-Path -Parent $java)
    $env:JAVA_HOME = $javaHome
    $env:PATH = (Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH

    Write-Host "Repository: $repositoryRoot"
    Write-Host "OS: $([Environment]::OSVersion.VersionString)"
    if (-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) {
        throw 'The official protocol v2 Schema is missing'
    }
    $schemaSources = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'docs') -Recurse -File |
        Where-Object { $_.Name -ceq 'analyzer-report-v2.schema.json' })
    if ($schemaSources.Count -ne 1 -or $schemaSources[0].FullName -cne $schemaPath) {
        throw 'The repository must contain exactly one official protocol v2 Schema source'
    }
    Invoke-Checked 'CMake version' $cmake @('--version') $repositoryRoot
    Invoke-Checked 'CTest version' $ctest @('--version') $repositoryRoot
    Invoke-Checked 'Java version' $java @('-version') $repositoryRoot
    Write-Host 'Schema: Draft 2020-12 docs/protocol/analyzer-report-v2.schema.json'
    [xml]$pom = Get-Content -LiteralPath (Join-Path $platformRoot 'pom.xml') -Raw -Encoding UTF8
    $schemaValidatorVersion = $pom.project.properties.'json-schema-validator.version'
    Write-Host "Schema validator: com.networknt json-schema-validator $schemaValidatorVersion"

    $configureArguments = @('-S', $analyzerRoot, '-B', $buildRoot, '-DBUILD_TESTS=ON')
    $cachedNlohmann = Join-Path $repositoryRoot '.tools/task12-dev-build/_deps/nlohmann_json-src'
    if (Test-Path -LiteralPath $cachedNlohmann -PathType Container) {
        $configureArguments += "-DFETCHCONTENT_SOURCE_DIR_NLOHMANN_JSON=$cachedNlohmann"
    }
    $ninja = Get-Command 'ninja.exe' -ErrorAction SilentlyContinue
    $mingwMake = Get-Command 'mingw32-make.exe' -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath 'C:/Program/VC/Auxiliary/Build/vcvars64.bat') {
        $configureArguments += @('-G', 'Visual Studio 17 2022', '-A', 'x64')
    } elseif ($null -ne $ninja) {
        $configureArguments += @('-G', 'Ninja')
    } elseif ($null -ne $mingwMake) {
        $configureArguments += @('-G', 'MinGW Makefiles')
    }
    Invoke-Checked 'CMake configure' $cmake $configureArguments $repositoryRoot
    Invoke-Checked 'C++ build' $cmake @('--build', $buildRoot, '--config', 'Release') $repositoryRoot
    Invoke-Checked 'complete CTest suite' $ctest @('--test-dir', $buildRoot, '-C', 'Release', '--output-on-failure') $repositoryRoot

    $analyzer = Get-Executable 'openpulse-analyzer'
    $scenarioGenerator = Get-Executable 'openpulse-analyzer-contract-report-generator'
    $complete = Join-Path $fixtureRoot 'repositories/complete'
    $missing = Join-Path $fixtureRoot 'repositories/missing-structure'
    $partialScenario = Join-Path $fixtureRoot 'scenarios/partial-success.json'
    $failedScenario = Join-Path $fixtureRoot 'scenarios/failed.json'

    Invoke-Checked 'C01 default v1 production CLI' $analyzer @(
        '--path', $complete, '--output', (Join-Path $reportRoot 'run-1/default-v1.json')) $repositoryRoot
    Invoke-Checked 'C02 explicit v1 production CLI' $analyzer @(
        '--protocol', '1.0', '--path', $complete,
        '--output', (Join-Path $reportRoot 'run-1/explicit-v1.json')) $repositoryRoot

    foreach ($run in 1..2) {
        $runDirectory = Join-Path $reportRoot "run-$run"
        Invoke-Checked "C03 complete v2 run $run" $analyzer @(
            '--protocol', '2.0', '--path', $complete,
            '--output', (Join-Path $runDirectory 'complete.v2.json')) $repositoryRoot
        Invoke-Checked "C04 missing-structure v2 run $run" $analyzer @(
            '--protocol', '2.0', '--path', $missing,
            '--output', (Join-Path $runDirectory 'missing-structure.v2.json')) $repositoryRoot
        Invoke-Checked "C05 partial builder run $run" $scenarioGenerator @(
            '--scenario', $partialScenario,
            '--output', (Join-Path $runDirectory 'partial-success.v2.json'),
            '--task-id', "contract_partial_$run",
            '--generated-at', "2026-10-02T0$($run - 1):00:00Z") $repositoryRoot
        Invoke-Checked "C06 failed builder run $run" $scenarioGenerator @(
            '--scenario', $failedScenario,
            '--output', (Join-Path $runDirectory 'failed.v2.json'),
            '--task-id', "contract_failed_$run",
            '--generated-at', "2026-10-02T0$($run - 1):30:00Z") $repositoryRoot
    }

    $manifest = Get-Content -LiteralPath (Join-Path $fixtureRoot 'cases.json') -Raw -Encoding UTF8 |
        ConvertFrom-Json -AsHashtable
    if ($manifest.formatVersion -cne 'openpulse-v2-contract-cases@1') {
        throw 'Unsupported contract fixture manifest format'
    }
    foreach ($case in $manifest.cases) {
        Assert-ContractGolden -CaseId $case.id -ManifestCase $case
    }

    $contractProperty = "-Dopenpulse.contract.report.dir=$reportRoot"
    Invoke-Checked 'C01-C09 Java contract suite' $mavenWrapper @(
        '-q', '-Dtest=AnalyzerReportV2ContractTest', $contractProperty, 'test') $platformRoot

    $artifactPaths = @()
    foreach ($run in 1..2) {
        # Protocol v1 intentionally retains repository.path for compatibility;
        # C09 is the protocol v2 report/snapshot safety gate.
        $artifactPaths += Get-ChildItem -LiteralPath (Join-Path $reportRoot "run-$run") -File -Filter '*.v2.json' |
            Select-Object -ExpandProperty FullName
    }
    $artifactPaths += Get-ChildItem -LiteralPath (Join-Path $fixtureRoot 'expected') -File |
        Select-Object -ExpandProperty FullName
    Assert-SafeArtifacts $artifactPaths

    $credentialInLog = Select-String -LiteralPath $logPath -Quiet -Pattern $credentialLikePattern
    if ($credentialInLog) {
        throw 'C09 credential-like text found in the local verification log'
    }
    $longDiagnostic = Get-Content -LiteralPath $logPath -Encoding UTF8 |
        Where-Object { $_ -match '(?i)(error|fail|exception)' -and $_.Length -gt 2000 } |
        Select-Object -First 1
    if ($null -ne $longDiagnostic) {
        throw 'C09 local diagnostic exceeds the 2000-character bound'
    }

    Write-Host '[PASS] C01-C09: 9/9 contract cases; N01-N10: 10/10 rejected as expected'
    Write-Host '[PASS] Real CLI coverage: default v1, explicit v1, complete v2, missing-structure v2'
    Write-Host '[PASS] Production-builder test boundary: PARTIAL_SUCCESS and FAILED'
} catch {
    Write-Error ("Contract verification failed: " + $_.Exception.Message)
    exit 1
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
