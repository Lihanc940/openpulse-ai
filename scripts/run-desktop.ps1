[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AnalyzerPath,
    [switch]$SkipBuild,
    [switch]$Wait
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$desktopRoot = Join-Path $repositoryRoot 'openpulse-desktop'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 directory.' }
$java = Join-Path $env:JAVA_HOME 'bin/javaw.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'JDK javaw.exe is missing.' }
$analyzer = (Resolve-Path -LiteralPath $AnalyzerPath).Path
if (-not (Test-Path -LiteralPath $analyzer -PathType Leaf)) { throw 'AnalyzerPath must be a verified Windows x64 executable.' }
if (-not $SkipBuild) {
    Push-Location (Join-Path $repositoryRoot 'openpulse-platform')
    try {
        & .\mvnw.cmd '-f' '..\pom.xml' '-pl' 'openpulse-desktop' '-am' 'package'
        if ($LASTEXITCODE -ne 0) { throw 'Desktop build failed.' }
    } finally { Pop-Location }
}
$runtime = Join-Path $desktopRoot 'target/runtime'
$fx = @(Get-ChildItem -LiteralPath $runtime -Filter 'javafx-*-win.jar' -File)
if ($fx.Count -ne 3) { throw 'Expected JavaFX Windows base, graphics and controls jars. Run the package build.' }
$libraries = @(Get-ChildItem -LiteralPath $runtime -Filter '*.jar' -File | Where-Object { $_.Name -notlike 'javafx-*' })
$classPath = [string]::Join(';', @((Join-Path $desktopRoot 'target/classes')) + @($libraries.FullName))
$modulePath = [string]::Join(';', @($fx.FullName))
$applicationDirectory = Join-Path $desktopRoot 'target/dev-runtime'
$bin = Join-Path $applicationDirectory 'bin'
New-Item -ItemType Directory -Path $bin -Force | Out-Null
Copy-Item -LiteralPath $analyzer -Destination (Join-Path $bin 'openpulse-analyzer.exe') -Force
# A development launch only: no bundled JRE, installer, ZIP or machine-wide configuration.
$arguments = @('--module-path', ('"' + $modulePath + '"'), '--add-modules', 'javafx.controls',
    ('"-Dopenpulse.desktop.app-dir=' + $applicationDirectory + '"'), '-cp', ('"' + $classPath + '"'),
    'io.github.lihanc940.openpulse.desktop.DesktopLauncher')
$process = Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Normal -PassThru `
    -RedirectStandardOutput (Join-Path $desktopRoot 'target/desktop-stdout.log') `
    -RedirectStandardError (Join-Path $desktopRoot 'target/desktop-stderr.log')
Write-Output "Desktop development process: $($process.Id)"
if ($Wait) { $process.WaitForExit() }
