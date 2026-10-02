<#
.SYNOPSIS
  Registers a Windows scheduled task that runs one JobRadar crawl every day.

.DESCRIPTION
  Creates (or replaces) the task "JobRadar Daily Crawl" for the current user. The task runs
  run-crawl.cmd, which starts JobRadar in its one-shot "crawl" profile against an H2 database in
  -DataDir and appends output to -DataDir\logs\crawl-<date>.log. The process exits 0 if at least one
  board was read, 1 otherwise, so the task history shows failed crawls.

  If the PC is asleep or off at the scheduled time, the task runs at the next opportunity
  (StartWhenAvailable). Nothing runs elevated.

.EXAMPLE
  .\install-daily-crawl.ps1 -JarPath C:\jobradar\jobradar.jar -DataDir C:\jobradar\data -At 07:00
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [string] $JarPath,
    [Parameter(Mandatory)] [string] $DataDir,
    [string] $At = "07:00",
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

# Fail early with a clear message instead of a silent task failure at 7am.
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    throw "java was not found on PATH. Install a Java 21 runtime first."
}

$action = New-ScheduledTaskAction -Execute "cmd.exe" `
    -Argument "/c `"`"$Runner`" `"$JarPath`" `"$DataDir`"`"" `
    -WorkingDirectory $DataDir
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew

if ($PSCmdlet.ShouldProcess($TaskName, "Register scheduled task")) {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
        -Description "Daily JobRadar crawl of public job boards" -Force | Out-Null
    Write-Host "Registered '$TaskName': daily at $At, data in $DataDir"
    Write-Host "Run it now with: Start-ScheduledTask -TaskName '$TaskName'"
}
