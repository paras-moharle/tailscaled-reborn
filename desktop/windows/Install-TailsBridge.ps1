<#
.SYNOPSIS
    Installs TailBridge for Windows: File Explorer context menu, logs dir,
    and an always-on receive watcher so phone→desktop clips land within ~5s.

.DESCRIPTION
    Creates %LOCALAPPDATA%\TailBridge\ and registers "Send to Tailscale peer"
    for files and folders in File Explorer.

    Safe location: %LOCALAPPDATA%\TailBridge\

.PARAMETER InstallReceiveWatcher
    Register a Scheduled Task that runs Receive-TailscaleFiles.ps1 -Watch
    at logon. The watcher polls tailscale every 5 seconds, so a clipboard
    sent from your phone lands on the Windows clipboard within about five
    seconds.
#>
[CmdletBinding()]
param(
    [switch]$InstallReceiveWatcher
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

    if ($InstallReceiveWatcher) {
        Write-Log 'Registering receive watcher scheduled task (2 s poll)...'
        $receive  = "$Script:TBDir\Receive-TailscaleFiles.ps1"
        $action   = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-ExecutionPolicy Bypass -NoProfile -File `"$receive`" -Watch"
        $trigger  = New-ScheduledTaskTrigger -AtLogOn
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RunOnlyIfNetworkAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
        $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
        $task = New-ScheduledTask -Action $action -Trigger $trigger -Principal $principal -Settings $settings
        Register-ScheduledTask -TaskName 'TailBridge Receive Watcher' -InputObject $task -Force | Out-Null
        Write-Log 'Scheduled task "TailBridge Receive Watcher" registered.'
        Write-Log "It polls for incoming files every 2 seconds — phone→desktop clips appear almost instantly."
    }

    Write-Log 'Install complete.'
    Write-Log ""
    Write-Log "Scripts:        $Script:TBDir\"
    Write-Log "Right-click any file or folder -> 'Send to Tailscale peer'."
    Write-Log "To push clipboard, run: $Script:TBDir\Push-TailscaleClipboard.ps1"
    if ($InstallReceiveWatcher) {
        Write-Log "Incoming clips are pulled automatically by the watcher (~2 s latency)."
    } else {
        Write-Log "To pull incoming clips manually, run: $Script:TBDir\Receive-TailscaleFiles.ps1"
        Write-Log "To watch continuously, run:   $Script:TBDir\Receive-TailscaleFiles.ps1 -Watch"
    }
}
catch {
    Write-Log "FATAL: $($_.Exception.Message)"
    exit 1
}
