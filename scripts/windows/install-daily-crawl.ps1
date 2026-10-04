<#
.SYNOPSIS
  Registers a Windows scheduled task that runs one JobRadar crawl every day.

.DESCRIPTION
  Creates (or replaces) the task "JobRadar Daily Crawl" for the current user. The task runs
  run-crawl.cmd, which starts JobRadar in its one-shot "crawl" profile against an H2 database in
  -DataDir and appends output to -DataDir\logs\crawl-<date>.log. The process exits 0 if at least one
  board was read, 1 otherwise, so the task history shows failed crawls.

  The Java runtime is checked to be version 21 or newer and its full path is written to
  -DataDir\jobradar-env.cmd, so the task doesn't depend on whichever "java" is first on PATH (or on a
  JAVA_HOME left pointing at an older JDK). export-postings.cmd reads the same file.

  If the PC is asleep or off at the scheduled time, the task runs at the next opportunity
  (StartWhenAvailable). Nothing runs elevated.

.EXAMPLE
  .\install-daily-crawl.ps1 -JarPath C:\jobradar\app\jobradar.jar -DataDir C:\jobradar\data -At 07:00
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [string] $JarPath,
    [Parameter(Mandatory)] [string] $DataDir,
    [string] $At = "07:00",
    [string] $JavaExe,
    [string] $TaskName = "JobRadar Daily Crawl"
)

$ErrorActionPreference = "Stop"

$JarPath = (Resolve-Path $JarPath).Path
if (-not (Test-Path $DataDir)) {
    New-Item -ItemType Directory -Path $DataDir | Out-Null
}
$DataDir = (Resolve-Path $DataDir).Path
$Runner = Join-Path $PSScriptRoot "run-crawl.cmd"
if (-not (Test-Path $Runner)) {
    throw "run-crawl.cmd not found next to this script ($Runner)"
}

if (-not $JavaExe) {
    $found = Get-Command java -ErrorAction SilentlyContinue
    if (-not $found) {
        throw "java was not found on PATH. Install a Java 21 runtime or pass -JavaExe."
    }
    $JavaExe = $found.Source
}
# "java -version" prints to stderr; under ErrorActionPreference=Stop, PowerShell 5.1 turns native stderr into a
# terminating error, so capture it through cmd instead.
$versionText = (cmd /c "`"$JavaExe`" -version 2>&1") -join "`n"
if ($versionText -notmatch 'version "(\d+)') {
    throw "Could not read the Java version from $JavaExe :`n$versionText"
}
if ([int]$Matches[1] -lt 21) {
    throw "JobRadar needs Java 21 or newer; $JavaExe is Java $($Matches[1]). Pass -JavaExe <path to a Java 21 java.exe>."
}

$envFile = Join-Path $DataDir "jobradar-env.cmd"
@(
    "@rem Written by install-daily-crawl.ps1",
    "set `"JOBRADAR_JAVA=$JavaExe`"",
    "set `"JOBRADAR_JAR=$JarPath`""
) | Set-Content -Path $envFile -Encoding ASCII

$action = New-ScheduledTaskAction -Execute "cmd.exe" `
    -Argument "/c `"`"$Runner`" `"$JarPath`" `"$DataDir`"`"" `
    -WorkingDirectory $DataDir
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew

if ($PSCmdlet.ShouldProcess($TaskName, "Register scheduled task")) {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
        -Description "Daily JobRadar crawl of public job boards" -Force | Out-Null
    Write-Host "Registered '$TaskName': daily at $At, Java $($Matches[1]) at $JavaExe, data in $DataDir"
    Write-Host "Run it now with: Start-ScheduledTask -TaskName '$TaskName'"
}
