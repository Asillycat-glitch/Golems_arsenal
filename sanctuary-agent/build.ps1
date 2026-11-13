param(
    [string]$AsmJar = (Join-Path $PSScriptRoot 'lib\asm-9.9.1.jar')
)

# Builds:
#   sanctuary-bootstrap.jar : GuardRuntime only, appended to the bootstrap classpath
#   sanctuary-agent.jar     : everything else + shaded ASM + the bootstrap stub as a resource
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here 'out'
$classes = Join-Path $out 'classes'
$stage = Join-Path $out 'stage'
$shade = Join-Path $out 'shade'
$agentJar = Join-Path $here 'sanctuary-agent.jar'
$stubJar = Join-Path $here 'sanctuary-bootstrap.jar'

if (Test-Path $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes, $stage, $shade | Out-Null

$src = Get-ChildItem -Path (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
& $javac -encoding UTF-8 -source 17 -target 17 -cp $AsmJar -d $classes @src
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

# 1) bootstrap stub: GuardRuntime only.
if (Test-Path $stubJar) { Remove-Item -LiteralPath $stubJar -Force }
& (Join-Path $env:JAVA_HOME 'bin\jar.exe') --create --file $stubJar -C $classes 'sanctuary/agent/GuardRuntime.class'
if ($LASTEXITCODE -ne 0) { throw "stub jar failed ($LASTEXITCODE)" }

# 2) stage everything except GuardRuntime (it must only exist in the bootstrap stub).
Get-ChildItem -Path $classes -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($classes.Length).TrimStart('\')
    if ($rel -eq 'sanctuary\agent\GuardRuntime.class') { return }
    $target = Join-Path $stage $rel
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $target -Force
}
Copy-Item -LiteralPath $stubJar -Destination (Join-Path $stage 'sanctuary-bootstrap.jar') -Force
$native = Join-Path $here 'sanctuary-win-x86_64.arcane'
if (Test-Path $native) { Copy-Item -LiteralPath $native -Destination (Join-Path $stage 'sanctuary-win-x86_64.arcane') -Force }

# 3) shade ASM.
Push-Location $shade
& (Join-Path $env:JAVA_HOME 'bin\jar.exe') xf $AsmJar
Pop-Location
Remove-Item -LiteralPath (Join-Path $shade 'META-INF') -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -Path (Join-Path $shade '*') -Destination $stage -Recurse -Force -ErrorAction SilentlyContinue

$manifest = Join-Path $out 'MANIFEST.MF'
@(
    'Manifest-Version: 1.0'
    'Premain-Class: sanctuary.agent.SanctuaryAgent'
    'Agent-Class: sanctuary.agent.SanctuaryAgent'
    'Can-Retransform-Classes: true'
    'Can-Redefine-Classes: true'
) | Set-Content -LiteralPath $manifest -Encoding ascii

if (Test-Path $agentJar) { Remove-Item -LiteralPath $agentJar -Force }
& (Join-Path $env:JAVA_HOME 'bin\jar.exe') --create --file $agentJar --manifest $manifest -C $stage .
if ($LASTEXITCODE -ne 0) { throw "agent jar failed ($LASTEXITCODE)" }

Write-Host ("built " + $agentJar + " (" + (Get-Item $agentJar).Length + " bytes)")
Write-Host ("built " + $stubJar + " (" + (Get-Item $stubJar).Length + " bytes)")
