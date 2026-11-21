#requires -Version 5.1
<#
.SYNOPSIS
  Capture a full Java thread dump while the game is frozen (hung, not crashed).

.DESCRIPTION
  When Minecraft joins a world, JEI does a full recipe registration. On a big pack with a
  high render/simulation distance and little free RAM this can stall for minutes and the log
  only shows "Registering recipes ... is running and has taken N minutes" with no exception.
  The only useful evidence at that point is the thread stacks.

  Run this from a second PowerShell while the game window is not responding:
      .\tools\dump-game-thread.ps1
  Defaults to the 1.20.1-Forge_47.4.20 instance; override with -Version.

.NOTES
  Output: <instance>\logs\thread-dump-yyyyMMdd-HHmmss.txt
  The two heads that matter are "Render thread" (client) and "Server thread" (integrated server).
#>
[CmdletBinding()]
param(
    [string]$Version = '1.20.1-Forge_47.4.20',
    [string]$JavaHome = 'D:\Java\zulu17.58.21-ca-jdk17.0.15-win_x64'
)

$ErrorActionPreference = 'Stop'

$jstack = Join-Path $JavaHome 'bin\jstack.exe'
if (-not (Test-Path -LiteralPath $jstack)) {
    throw "jstack not found: $jstack (pass -JavaHome <jdk dir>)"
}

$proc = Get-CimInstance Win32_Process |
    Where-Object {
        ($_.Name -eq 'javaw.exe' -or $_.Name -eq 'java.exe') -and
        $_.CommandLine -and
        $_.CommandLine.Contains($Version)
    } |
    Select-Object -First 1

if (-not $proc) {
    Write-Host "No running $Version process found. Start the game, then rerun while it is frozen."
    exit 1
}

$instance = 'D:\' + $Version
$m = [regex]::Match($proc.CommandLine, '--gameDir\s+"?([^"]+?)"?(\s+--|$)')
if ($m.Success) {
    $instance = $m.Groups[1].Value.Trim()
}

$outDir = Join-Path $instance 'logs'
if (-not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $outDir ('thread-dump-' + $stamp + '.txt')

Write-Host "PID $($proc.ProcessId) -> $out"
& $jstack -l $proc.ProcessId 2>&1 | Out-File -LiteralPath $out -Encoding utf8
if ($LASTEXITCODE -ne 0) {
    Write-Host "jstack failed (exit $LASTEXITCODE), retrying with -F ..."
    & $jstack -F -l $proc.ProcessId 2>&1 | Out-File -LiteralPath $out -Encoding utf8
}

$lines = Get-Content -LiteralPath $out
foreach ($thread in @('"Render thread"', '"Server thread"')) {
    $idx = ($lines | Select-String -SimpleMatch $thread | Select-Object -First 1).LineNumber
    if ($idx) {
        Write-Host ''
        Write-Host "===== $thread ====="
        $end = [Math]::Min($idx + 18, $lines.Count - 1)
        $lines[($idx - 1)..$end] | ForEach-Object { "  $_" }
    }
}

Write-Host ''
Write-Host "Full dump: $out"
