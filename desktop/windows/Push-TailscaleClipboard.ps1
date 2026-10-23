<#
.SYNOPSIS
    Push the current Windows clipboard text to an online Tailscale peer.
    The peer's phone/desktop receives it as a clipboard entry automatically.

.DESCRIPTION
    Reads Get-Clipboard, wraps the text in the TBCLIP1 clipboard format used
    by TailBridge, writes a temporary .clip file, and sends it with
    `tailscale file cp`. The receiving side (Android or another TailBridge
    host) detects TBCLIP1 and sets its clipboard instead of saving a file.

    Safe location: %LOCALAPPDATA%\TailBridge\
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$Script:TBDir   = "$env:LOCALAPPDATA\TailBridge"
$Script:LogFile = "$Script:TBDir\logs\push.log"
$Script:TempDir = "$Script:TBDir\Temp"

if (-not (Test-Path -Path $Script:TBDir)) { New-Item -ItemType Directory -Path $Script:TBDir -Force | Out-Null }
if (-not (Test-Path -Path (Split-Path -Parent $Script:LogFile))) { New-Item -ItemType Directory -Path (Split-Path -Parent $Script:LogFile) -Force | Out-Null }
if (-not (Test-Path -Path $Script:TempDir)) { New-Item -ItemType Directory -Path $Script:TempDir -Force | Out-Null }

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
    $form.Size         = New-Object System.Drawing.Size(420, 300)
    $form.StartPosition = 'CenterScreen'
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false

    $lbl = New-Object System.Windows.Forms.Label
    $lbl.Text = 'More than one peer is online. Choose where to send the clipboard:'
    $lbl.Location = New-Object System.Drawing.Point(12, 12)
    $lbl.AutoSize = $true
    $form.Controls.Add($lbl)

    $list = New-Object System.Windows.Forms.ListBox
    $list.Location = New-Object System.Drawing.Point(12, 40)
    $list.Size = New-Object System.Drawing.Size(380, 180)
    foreach ($p in $Peers) { $list.Items.Add("$($p.Name)  ($($p.IP))") | Out-Null }
    $form.Controls.Add($list)

    $btnOK = New-Object System.Windows.Forms.Button
    $btnOK.Text = 'OK'; $btnOK.DialogResult = 'OK'; $btnOK.Location = New-Object System.Drawing.Point(232, 236)
    $form.Controls.Add($btnOK)

    $btnCancel = New-Object System.Windows.Forms.Button
    $btnCancel.Text = 'Cancel'; $btnCancel.DialogResult = 'Cancel'; $btnCancel.Location = New-Object System.Drawing.Point(312, 236)
    $form.Controls.Add($btnCancel)

    $form.AcceptButton = $btnOK
    $form.CancelButton = $btnCancel

    if ($form.ShowDialog() -eq 'OK' -and $list.SelectedIndex -ge 0) {
        return $Peers[$list.SelectedIndex]
    }
    return $null
}

try {
    $tailscale = Find-TailscaleExe
    if (-not $tailscale) { Write-Log 'ERROR: tailscale.exe not found.'; throw }

    $raw = & $tailscale status --json 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'tailscale status failed' }
    $status = $raw | ConvertFrom-Json -ErrorAction Stop

    $peerList = @()
    $peerContainer = $status.Peer
    if ($null -ne $peerContainer) {
        $peerItems = if ($peerContainer -is [array]) { $peerContainer } else { $peerContainer.psobject.Properties | ForEach-Object { $_.Value } }
        foreach ($p in $peerItems) {
            if ($p.Online -eq $true) {
                $name = if ($p.DNSName) { $p.DNSName -replace '\.$','' } else { 'unknown' }
                $ip   = if ($p.TailscaleIPs -and $p.TailscaleIPs.Count -gt 0) { $p.TailscaleIPs[0] } else { '' }
                $peerList += [pscustomobject]@{ Name = $name; IP = $ip }
            }
        }
    }

    if ($peerList.Count -eq 0) { Write-Log 'ERROR: no online peers.'; throw 'no online peers' }
    $chosen = if ($peerList.Count -eq 1) { $peerList[0] } else { Show-PeerPicker -Peers $peerList }

    if (-not (Get-Clipboard -ErrorAction SilentlyContinue)) {
        Write-Log 'Clipboard is empty or not text.'
        throw 'clipboard empty'
    }
    $text = Get-Clipboard -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($text)) { Write-Log 'Clipboard contains no text.'; throw 'no text' }

    $epoch = [int64]((Get-Date).ToUniversalTime() - (Get-Date "1970-01-01").ToUniversalTime()).TotalSeconds
    $b64   = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($text))
    $clip  = "$Script:TempDir\clip-$epoch.clip"
    @"
TBCLIP1
{"t":$epoch,"s":"windows"}
$b64
"@ | Set-Content -Path $clip -Encoding UTF8 -NoNewline

    Write-Log "Pushing clipboard to $($chosen.Name)"
    & $tailscale file cp $clip "$($chosen.Name):" 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Log "OK: clipboard sent to $($chosen.Name)"
    } else {
        Write-Log "FAILED: clipboard send to $($chosen.Name)"
        throw 'file cp failed'
    }
    Remove-Item -Path $clip -Force -ErrorAction SilentlyContinue
}
catch {
    Write-Log "FATAL: $($_.Exception.Message)"
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show("TailBridge clipboard error:`n`n$($_.Exception.Message)", 'TailBridge', [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
    exit 1
}
