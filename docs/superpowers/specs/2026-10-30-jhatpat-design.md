# JhatPat ⚡ — Design

> Phone ↔ Laptop file transfer over WiFi. No app, no internet.

Date: 2026-10-30 · Status: draft, awaiting owner approval

## 1. Intent and why

The owner wants to move files, text and links between a laptop and phones that
sit on the same WiFi, and found no simple way to do it. Existing tools either
need an app on every device (LocalSend), depend on a signaling server
(PairDrop), or are command-line only (qrcp, magic-wormhole). The single most
common complaint across these tools is "my device doesn't show up", caused by
Windows "Public" network profiles, firewalls, VPN/virtual adapters, or router
client (AP) isolation. None of them diagnose this for the user.

JhatPat will be published on GitHub as open source so anyone can use it. It
must therefore work for a non-technical person: download one file, run it,
scan a QR code, done.

**What makes it different**
1. Phones need **no app**: the phone browser is the client.
2. **Fully offline**: the laptop is the server; nothing touches the internet.
3. **Network Doctor**: when something blocks the connection, JhatPat says why
   in plain words and how to fix it.
4. **Private delivery**: send to one device, a named group, or everyone, with
   server-side enforcement.
5. A fun, friendly look ("Sunny Pop"), not a dull utility.

## 2. Goals

- Laptop → phone and phone → laptop transfer of any file type and size
  (multi-GB videos must work without filling RAM).
- Multiple files at once; whole folders.
- Send text and links in both directions, with a one-tap Copy button.
- Several phones connected at the same time.
- Recipients per transfer: **one device**, **a group**, or **everyone**.
  A device must never be able to see or download items not addressed to it.
- Joining is protected: QR code (carries a secret) or 4-digit PIN, then the
  laptop owner approves the device (Allow / Block).
- Interrupted uploads resume from where they stopped.
- Network Doctor detects and explains common blockers.
- Light and dark themes; follows the device setting, with a manual toggle.
- One self-contained executable each for Windows, macOS and Linux.

**Success looks like:** a first-time user goes from download to first file on
their phone in under 2 minutes without help; when the network blocks them,
JhatPat tells them exactly what to change.

## 3. Non-goals

- No transfer over the internet, no relay/TURN servers, no cloud.
- No accounts, sign-up, or telemetry.
- No native mobile app (v1).
- No direct phone-to-phone path: everything passes through the laptop (the
  laptop can relay an item from one phone to others).

## 4. Anti-goals (would be a failure even if it "works")

- Any item visible to a device it was not sent to.
- The tool silently failing to connect with no explanation.
- Requiring users to install Go, Python, Node or any runtime.
- Loading anything from a CDN or the internet at runtime (must work on a WiFi
  with no internet).
- Loading a whole file into memory.
- A boring, grey "developer tool" look.

## 5. Decisions made with the owner

| Topic | Decision |
|---|---|
| Purpose | Laptop ↔ phone over same WiFi; open source on GitHub |
| Name | **JhatPat** (repo `jhatpat`). 51 GitHub repos share the word but none is a file-transfer tool. |
| Laptop UI | A web page in the laptop's browser (not a desktop window) |
| Visual style | **Sunny Pop**: bright yellow/orange, chunky black outlines with offset shadows, rounded cards, emoji, big thumb-friendly buttons |
| Themes | Light + dark; follows OS by default; 🌞/🌙 toggle |
| Security | 4-digit PIN **and** host approval of each new device |
| Returning devices | **Always ask again** — no auto-trust; every device needs Allow on every run |
| Content | Files, folders, text, links |
| Recipients | One device, a group, or everyone; groups created on the laptop |
| Project folder | `D:\Projects\jhatpat` |
| Technology | Go single binary + embedded HTML/CSS/JS (Claude's recommendation, owner accepted) |

## 6. Architecture

```
            ┌──────────────── Laptop ─────────────────┐
 Browser ──►│ Host page  (http://localhost:PORT/host) │
 (owner)    │                                          │
            │   Go server (single binary)             │
            │   • HTTP API + Server-Sent Events        │
            │   • device registry, groups, inbox store │
            │   • file streaming to/from disk          │
            │   • Network Doctor                       │
            └──────────▲───────────────────▲───────────┘
                       │ LAN IP:PORT        │
                 Phone A browser      Phone B browser
                 (client page)        (client page)
```

- **Server**: Go standard library `net/http`. Listens on all interfaces at a
  fixed default port (proposal: `8787`, overridable by flag; fall back to the
  next free port).
- **Two pages, one server**:
  - *Host page* — served only to requests from loopback (127.0.0.1/::1) and
    additionally gated by a random host token placed in the URL the binary
    opens. Shows QR, PIN, approvals, devices, groups, send panel, history,
    Network Doctor.
  - *Client page* — what phones see: Bhejo/Send, Lo/Receive, text box,
    inbox, theme toggle.
- **Realtime updates**: Server-Sent Events (one stream per page) for device
  joins, approval prompts, new inbox items and progress. Actions use plain
  `fetch` POST/PUT. (SSE chosen over WebSocket: stdlib-only, works through
  every mobile browser, one-way push is all we need.)
- **Assets**: all HTML/CSS/JS/fonts/icons embedded with `go:embed`. Vanilla
  JS, no framework, no build step, no CDN. QR code generated server-side as
  PNG/SVG (a small vendored Go QR library, e.g. `skip2/go-qrcode`, or
  `rsc.io/qr`).
- **On start**: pick the LAN IP (see Network Doctor for multi-adapter cases),
  print the URL + QR in the terminal too, open the host page in the default
  browser.

## 7. Joining and security

1. On each start the server generates: a 4-digit **PIN**, a long random
   **join secret**, and the host token. Both PIN and secret change every run;
   the host page has a "New PIN" button.
2. The QR encodes `http://<ip>:<port>/j/<join-secret>` → no typing needed.
3. Opening the bare `http://<ip>:<port>/` shows the 4-box PIN entry.
4. A valid secret or PIN creates a **pending device** with the name the phone
   offers (derived from the user agent, e.g. "Android Phone", editable by the
   phone user and by the host).
5. Host page shows *"📱 <name> wants to connect — ✅ Allow / ⛔ Block"*.
6. On Allow, the device receives a random **device token** in an HttpOnly,
   SameSite=Strict cookie. Every client request is authenticated by it.
7. Brute-force protection: max 5 wrong PINs per IP per 5 minutes, then a
   lockout message; PIN entry also locks globally after 20 failures until the
   host taps "New PIN".
8. Host can rename, kick, or block any device. Blocked devices cannot
   re-request during this run.
9. **Transport**: plain HTTP on the LAN in v1. HTTPS with a self-signed
   certificate produces scary browser warnings on phones, which conflicts with
   the 2-minute goal. The README states this clearly (others on the same WiFi
   could in theory sniff traffic). Optional HTTPS is a future item.

## 8. Devices, groups and recipients

- **Device registry**: in-memory for the run; known device names may be
  remembered (keyed by a persistent device id cookie) so groups survive
  restarts — but a returning device still needs approval each run.
- **Groups**: created on the host page ("Ghar wale", "Office"), each a named
  list of remembered devices. Persisted in a JSON config in the OS user
  config directory (`%APPDATA%\JhatPat` on Windows).
- **Every item** (file, folder, text, link) has a recipient set resolved at
  send time: one device id, a group's members, or all approved devices.
- **Enforcement is server-side**: list, metadata and download endpoints only
  return items whose recipient set contains the requesting device. Item ids
  are random, and an id for someone else's item returns 404 (not 403), so
  existence isn't leaked.
- **Phone → laptop** items land on the laptop. A phone can also choose
  "send to another device/group"; the laptop then stores the item and offers
  it to those recipients (relay).

## 9. Transfers

**Phone → laptop (upload)**
- Client splits files into chunks (proposal: 8 MB) and `PUT`s each with its
  byte offset to an upload session. Server writes straight to a `.part` file
  at that offset (no RAM buffering), then renames on completion.
- **Resume**: on reconnect the client asks the session for the received byte
  count and continues from there.
- Several files upload in sequence with a combined progress bar plus per-file
  bars, speed (MB/s) and time left.
- Saved to `Downloads/JhatPat/` (configurable). Name clashes get ` (1)`,
  ` (2)` suffixes. File names are sanitized (no path traversal).
- **Folders from phone**: `<input webkitdirectory>` where supported (Android
  Chrome, desktop browsers); relative paths are recreated under the save
  folder. Where unsupported (iOS Safari), the button only picks files.

**Laptop → phone (download)**
- Host picks files/folders (file picker + drag-and-drop on host page), picks
  recipients, sends. Recipients see the item in their inbox and tap to
  download.
- Downloads are streamed from disk with HTTP `Range` support so the browser
  can resume.
- **Folders** are zipped on the fly while streaming (`archive/zip`, Store
  method — no compression, maximum speed, no temp file).
- Items stay available until the host removes them or the tool exits.

**Text and links**
- Text box on both pages → "Bhejo". Appears in the recipients' inbox with a
  📋 Copy button; URLs are rendered as tappable links. Size cap (proposal:
  1 MB; larger text becomes a `.txt` file).

**Integrity**
- Server computes SHA-256 while writing uploads and shows it in item
  details. Client-side hashing of huge files in mobile browsers is too slow,
  so end-to-end hash verification is not required in v1.

## 10. Network Doctor

Runs at start and on demand ("🩺 Check network" on the host page). Status pill
in the header: 😊 *Sab theek hai* (green) or 😟 *Masla hai* (red, clickable).

Checks:
1. **Adapter choice**: list IPv4 addresses; prefer WiFi/Ethernet, de-rank
   VPN, VirtualBox, Hyper-V, WSL, Docker and similar virtual adapters. Host can
   switch the IP the QR uses.
2. **Windows network profile**: if the active WiFi is *Public*, explain and
   show the steps (Settings → Network → WiFi → Private).
3. **Windows Firewall**: check for an inbound allow rule for the binary/port.
   If missing, offer a button that adds it (triggers a UAC prompt; only on
   explicit click). macOS: explain the "Allow incoming connections" prompt.
4. **Self-reachability**: the server calls its own LAN address to confirm the
   port is reachable from the network side.
5. **No phone ever arrived** (after QR shown for a while, or on user tap
   "My phone can't open it"): show the AP/client-isolation explanation, how to
   disable it on the router, and the **hotspot fallback** — turn on the
   phone's hotspot and connect the laptop to it (or vice versa), then rescan.
6. Tips: prefer 5 GHz WiFi for speed.

All messages are in simple language with step-by-step instructions.

## 11. UI ("Sunny Pop")

Approved mockups: bright cream/yellow/orange background, chunky 3px dark
outlines, 4px offset hard shadows on buttons, rounded 14–28px corners, emoji
accents, striped orange progress bars. Dark theme: near-black warm brown
background with orange outlines and yellow accents.

**Host page**: header (logo, Network Doctor pill, 🌞/🌙) · left: big QR +
PIN · right: drop zone "Files yahan phenkein!" · recipient picker (device /
group / everyone) · devices list with approve/kick · groups manager · text
box · history list with per-item progress · "📂 Open folder" button.

**Client page**: header · two big buttons 📤 Bhejo / 📥 Lo (with unread
count) · text box · inbox list · progress.

Language: English only (owner decision, 2026-10-30: the project is an
open-source repo for an international audience). All UI strings live in
`web/static/js/i18n.js`.

## 12. Distribution and open source

- License: MIT.
- GitHub repo `jhatpat`: README with a GIF demo, quick start, troubleshooting
  (Network Doctor topics), security note about HTTP on LAN, screenshots of
  both themes.
- GitHub Actions: build + test on push; tagged releases build Windows/macOS/
  Linux binaries (GoReleaser).
- Windows SmartScreen will warn on unsigned exe; document "More info → Run
  anyway". Code signing is out of scope.

## 13. Testing

- Go unit tests: PIN/secret validation, rate limiting, device approval,
  recipient enforcement (a device cannot list/download others' items), chunk
  writes and resume, filename sanitization, on-the-fly zip, Range requests.
- Integration test: start server, simulate two devices over HTTP, verify
  isolation and a resumed upload.
- Manual: real Android phone and iPhone on the owner's WiFi, both themes,
  a ≥2 GB file, folder both ways, Network Doctor with Public profile.

## 14. Constraints

- Go is **not yet installed** on the owner's laptop; the build needs Go
  (latest stable). End users never need it.
- Owner's machine: Windows 10. Windows is the primary test platform;
  macOS/Linux builds are produced by CI but less manually tested.

## 15. Calls made by Claude (owner may change)

- Go + vanilla JS, SSE for realtime, stdlib-first.
- All traffic hubs through the laptop; no phone-to-phone path.
- Plain HTTP on LAN in v1.
- Default port 8787; 8 MB upload chunks; 1 MB text cap.
- Save folder `Downloads/JhatPat`.
- Groups and remembered device names persist (approvals never persist —
  owner's decision, see §5).
- 404 for items not addressed to the requester.
- English base UI with Roman Urdu option.
- MIT license.

## 16. Left to the builder

- Exact API routes and JSON shapes.
- Choice of Go QR library (must be pure Go, vendorable).
- Exact PIN lockout numbers, chunk size tuning after measuring.
- How Network Doctor queries Windows (PowerShell `Get-NetConnectionProfile`
  / `netsh advfirewall`), with graceful fallback when unavailable.
- File layout of the Go module and the web assets.
