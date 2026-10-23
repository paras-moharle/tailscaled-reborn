<#
.SYNOPSIS
    Installs TailBridge for Windows: File Explorer context menu, logs dir,
    and optional scheduled task that pulls incoming clipboard clips.

.DESCRIPTION
    Creates %LOCALAPPDATA%\TailBridge\ and registers "Send to Tailscale peer"
    for files and folders in File Explorer.

    Safe location: %LOCALAPPDATA%\TailBridge\

.PARAMETER InstallReceiveTask
    Register a Scheduled Task that runs Receive-TailscaleFiles.ps1 at logon
    and every 5 minutes, so phone→desktop clipboard clips land automatically.
#>
[CmdletBinding()]
param(
    [switch]$InstallReceiveTask
)

$ErrorActionPreference = 'Stop'
$Script:TBDir = "$env:LOCALAPPDATA\TailBridge"

if (-not (Test-Path -Path $Script:TBDir)) { New-Item -ItemType Directory -Path $Script:TBDir -Force | Out-Null }
$dirs = @('logs','Received','Temp')
foreach ($d in $dirs) {
    $p = Join-Path $Script:TBDir $d
    if (-not (Test-Path -Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
}

function Write-Log {
    param([string]$Message)
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    "$stamp  $Message"
}

# --- registry helper ---------------------------------------------------------
function Set-ShellCommand {
    param([string]$RootKey, [string]$MenuName, [string]$Command)
    $base = "HKCR:\$RootKey\shell\$MenuName"
    if (-not (Test-Path -Path $base)) { New-Item -Path $base -Force | Out-Null }
    Set-ItemProperty -Path $base -Name '' -Value 'Send to Tailscale peer' -Force
    Set-ItemProperty -Path $base -Name 'FriendlyName' -Value 'Send to Tailscale peer' -Force
    Set-ItemProperty -Path $base -Name 'MultiSelectModel' -Value 'DocumentList' -Force

    $cmd = Join-Path $base 'command'
    if (-not (Test-Path -Path $cmd)) { New-Item -Path $cmd -Force | Out-Null }
    Set-ItemProperty -Path $cmd -Name '' -Value $Command -Force
}

try {
    $ps1 = "$Script:TBDir\Send-ToTailscalePeer.ps1"
    $cmd = "powershell.exe -ExecutionPolicy Bypass -NoProfile -File `"$ps1`" `"%*`""

    Write-Log 'Registering File Explorer context menu for files (*)...'
    Set-ShellCommand -RootKey '*' -MenuName 'Send to Tailscale peer' -Command $cmd

    Write-Log 'Registering File Explorer context menu for folders (Directory)...'
    Set-ShellCommand -RootKey 'Directory' -MenuName 'Send to Tailscale peer' -Command $cmd

    if ($InstallReceiveTask) {
        Write-Log 'Registering receive scheduled task...'
        $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-ExecutionPolicy Bypass -NoProfile -File `"$Script:TBDir\Receive-TailscaleFiles.ps1`""
        $trigger1 = New-ScheduledTaskTrigger -AtLogOn
        $trigger2 = New-ScheduledTaskTrigger -Daily -At 9am -RandomDelay 00:05:00
        $trigger3 = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration ([TimeSpan]::MaxValue)
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RunOnlyIfNetworkAvailable
        $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
        $task = New-ScheduledTask -Action $action -Trigger $trigger1,$trigger2,$trigger3 -Principal $principal -Settings $settings
        Register-ScheduledTask -TaskName 'TailBridge Receive Files' -InputObject $task -Force | Out-Null
        Write-Log 'Scheduled task "TailBridge Receive Files" registered.'
    }

    Write-Log 'Install complete.'
    Write-Log ""
    Write-Log "Scripts:        $Script\TBDir\"
    Write-Log "Right-click any file or folder -> 'Send to Tailscale peer'."
    Write-Log "To push clipboard, run: $Script:TBDir\Push-TailscaleClipboard.ps1"
    if ($InstallReceiveTask) {
        Write-Log "Incoming clips are pulled automatically by the scheduled task."
    } else {
        Write-Log "To pull incoming clips manually, run: $Script:TBDir\Receive-TailscaleFiles.ps1"
    }
}
catch {
    Write-Log "FATAL: $($_.Exception.Message)"
    exit 1
}
