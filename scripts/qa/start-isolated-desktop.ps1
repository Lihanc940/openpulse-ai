#requires -Version 7.0
# Maintainer-only launcher/observer. Does not modify the machine's tools or settings.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ImageDirectory, [Parameter(Mandatory)][string]$EvidenceDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$image = (Resolve-Path -LiteralPath $ImageDirectory).Path
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $evidence) { throw 'Evidence directory must be new.' }
$temp = Join-Path $evidence 'temp'
New-Item -ItemType Directory $temp -Force | Out-Null
$start = [Diagnostics.ProcessStartInfo]::new((Join-Path $image 'OpenPulse.exe'))
$start.UseShellExecute = $false
$start.WorkingDirectory = $evidence
foreach ($key in @($start.Environment.Keys)) {
    if ($key -match '(?i)JAVA|JDK|CLASSPATH|MAVEN|GRADLE|OPENPULSE|VSINSTALL|VCINSTALL|VCTOOLS|MYSQL|DATABASE|SPRING') {
        [void]$start.Environment.Remove($key)
    }
}
$start.Environment['PATH'] = Join-Path $env:SystemRoot 'System32'
$start.Environment['TEMP'] = $temp; $start.Environment['TMP'] = $temp
foreach ($key in @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY')) { $start.Environment[$key] = 'http://127.0.0.1:9' }
$start.Environment['NO_PROXY'] = ''
$qaProfile = Join-Path $evidence 'empty-profile'
foreach ($key in @('APPDATA','LOCALAPPDATA')) {
    $directory = Join-Path $qaProfile $key
    New-Item -ItemType Directory $directory -Force | Out-Null
    $start.Environment[$key] = $directory
}
$process = [Diagnostics.Process]::Start($start)
Write-Host "Isolated native launcher PID: $($process.Id)"
$children = @{}; $nativeModules = @{}; $tcpSeen = @{}; $observations = 0
$appProcesses = @{}; $appProcesses["$($process.Id)"] = $true
while (-not $process.WaitForExit(500)) {
    $observations++
    # On Windows jpackage has a waiting parent launcher and a second same-image
    # process that hosts the JVM. Observe that owned tree, not just the launcher PID.
    $snapshot = @(Get-CimInstance Win32_Process -Filter "Name='OpenPulse.exe' OR Name='openpulse-analyzer.exe'")
    foreach ($item in $snapshot) {
        if ($item.ExecutablePath -ieq $start.FileName -and $appProcesses.ContainsKey("$($item.ParentProcessId)")) {
            $appProcesses["$($item.ProcessId)"] = $true
        }
    }
    foreach ($child in $snapshot) {
        if ($child.Name -ieq 'openpulse-analyzer.exe' -and $appProcesses.ContainsKey("$($child.ParentProcessId)")) {
            $children["$($child.ProcessId)"] = [ordered]@{ pid=$child.ProcessId; path=$child.ExecutablePath; commandLine=$child.CommandLine }
        }
    }
    foreach ($appPid in @($appProcesses.Keys)) {
        try {
            $observed = Get-Process -Id ([int]$appPid) -ErrorAction Stop
            foreach ($module in $observed.Modules) {
                if ($module.ModuleName -match '^(jvm|java|glass|prism_.*|jimage|jli)\.dll$') { $nativeModules[$module.FileName] = $true }
            }
        } catch { Write-Warning "Process $appPid exited during observation." }
        # Observation is best-effort sampling, not a firewall or proof of zero transient traffic.
        foreach ($connection in @(Get-NetTCPConnection -OwningProcess ([int]$appPid) -ErrorAction SilentlyContinue)) {
            $key = "$($connection.LocalAddress):$($connection.LocalPort)->$($connection.RemoteAddress):$($connection.RemotePort) $($connection.State)"
            $tcpSeen[$key] = $true
        }
    }
}
$remaining = @($children.Keys | Where-Object { Get-Process -Id ([int]$_) -ErrorAction SilentlyContinue })
$remainingApps = @($appProcesses.Keys | Where-Object { Get-Process -Id ([int]$_) -ErrorAction SilentlyContinue })
$result = [ordered]@{ image=$image; pid=$process.Id; exitCode=$process.ExitCode; samples=$observations;
    path=$start.Environment['PATH']; externalJavaVariablesRemoved=$true; privateTemp=$temp;
    emptyApplicationData=$qaProfile; nativeModules=@($nativeModules.Keys | Sort-Object);
    appProcessIds=@($appProcesses.Keys); remainingApplicationProcesses=$remainingApps;
    analyzerProcesses=@($children.Values); remainingObservedChildren=$remaining;
    remainingTempDirectories=@(Get-ChildItem -LiteralPath $temp -Directory | ForEach-Object Name);
    observedTcpEndpoints=@($tcpSeen.Keys); networkLimitation='Live OS network; sampling can miss transient traffic; no firewall/adapter changes.' }
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'window-process-evidence.json') -Encoding utf8
$process.Dispose()
Write-Host "Closed. Evidence: $evidence"
