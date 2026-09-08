param([Parameter(Mandatory=$true)][string]$Repository)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath $Repository).Path
$cliHome = Join-Path $repoPath 'wdl-cli\build\install\wdl'
$lspHome = Join-Path $repoPath 'wdl-lsp\build\install\wdl-lsp'
foreach ($launcher in @((Join-Path $cliHome 'bin\wdl.bat'), (Join-Path $lspHome 'bin\wdl-lsp.bat'))) {
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw "Missing launcher: $launcher" }
}
# Preserve the raw user PATH, including expandable %VARIABLES%; never copy the machine PATH.
$key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
try {
    $oldPath = $key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    $oldHomes = @($key.GetValue('WDL_HOME'), $key.GetValue('WDL_LSP_HOME'), $key.GetValue('WDL_DAP_HOME'))
    $bins = @((Join-Path $cliHome 'bin'), (Join-Path $lspHome 'bin'), (Join-Path $dapHome 'bin'))
    $remove = @($bins) + @($oldHomes | Where-Object { $_ } | ForEach-Object { Join-Path $_ 'bin' })
    $entries = @($oldPath -split ';' | Where-Object { $_ -and $_.TrimEnd('\') -notin $remove })
    $key.SetValue('WDL_HOME', $cliHome, [Microsoft.Win32.RegistryValueKind]::String)
    $key.SetValue('WDL_LSP_HOME', $lspHome, [Microsoft.Win32.RegistryValueKind]::String)
    $key.SetValue('WDL_DAP_HOME', $dapHome, [Microsoft.Win32.RegistryValueKind]::String)
    $key.SetValue('Path', (($bins + $entries) -join ';'), [Microsoft.Win32.RegistryValueKind]::ExpandString)
} finally { $key.Dispose() }
# Let newly launched applications pick up the changed user environment.
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class WdlEnvironmentBroadcast {
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr SendMessageTimeout(IntPtr h, uint m, UIntPtr w, string l, uint f, uint t, out UIntPtr r);
}
'@
$broadcastResult = [UIntPtr]::Zero
[void][WdlEnvironmentBroadcast]::SendMessageTimeout([IntPtr]0xffff, 0x1a, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$broadcastResult)
Write-Host "Installed WDL from $repoPath. Restart your terminal and IDEA. Java 21+ must be available."
