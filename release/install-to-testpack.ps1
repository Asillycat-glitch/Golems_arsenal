param(
    [string]$ModsDir = 'D:\我自己的包\.minecraft\versions\1.20.1-Forge_47.4.20\mods'
)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $dir 'golems_arsenal-0.3.jar'
$dst = Join-Path $ModsDir 'golems_arsenal-c2c3-forceregister.jar'
if (!(Test-Path -LiteralPath $src)) { throw "missing $src" }
if (!(Test-Path -LiteralPath $ModsDir)) { throw "missing mods dir $ModsDir" }

if (Test-Path -LiteralPath $dst) {
    $bak = "$dst.bak-$(Get-Date -Format yyyyMMdd-HHmmss)"
    Copy-Item -LiteralPath $dst -Destination $bak -Force
    Write-Host "backup: $bak"
}
Copy-Item -LiteralPath $src -Destination $dst -Force
Write-Host "installed: $dst"
Write-Host "start the game, then look for [Sanctuary] guard thread / supervisor started"
