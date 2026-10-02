<#
.SYNOPSIS
  Removes the "JobRadar Daily Crawl" scheduled task. Leaves the data directory untouched.
#>
[CmdletBinding(SupportsShouldProcess)]
param([string] $TaskName = "JobRadar Daily Crawl")

$ErrorActionPreference = "Stop"
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    if ($PSCmdlet.ShouldProcess($TaskName, "Unregister scheduled task")) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "Removed '$TaskName'"
    }
} else {
    Write-Host "No task named '$TaskName'"
}
