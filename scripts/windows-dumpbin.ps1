function Resolve-WindowsDumpbin {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$VsWherePath)

    if (-not (Test-Path -LiteralPath $VsWherePath -PathType Leaf)) {
        throw 'vswhere is unavailable; runtime dependency check is required'
    }
    # SSMS and other VS Installer products may be newer than Build Tools.
    # Filter for the C++ tool component before asking vswhere for -latest.
    $candidates = @(& $VsWherePath -products '*' `
        -requires 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64' `
        -latest -find 'VC\Tools\MSVC\**\Hostx64\x64\dumpbin.exe')
    $selected = $null
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            $selected = $candidate
        }
    }
    if ($selected) { return (Resolve-Path -LiteralPath $selected).Path }
    throw 'dumpbin is unavailable; runtime dependency check is required'
}
