# TailBridge for Windows — scripts only, no app

Everything lives in:

```
%LOCALAPPDATA%\TailBridge\
├── Send-ToTailscalePeer.ps1     # File Explorer context menu command
├── Push-TailscaleClipboard.ps1  # Push Windows clipboard to a peer
├── Receive-TailscaleFiles.ps1   # Pull incoming files; apply TBCLIP1 clips
├── Install-TailsBridge.ps1      # Registers context menus + optional task
├── logs\                        # send.log, push.log, receive.log
├── Received\                    # files pulled from peers
└── Temp\                        # staging for outgoing .clip files
```

## Install

Open PowerShell as a normal user and run:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
& "$env:LOCALAPPDATA\TailBridge\Install-TailsBridge.ps1" -InstallReceiveTask
```

What it does:
- Creates the directories above.
- Adds **"Send to Tailscale peer"** to the right-click menu for files and folders.
- Registers a Scheduled Task (`TailBridge Receive Files`) that pulls incoming transfers every 5 minutes and at logon, so phone → desktop clipboard clips land on Windows automatically.

## Send a file or folder

Right-click any file or folder in File Explorer → **Send to Tailscale peer**. Pick the online peer (or it chooses automatically if only one is online). The file is sent with `tailscale file cp`.

## Push your clipboard to a peer

Run:

```powershell
& "$env:LOCALAPPDATA\TailBridge\Push-TailscaleClipboard.ps1"
```

The current Windows clipboard text is wrapped in the `TBCLIP1` format and sent to the chosen peer. On the Android side (or any other TailBridge host), it lands as a clipboard entry — no file is left behind.

## Receive a clipboard from a peer

With `-InstallReceiveTask` this happens automatically. To pull manually:

```powershell
& "$env:LOCALAPPDATA\TailBridge\Receive-TailscaleFiles.ps1"
```

Any `.clip` file beginning with `TBCLIP1` is decoded and placed on the Windows clipboard via `Set-Clipboard`; regular files are left in `Received\`.

## How it talks to your phone

Your phone already writes `TBCLIP1` files when you share a clip to a peer, and its `tailscaled.receive` script recognizes them and sets the Android clipboard. These Windows scripts speak the same format:

- Windows → Phone: `Push-TailscaleClipboard.ps1` writes a `TBCLIP1` `.clip`; phone receives it and sets the Android clipboard.
- Phone → Windows: phone sends a `TBCLIP1` `.clip`; `Receive-TailscaleFiles.ps1` pulls it and sets the Windows clipboard.

## Logs

All activity is logged to `%LOCALAPPDATA%\TailBridge\logs\`. If something fails, check the logs first.

## Uninstall

Remove the registry keys:

```
HKEY_CLASSES_ROOT\*\shell\Send to Tailscale peer
HKEY_CLASSES_ROOT\Directory\shell\Send to Tailscale peer
```

Delete the `TailBridge` folder and unregister the scheduled task if you created one.
