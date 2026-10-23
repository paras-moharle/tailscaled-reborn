<#
.SYNOPSIS
    Pull pending Tailscale file transfers and apply clipboard clips.

.DESCRIPTION
    Runs `tailscale file get` to fetch any files peers have sent to this
    machine, then scans the staging folder for TBCLIP1 clipboard files.
    When one is found, the decoded text is placed on the Windows clipboard
    via Set-Clipboard and the .clip file is removed.

    Safe location: %LOCALAPPDATA%\TailBridge\

    Can be run once, or scheduled (e.g. Task Scheduler every 5 minutes).
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$Script:TBDir     = "$env:LOCALAPPDATA\TailBridge"
$Script:LogFile   = "$Script:TBDir\logs\receive.log"
$Script:Received  = "$Script:TBDir\Received"

if (-not (Test-Path -Path $Script:TBDir)) { New-Item -ItemType Directory -Path $Script:TBDir -Force | Out-Null }
if (-not (Test-Path -Path $Script:Received)) { New-Item -ItemType Directory -Path $Script:Received -Force | Out-Null }
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

function Process-ClipFile {
    param([System.IO.FileInfo]$ClipFile)
    try {
        $raw   = Get-Content -Path $ClipFile.FullName -Encoding UTF8 -ErrorAction Stop
        $lines = $raw -split "`n"
        if ($lines[0].Trim() -ne 'TBCLIP1') { return $false }

        $metaLine = $lines[1].Trim()
        $meta     = $metaLine | ConvertFrom-Json -ErrorAction Stop
        $source   = if ($meta.s) { $meta.s } else { 'a peer' }

        $bodyLines = $lines[2..($lines.Length - 1)]
        $b64       = ($bodyLines -join "`n") -replace "`r",''
        $decoded   = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))

        Set-Clipboard -Value $decoded -ErrorAction Stop
        Write-Log "Clipboard received from $source — set to Windows clipboard."
        Remove-Item -Path $ClipFile.FullName -Force -ErrorAction SilentlyContinue
        return $true
    }
    catch {
        Write-Log "Failed to process clip file $($ClipFile.Name): $($_.Exception.Message)"
        return $false
    }
}

try {
    $tailscale = Find-TailscaleExe
    if (-not $tailscale) { Write-Log 'ERROR: tailscale.exe not found.'; throw }

    Push-Location $Script:Received
    & $tailscale file get 2>&1 | Out-Null
    Pop-Location

    $files = Get-ChildItem -Path $Script:Received -Filter '*.clip' -File -ErrorAction SilentlyContinue
    foreach ($f in $files) {
        Process-ClipFile -ClipFile $f
    }

    exit 0
}
catch {
    Write-Log "FATAL: $($_.Exception.Message)"
    exit 1
}
