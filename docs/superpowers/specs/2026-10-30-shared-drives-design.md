# JhatPat Shared Drives — Design

Date: 2026-10-30 · Status: draft, awaiting owner approval
Builds on: `2026-10-30-jhatpat-design.md` (JhatPat v1)

## 1. Intent and why

The owner wants files on the laptop to be usable from phones **without
downloading them first**: browse folders, stream a video, open a PDF, and
when attaching a file in WhatsApp/Gmail/etc., pick it **straight from the
laptop's folder**. Access must work per phone and per group (e.g. the
"Hamza" group), and the reverse direction (laptop opening a phone's
storage) is wanted too.

A web page cannot appear inside other apps' file pickers, so this uses
**WebDAV**, the standard network-drive protocol understood by file manager
apps (CX File Explorer, Solid Explorer, Owlfiles on iPhone, Windows,
macOS Finder). The file manager then offers the laptop folder to every app.

The work is split into three projects, built in this order:

| # | Project | Size | This doc |
|---|---|---|---|
| 1 | **Shared Drives** in JhatPat (laptop folders → phones over WebDAV) | project | ✅ yes |
| 2 | **Phone storage links** (laptop opens a phone's storage that a phone app exposes) | small | ✅ yes (§12) |
| 3 | **JhatPat Android app** (native app: **phone can be the JhatPat host** for phone-to-phone sharing without a laptop, exposes phone storage, share-sheet target, auto-connect) | large, separate | ❌ own design later (direction fixed in §16) |

## 2. Goals

- The owner picks folders or whole drives to share, each with:
  - **who**: one phone, a group, or everyone;
  - **permission**: 👁️ *View* (read only) or ✏️ *Full* (add, rename, move, delete).
- Phones connect once with a file manager app using an address, username
  and password shown on their JhatPat page; the connection keeps working
  across JhatPat restarts until the owner revokes it.
- Videos/music stream with seeking; photos and documents open without a
  full download.
- Nothing outside a share can ever be reached (no path tricks).
- Deletes are recoverable for 30 days.
- The owner can see what was accessed/changed, and can switch all drive
  access off with one switch.

## 3. Non-goals

- Access from outside the local network.
- Phone-to-phone sharing without a laptop **in this project** (it comes with
  the Android app, where a phone runs the host — §16). Here each phone only
  sees laptop shares.
- Windows Explorer "Map network drive" over plain HTTP (Windows refuses
  Basic auth without HTTPS unless a registry value is changed; documented,
  not worked around).
- Syncing/backup.

## 4. Anti-goals (failure even if it works)

- Any path that escapes a share: `..`, absolute paths, symlinks, Windows
  junctions, drive letters, UNC paths, alternate data streams (`file:stream`),
  8.3 short names, case tricks.
- A *View* share accepting any change.
- A phone seeing shares not granted to it.
- System or JhatPat's own secrets becoming reachable (Windows, Program
  Files, AppData, the JhatPat config dir with passwords).
- A permanent delete with no undo.
- Drive passwords stored in plain text.

## 5. Decisions

| Topic | Decision | By |
|---|---|---|
| Protocol | WebDAV at `http://<ip>:<port>/dav/` | owner (CX File Explorer) |
| Permissions | Per share: View or Full | owner ("proper" access) |
| Who | Per share: devices, groups, everyone | owner |
| Login lifetime | **Persistent "drive password" per phone**, valid until revoked or reset. Browser joining still asks every run. | Claude (owner delegated) — file managers save the connection; a per-run password would break it every time. Risk is contained by: owner must explicitly grant drive access, random 20-char passwords, hashing, lockout, master switch, LAN only. |
| Whole drives | Allowed, but sensitive system folders inside are always blocked (§8.4) | Claude |
| Deletes | Moved to a hidden `.jhatpat-trash` in the share root, auto-purged after 30 days | Claude |
| Transport | Plain HTTP like v1 (password readable by someone sniffing the same WiFi); README warns; optional HTTPS later | Claude |
| Android app | Separate project after this one | owner + Claude |

## 6. Concepts and data

**Share** (persisted in `<config>/shares.json`, atomic writes):
```
{ id, name, path (absolute, cleaned), mode: "view"|"full",
  access: { all: bool, devices: [persistentID], groups: [groupID] },
  enabled: bool, createdAt }
```
- `name` is unique (case-insensitive) and is the folder name phones see at
  the WebDAV root. Default: folder name, or `D-drive` for `D:\`.
- Overlapping shares are allowed; each is its own jail.

**Drive account** (persisted in `<config>/drive-accounts.json`):
```
{ persistentID, username, passwordHash (SHA-256 of salt+password),
  salt, createdAt, lastUsedAt }
```
- One per phone (keyed by the phone's persistent id from v1).
- Username: lowercase slug of the device name + `-` + 4 random chars,
  e.g. `hamza-phone-7k2q`. Never reused.
- Password: 20 chars from an unambiguous alphabet (no 0/O/1/l), ≥100 bits.
  Shown on screen when created or reset; only the hash is stored. The
  phone page can show it again only while the phone is approved in the
  current run (kept in memory for this run only), otherwise "Reset" makes
  a new one.

**Master switch**: `driveSharing: on|off` in `<config>/settings.json`.
Default **off**. When off, `/dav` returns 503 for everyone.

**Access rule** — an account can use a share when the share is enabled,
the master switch is on, and the share's `access` contains `all`, its
persistent id, or a group whose members include its persistent id.

## 7. Components (Go)

- `internal/shares` — share store, access rule, path denylist (§8.4).
- `internal/driveauth` — accounts, hashing, Basic-auth check, lockout
  (10 failures / IP / 10 min; global 100 → lock until owner unlocks).
- `internal/davfs` — a `webdav.FileSystem` (from `golang.org/x/net/webdav`):
  - virtual root `/` listing the shares the account may use;
  - each share served through **`os.Root`** (Go ≥1.24), which refuses to
    follow paths, symlinks or junctions out of the share directory;
  - extra name checks on every path segment (§8.2);
  - *View* shares: every write operation returns `os.ErrPermission`;
  - `RemoveAll` → move into `.jhatpat-trash/<yyyy-mm-dd>/<path>`;
  - hides `.jhatpat-trash`, names starting with `.` or `$`,
    `desktop.ini`, `thumbs.db`, and anything on the denylist.
- `internal/server` additions:
  - `/dav/` handler = middleware (§8.3) → `webdav.Handler{FileSystem, LockSystem: webdav.NewMemLS()}`;
  - host API for shares, drive accounts, master switch, activity log;
  - device API for "my drive access" and "my phone storage address".
- Activity log: in-memory ring buffer of 500 entries
  `{time, username, method, share, path, result}`; pushed to the host page
  via the existing SSE hub; reads of the same file within 60 s collapse into
  one line.

## 8. Security design

### 8.1 Authentication
- HTTP Basic over the existing listener; constant-time compare of hashes.
- Unknown user and wrong password take the same time and give the same 401.
- Lockout per §7; locked IPs get 429.
- Revoking or resetting an account takes effect on the next request (no cache).

### 8.2 Path safety (defence in depth)
1. Request path is URL-decoded once by `net/http`; reject if it contains
   `\`, a NUL, a `:` in any segment (drive letters and ADS), or a segment
   that is `..`/`.` after cleaning.
2. First segment selects the share by exact name; unknown → 404.
3. Remaining path is opened only through that share's `os.Root`
   (kernel-level containment, symlink/junction safe).
4. Every segment is checked against the denylist (§8.4) and hidden rules.
5. Windows 8.3 short names (`PROGRA~1`) are rejected on Windows shares:
   a segment matching `^[^.~]{1,6}~[0-9]+(\.[^.]{0,3})?$` is refused
   unless an entry with exactly that long name exists in the directory
   (so a real file called `photo~1.jpg` still works).

### 8.3 Request rules (middleware before WebDAV)
- Allowed methods: `OPTIONS, GET, HEAD, PROPFIND, PUT, DELETE, MKCOL, COPY,
  MOVE, LOCK, UNLOCK, PROPPATCH`. Others → 405.
- On *View* shares, `PUT, DELETE, MKCOL, COPY(to it), MOVE, LOCK, UNLOCK,
  PROPPATCH` → 403 before reaching the filesystem.
- `PROPFIND` with `Depth: infinity` → 403 (prevents walking a whole drive).
- `COPY`/`MOVE`: `Destination` must be on the same host, under `/dav/`, in
  the **same share**, and that share must be *Full*; otherwise 403.
  `Overwrite` defaults to `F` semantics when absent (no silent overwrite).
- `Host` header must be an IP literal, `localhost`, or the machine's
  hostname (blocks DNS-rebinding pages in the laptop's browser).
- Requests with an `Origin` header from another site → 403 (browser CSRF).
- No request body limit for `PUT` (large videos), but writes stream to disk.

### 8.4 Always-blocked folders (even inside a shared drive)
Matched case-insensitively against the absolute path:
- Windows: `\Windows`, `\Program Files`, `\Program Files (x86)`,
  `\ProgramData`, `\$Recycle.Bin`, `\System Volume Information`,
  `\Recovery`, `\Boot`, `\Users\*\AppData`, `pagefile.sys`,
  `hiberfil.sys`, `swapfile.sys`.
- macOS/Linux: `/System`, `/Library`, `/private`, `/etc`, `/var`,
  `/usr`, `/bin`, `/sbin`, `/proc`, `/sys`, `/dev`, `~/Library`.
- JhatPat's own config dir and its outbox.
- Creating a share whose path *is* or *is inside* a blocked folder is refused.

### 8.5 Known residual risks (documented in README)
- Plain HTTP: someone sniffing the same WiFi can read files and the drive
  password. Use only trusted WiFi; HTTPS is a future option.
- A phone with *Full* access can fill the disk.
- The file manager app on the phone stores the password.

## 9. UI

**Laptop page — new card "📂 Shared Drives"** (left column, under Groups):
- Master switch: `Drive sharing: OFF / ON` (big, obvious).
- `+ Share a folder` → the existing laptop file picker (folders/drives only)
  → dialog: name, permission (👁️ View / ✏️ Full), who (checkboxes:
  Everyone, each group, each known phone).
- List of shares: name, path, permission chip, who, enable toggle, edit,
  remove (removing a share never deletes files).
- **Drive access per phone** in the Devices list: `🔑 Give drive access`
  → shows username + password once, with Copy; existing accounts show
  `Reset password` and `Revoke`.
- **Activity** (collapsible): latest entries, live.
- Network Doctor: new check "Drive sharing reachable".

**Phone page — new "💻 Laptop Drive" sheet** (button in the header):
- If the owner granted access: server address `http://<ip>:<port>/dav/`,
  username, password (only in this run, else "Ask the laptop to reset"),
  each with Copy, plus a 4-step "Connect in CX File Explorer" guide and the
  list of shares this phone can see.
- If not granted: "Ask the laptop owner to give this phone drive access."
- **My phone storage** (§12) field.

## 10. Errors and edge cases a person will meet
- Share path deleted/unplugged USB → share shows ⚠️ "folder not found",
  WebDAV returns 404 for it; nothing crashes.
- Two phones edit the same file → WebDAV locks (MemLS) prevent clobbering
  for clients that lock; otherwise last writer wins (documented).
- Disk full during upload → 507 Insufficient Storage, partial file removed.
- Filenames with emoji/Urdu text → work (UTF-8 end to end).
- Very large folders (10k+ files) → PROPFIND Depth 1 only; listing streams.
- JhatPat restarts → accounts and shares persist; CX reconnects silently.
- Phone renamed → username stays the same (it is shown, not derived live).

## 11. Testing
- `davfs`: traversal (`../`, `%2e%2e`, `..\\`, `C:`, `file.txt:stream`,
  `PROGRA~1`), symlink and junction escape (Windows junction test skipped
  when not creatable), denylist inside a shared drive, hidden entries,
  trash move + 30-day purge.
- `driveauth`: hash/verify, unknown user timing parity (same code path),
  lockout, reset/revoke immediate.
- Middleware: method allowlist, View-share write → 403 for every write
  method, Depth infinity → 403, cross-share MOVE/COPY → 403, bad Host →
  403, cross-site Origin → 403, master switch off → 503.
- Access rule: device, group, everyone; group membership change applies
  immediately; revoked account → 401.
- Integration: real `webdav` client round-trip (PROPFIND, GET with Range,
  PUT, MKCOL, MOVE, DELETE → trash) using `httptest`.
- Manual: CX File Explorer on Android connect, browse, stream a 1 GB video
  with seeking, attach a laptop file in WhatsApp via CX, rename/delete on a
  Full share, write attempt on a View share fails.

## 12. Project 2 — Phone storage links (laptop → phone)
- A phone app (CX File Explorer → "Access from network", or the future
  JhatPat Android app) shows an address like `ftp://192.168.1.8:2121`.
- The phone saves it once on its JhatPat page ("My phone storage").
  Accepted only if the scheme is `ftp`, `ftps`, `http` or `https` and the
  host is a private-range IPv4 address (no internet hosts, no `file:`).
- The laptop's Devices list shows `📱 Open phone storage` for that phone,
  which opens the address in the OS file manager (Windows Explorer opens
  `ftp://` natively). JhatPat never stores the phone app's password.
- Persisted per persistent id in `<config>/phone-storage.json`.

## 13. Constraints
- `os.Root.Rename`/`RemoveAll`/`MkdirAll` need **Go 1.25**: raise the
  `go` directive in `go.mod` from 1.24 to 1.25 (installed toolchain: 1.27).
- New dependency: `golang.org/x/net/webdav` (official Go sub-repository).
- **Keep the core portable for the Android app (§16):** everything under
  `internal/` except `netdoctor/windows.go` must stay free of desktop-only
  code (no shelling out, no Windows paths outside build-tagged files), so the
  same server can later be compiled for Android with gomobile. The denylist
  (§8.4) gets an Android list too: `/storage/emulated/0/Android/data`,
  `/storage/emulated/0/Android/obb`, `/data`, `/system`.

## 14. Left to the builder
- Exact API routes/JSON for shares and accounts.
- Activity-log collapsing details.
- Whether `webdav.Handler` needs a thin wrapper for `Overwrite` defaults.
- Trash purge schedule (on start + daily).

## 15. Calls made by Claude (owner may change)
- Persistent drive passwords (see §5).
- Master switch default OFF.
- Trash instead of permanent delete; 30 days.
- System-folder denylist always on.
- No `Depth: infinity`.
- Plain HTTP for now.
- Android app as a separate project after this one.

## 16. Direction for Project 3 — phone as host (owner request, 2026-10-30)
Not designed here; recorded so Projects 1–2 don't block it.
- The Android app can run **the same JhatPat server** (Go core compiled
  with gomobile into an Android library) as a foreground service, so a phone
  becomes the host: other phones join it from their browser with QR + PIN +
  Allow, exactly like the laptop today — phone-to-phone with no laptop.
- The host page opens inside the app (a WebView on `localhost`), so the
  existing "host page only from loopback" rule still holds.
- Phone hosting pairs naturally with **hotspot mode**: the host phone turns
  on its hotspot, others connect to it — this also avoids router
  AP-isolation problems.
- The phone host can share its own folders through the same Shared Drives
  feature (WebDAV), which also covers "laptop opens phone storage" without
  CX File Explorer.
- Open questions for that design: storage permission model on Android 11+
  (Storage Access Framework vs all-files access), battery/background limits,
  Play Store vs direct APK distribution, iPhone (no equivalent; iPhones stay
  browser clients).

