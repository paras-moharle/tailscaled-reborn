# TailBridge for Windows — scripts only, no app

Everything lives in:

```
%LOCALAPPDATA%\TailBridge\
├── Send-ToTailscalePeer.ps1     # File Explorer context menu command
├── Receive-TailscaleFiles.ps1   # Pull incoming files (watch mode supported)
├── Install-TailsBridge.ps1      # Registers context menus + receive watcher
├── logs\                        # send.log, receive.log
└── Received\                    # files pulled from peers
```

## Install

Open PowerShell as a normal user and run:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
& "$env:LOCALAPPDATA\TailBridge\Install-TailsBridge.ps1" -InstallReceiveWatcher
```

What it does:

- Creates the directories above.
- Adds **"Send to Tailscale peer"** to the right-click menu for files and folders.
- Registers a Scheduled Task (`TailBridge Receive Watcher`) that runs at logon
  and polls `tailscale file get` every few seconds, so incoming files are
  fetched without opening anything by hand.

## Send a file or folder

Right-click any file or folder in File Explorer → **Send to Tailscale peer**.
Pick the online peer (or it chooses automatically if only one is online). The
file is sent with `tailscale file cp`.

## Receive a file from a peer

With `-InstallReceiveWatcher` this happens automatically. To pull manually:

```powershell
& "$env:LOCALAPPDATA\TailBridge\Receive-TailscaleFiles.ps1"
```

To watch continuously without the task:

```powershell
& "$env:LOCALAPPDATA\TailBridge\Receive-TailscaleFiles.ps1 -Watch"
```

Files land in `Received\`.

## Logs

All activity is logged to `%LOCALAPPDATA%\TailBridge\logs\`. If something fails,
check the logs first.

## Uninstall

Remove the registry keys:

```
HKEY_CLASSES_ROOT\*\shell\Send to Tailscale peer
HKEY_CLASSES_ROOT\Directory\shell\Send to Tailscale peer
```

Unregister the `TailBridge Receive Watcher` scheduled task if you created one,
then delete the `TailBridge` folder.
