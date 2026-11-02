[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Markers,
    [ValidateSet('100%', '125%', '150%')][string]$Scale = '125%',
    [int]$TimeoutSeconds = 30,
    [string]$ReportPath,
    [string]$RepositoryPath,
    [int]$Width = 960,
    [int]$Height = 680
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$desktop = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$runtime = Join-Path $desktop 'target/runtime'
$fx = @(Get-ChildItem $runtime -Filter 'javafx-*-win.jar')
$libs = @(Get-ChildItem $runtime -Filter '*.jar' | Where-Object Name -NotLike 'javafx-*')
$modulePath = [string]::Join(';', @($fx.FullName))
$classPath = [string]::Join(';', @((Join-Path $desktop 'target/classes'), (Join-Path $desktop 'target/test-classes')) + @($libs.FullName))
New-Item -ItemType Directory -Path $Markers -Force | Out-Null
$markerPath = (Resolve-Path -LiteralPath $Markers).Path
$arguments = @('--module-path', ('"' + $modulePath + '"'), '--add-modules', 'javafx.controls',
    "-Dglass.win.uiScale=$Scale", '-cp', ('"' + $classPath + '"'),
    'io.github.lihanc940.openpulse.desktop.WindowProbe', ('"--markers=' + $markerPath + '"'),
    "--timeout=$TimeoutSeconds", "--width=$Width", "--height=$Height")
if ($ReportPath) { $arguments += '"--report=' + (Resolve-Path -LiteralPath $ReportPath).Path + '"' }
if ($RepositoryPath) { $arguments += '"--repository=' + (Resolve-Path -LiteralPath $RepositoryPath).Path + '"' }
$process = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/javaw.exe') -ArgumentList $arguments `
    -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $markerPath 'window.log') `
    -RedirectStandardError (Join-Path $markerPath 'window-error.log')
Write-Output "Test-only window process: $($process.Id)"
$process.WaitForExit()
