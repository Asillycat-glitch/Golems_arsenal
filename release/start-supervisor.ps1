param(
    [Parameter(Mandatory = $true)][long]$GamePid,
    [string]$Restart = ""
)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $dir 'sanctuary-supervisor.jar'
if (!(Test-Path -LiteralPath $jar)) { throw "missing $jar" }

$java = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
if (!(Test-Path -LiteralPath $java)) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
if (!(Test-Path -LiteralPath $java)) { throw 'JAVA_HOME is not set correctly' }

$log = Join-Path $dir 'supervisor.log'
$args = @('-jar', $jar, '--pid', "$GamePid", '--journal', $log)
if ($Restart) { $args += @('--restart', $Restart) }

Start-Process -FilePath $java -ArgumentList $args -WindowStyle Hidden
Write-Host "supervisor started for pid=$GamePid"
Write-Host "journal: $log"
