# JhatPat ⚡

[![CI](https://github.com/Hamza-Bhai/jhatpat/actions/workflows/ci.yml/badge.svg)](https://github.com/Hamza-Bhai/jhatpat/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/Hamza-Bhai/jhatpat?include_prereleases)](https://github.com/Hamza-Bhai/jhatpat/releases)

**Phone ↔ Laptop file transfer over WiFi. No app, no internet.**

JhatPat runs on your laptop. Your phone opens it in its browser: scan a QR
code and you can send photos, videos, documents, folders, text and links in
both directions. Nothing goes to the internet, so it's fast and private.

## Features

- 📱 **No app on the phone.** Android, iPhone and other laptops just use the browser.
- 🔁 **Both directions.** Phone → laptop and laptop → phone, many files at once.
- 📁 **Folders.** Laptop → phone as a ZIP; phone → laptop with folder structure kept (Android and desktop browsers).
- 💬 **Text and links**, with a one-tap Copy button.
- 🔐 **PIN + approval.** Join with the QR code or a 4-digit PIN, then the laptop owner taps *Allow*. Every device is asked again on every run.
- 👨‍👩‍👧 **Groups and privacy.** Send to one device, a group (like "Family"), or everyone. A device can never see items that weren't sent to it.
- ⏯️ **Resume.** If the WiFi drops mid-upload, it continues where it stopped.
- 🩺 **Network Doctor.** Tells you in plain words why a phone can't connect (Public network, firewall, VPN adapter, router isolation) and how to fix it.
- 🌞/🌙 **Light and dark** themes.
- 🧳 **One small file.** No Python, Node or anything else to install. Windows, macOS and Linux.

## Quick start

1. Download JhatPat for your system from the [Releases](../../releases) page and unzip it.
2. Run `jhatpat` (on Windows, double-click `jhatpat.exe`; no terminal window opens).
   - Windows may show **"Windows protected your PC"** because the app isn't code-signed. Click **More info → Run anyway**.
   - If Windows Firewall asks, click **Allow access** (tick *Private networks*).
3. The JhatPat page opens in your laptop's browser.
4. Connect your phone to the **same WiFi**, then scan the QR code with the phone camera.
5. Tap **✅ Allow** on the laptop. Done: send away! 🎉

Received files are saved to `Downloads/JhatPat`. To stop JhatPat, click **Quit JhatPat** at the top of the laptop page (or press `Ctrl+C` if you started it from a terminal).

### Options

```
jhatpat -port 8787          # port (tries the next ones if busy)
jhatpat -dir D:\Inbox       # where received files go
jhatpat -ip 192.168.1.5     # which network address to put in the QR code
jhatpat -no-browser         # don't open the laptop page automatically
jhatpat -version            # print the version
```

On Windows the released `jhatpat.exe` has no console, so it prints nothing; run a source build (below) if you want terminal output.

## Troubleshooting

Click the status pill at the top of the laptop page to open the **Network Doctor**.

| Problem | Fix |
|---|---|
| Phone can't open the link, Doctor says **Public network** | Windows blocks phones on Public networks. *Settings → Network & Internet → Wi-Fi → your network → Network profile type → **Private***. |
| Doctor says **Firewall is blocking** | Click *Allow JhatPat (admin)* in the Doctor, or allow it when Windows asks. |
| Phone still can't connect | Your router may have **AP / client isolation** (common on guest and office WiFi). Turn it off in the router settings, or use a **hotspot**: turn on your phone's hotspot, connect the laptop to it, and scan the new QR. |
| Wrong address in the QR (VPN or virtual adapters) | Start with `jhatpat -ip <your WiFi address>`. |
| Slow transfers | Use **5 GHz** WiFi if your router has it. |

## Security notes

- JhatPat only works on your local network and never contacts the internet.
- Joining needs the QR secret or the PIN, **and** the laptop owner's approval. Wrong PINs are rate-limited.
- The laptop page only opens on the laptop itself.
- Traffic uses plain **HTTP** on your WiFi (browsers show scary warnings for self-signed HTTPS on phones). Someone on the same WiFi with special tools could see the files in transit, so use it on networks you trust.

## Build from source

Needs Go 1.24 or newer.

```bash
git clone https://github.com/Hamza-Bhai/jhatpat
cd jhatpat/web
go test ./...
go build -o jhatpat ./cmd/jhatpat
# Windows, no terminal window on double-click: run build-windows.bat (uses -ldflags "-H=windowsgui")
```

## Repository layout

| Folder | What it is |
|---|---|
| [`web/`](web) | The JhatPat server and web interface (Go). Runs on Windows, macOS and Linux. |
| `app/` | The Android app, so a phone can be the host (in development). |

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first. To report a security problem, see [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)
