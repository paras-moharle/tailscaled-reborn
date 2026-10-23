<#
.SYNOPSIS
    Send one or more files/folders to an online Tailscale peer.
    Installed by TailBridge for Windows at %LOCALAPPDATA%\TailBridge\.

.DESCRIPTION
    This script is the command behind the File Explorer context menu
    "Send to Tailscale peer". It lists online Tailscale peers, lets you
    pick one when more than one is online, and runs `tailscale file cp`
    for every path passed on the command line.

.PARAMETER Path
    One or more file or folder paths passed by Windows Explorer.

.NOTES
    Safe location: %LOCALAPPDATA%\TailBridge\
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$Path
)

$ErrorActionPreference = 'Stop'
$Script:TBDir   = "$env:LOCALAPPDATA\TailBridge"
$Script:LogFile = "$Script:TBDir\logs\send.log"

if (-not (Test-Path -Path $Script:TBDir)) { New-Item -ItemType Directory -Path $Script:TBDir -Force | Out-Null }
if (-not (Test-Path -Path (Split-Path -Parent $Script:LogFile))) { New-Item -ItemType Directory -Path (Split-Path -Parent $Script:LogFile) -Force | Out-Null }

function Write-Log {
    param([string]$Message)
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    "$stamp  $Message" | Out-File -FilePath $Script:LogFile -Append -Encoding UTF8
}

function Find-TailscaleExe {
    $candidates = @(
        (Get-Command 'tailscale' -ErrorAction SilentlyContinue).Source,
        "$env:ProgramFiles\Tailscale\tailscale.exe",
        "$env:ProgramFiles(x86)\Tailscale\tailscale.exe"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -Path $c -PathType Leaf)) { return $c }
    }
    return $null
}

function Show-PeerPicker {
    param([pscustomobject[]]$Peers)
    Add-Type -AssemblyName System.Windows.Forms
    $form = New-Object System.Windows.Forms.Form
    $form.Text         = 'TailBridge — choose peer'
    $form.Size         = New-Object System.Drawing.Size(420, 320)
    $form.StartPosition = 'CenterScreen'
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false

    $lbl = New-Object System.Windows.Forms.Label
    $lbl.Text = 'More than one peer is online. Choose where to send:'
    $lbl.Location = New-Object System.Drawing.Point(12, 12)
    $lbl.AutoSize = $true
    $form.Controls.Add($lbl)

    $list = New-Object System.Windows.Forms.ListBox
    $list.Location = New-Object System.Drawing.Point(12, 40)
    $list.Size = New-Object System.Drawing.Size(380, 200)
    foreach ($p in $Peers) {
        $list.Items.Add("$($p.Name)  ($($p.IP))") | Out-Null
    }
    $form.Controls.Add($list)

    $btnOK = New-Object System.Windows.Forms.Button
    $btnOK.Text = 'OK'
    $btnOK.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $btnOK.Location = New-Object System.Drawing.Point(232, 256)
    $form.Controls.Add($btnOK)

    $btnCancel = New-Object System.Windows.Forms.Button
    $btnCancel.Text = 'Cancel'
    $btnCancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
    $btnCancel.Location = New-Object System.Drawing.Point(312, 256)
    $form.Controls.Add($btnCancel)

    $form.AcceptButton = $btnOK
    $form.CancelButton = $btnCancel

    $result = $form.ShowDialog()
    if ($result -eq [System.Windows.Forms.DialogResult]::OK -and $list.SelectedIndex -ge 0) {
        return $Peers[$list.SelectedIndex]
    }
    return $null
}

try {
    $tailscale = Find-TailscaleExe
    if (-not $tailscale) {
        Write-Log 'ERROR: tailscale.exe not found in PATH or default install locations.'
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show('Tailscale is not installed or cannot be found.', 'TailBridge', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
        exit 1
    }

    $raw = & $tailscale status --json 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Log "ERROR: tailscale status failed: $raw"
        throw 'tailscale status failed'
    }

    $status = $raw | ConvertFrom-Json -ErrorAction Stop
    $peerList = @()

    # tailscale status --json may expose Peer as an array or as a map keyed by node ID.
    $peerContainer = $status.Peer
    if ($null -ne $peerContainer) {
        if ($peerContainer -is [array]) {
            $peerItems = $peerContainer
        } else {
            $peerItems = $peerContainer.psobject.Properties | ForEach-Object { $_.Value }
        }
        foreach ($p in $peerItems) {
            if ($null -eq $p.Online) { continue }
            if ($p.Online -eq $true) {
                $name = if ($p.DNSName) { $p.DNSName -replace '\.$','' } else { 'unknown' }
                $ip   = if ($p.TailscaleIPs -and $p.TailscaleIPs.Count -gt 0) { $p.TailscaleIPs[0] } else { '' }
                $peerList += [pscustomobject]@{ Name = $name; IP = $ip }
            }
        }
    }

    if ($peerList.Count -eq 0) {
        Write-Log 'ERROR: no online Tailscale peers found.'
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show('No online Tailscale peers found.', 'TailBridge', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Warning)
        exit 1
    }

    $chosen = if ($peerList.Count -eq 1) { $peerList[0] } else { Show-PeerPicker -Peers $peerList }
    if (-not $chosen) {
        Write-Log 'User cancelled peer selection.'
        exit 0
    }

    Write-Log "Selected peer: $($chosen.Name) ($($chosen.IP))"

    foreach ($target in $Path) {
        if (-not (Test-Path -Path $target)) {
            Write-Log "SKIP: path does not exist: $target"
            continue
        }
        Write-Log "Sending: $target  ->  $($chosen.Name)"
        & $tailscale file cp $target "$($chosen.Name):" 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Log "OK: $target"
        } else {
            Write-Log "FAILED: $target"
        }
    }
}
catch {
    Write-Log "FATAL: $($_.Exception.Message)"
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show("TailBridge error:`n`n$($_.Exception.Message)", 'TailBridge', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
    exit 1
}
