# Shared Drives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Owner preference:** execute inline with superpowers:executing-plans (no subagents).

**Goal:** Phones mount laptop folders/drives over WebDAV (CX File Explorer etc.) with per-share View/Full permission for devices, groups or everyone; plus laptop → phone storage links.

**Architecture:** New portable packages `shares`, `driveauth`, `davfs`, `phonelinks` under `internal/`; `davfs` implements `webdav.FileSystem` with one `os.Root` jail per share; a `/dav/` middleware in `server` does auth, access and request rules before `golang.org/x/net/webdav`. UI cards on host and phone pages.

**Tech Stack:** Go ≥1.25 (toolchain 1.27), `golang.org/x/net/webdav`, existing vanilla JS UI.

**Spec:** `docs/superpowers/specs/2026-10-30-shared-drives-design.md`

## Global Constraints

- `go.mod` directive `go 1.25`.
- No desktop-only code outside build-tagged files in `internal/` (Android/gomobile later).
- Share modes: `"view"`, `"full"`. Access: `{all, devices:[persistentID], groups:[groupID]}`.
- Drive sharing master switch default **off**; off → `/dav/*` 503.
- Username: slug(device name, max 20) + `-` + 4 random `[a-z0-9]`; password 20 chars from `abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789`; stored `sha256(salt||password)` hex with 16-byte random salt.
- Lockout: 10 failures / IP / 10 min → 429; 100 total failures → all logins locked until host unlocks.
- Allowed DAV methods: `OPTIONS GET HEAD PROPFIND PUT DELETE MKCOL COPY MOVE LOCK UNLOCK PROPPATCH`.
- Trash dir `.jhatpat-trash/<yyyy-mm-dd>/<rel path>` at share root; purge entries older than 30 days on start and every 24 h.
- Files: `<config>/shares.json`, `<config>/drive-accounts.json`, `<config>/phone-links.json` (atomic writes, corrupt → `.bad` backup + empty).
- WebDAV realm: `JhatPat`. Prefix: `/dav`.
- English-only UI strings in `web/static/js/i18n.js`.

## Review Focus

1. Shared USB drive unplugged → that share answers 404 and the host card shows ⚠️, nothing crashes. *(Task 1 `TestShareStatusMissing`, Task 4 `TestDAVMissingShareFolder`)*
2. Emoji / Urdu file names through WebDAV PUT then GET/PROPFIND round-trip unchanged. *(Task 4 `TestDAVUnicodeNames`)*
3. Upload to a View share is refused and leaves no file behind. *(Task 4 `TestDAVViewShareRejectsWrites`)*
4. PUT over an existing file on a Full share replaces it, but the old version lands in trash (no silent loss). *(Task 3 `TestOverwriteKeepsOldInTrash`)*
5. Removing a phone from a group cuts its drive access on the very next request. *(Task 4 `TestDAVGroupChangeAppliesImmediately`)*

---

## File Structure

```
internal/shares/store.go        shares + master switch, access rule, persistence
internal/shares/blocked.go      system-folder denylist (pure, per GOOS)
internal/driveauth/store.go     drive accounts, hashing, lockout
internal/davfs/fs.go            webdav.FileSystem over os.Root jails
internal/davfs/names.go         segment checks, hidden names, 8.3 short names
internal/davfs/trash.go         move-to-trash + purge
internal/phonelinks/store.go    phone storage URLs (validated)
internal/server/dav.go          /dav middleware + handler + activity log
internal/server/drives.go       host + device drive APIs
internal/groups/store.go        + GroupsOf(pid)
web/host.html, web/static/js/host.js, web/client.html, web/static/js/client.js,
web/static/js/i18n.js, web/static/css/sunnypop.css   UI
README.md                       Shared Drives section + CX guide
```

---

### Task 1: Shares store, denylist, master switch

**Files:** Create `internal/shares/store.go`, `internal/shares/blocked.go`, tests `store_test.go`, `blocked_test.go`; modify `go.mod` (`go 1.25`), `internal/groups/store.go` (+ test).

**Interfaces — Produces:**
```go
type Mode string // View = "view", Full = "full"
type Access struct { All bool `json:"all"`; Devices []string `json:"devices"`; Groups []string `json:"groups"` }
type Share struct { ID, Name, Path string; Mode Mode; Access Access; Enabled bool; CreatedAt time.Time } // json lower-camel
type Patch struct { Name *string; Mode *Mode; Access *Access; Enabled *bool }
func Open(configDir string, extraBlocked ...string) (*Store, error)
func (s *Store) Add(name, path string, mode Mode, a Access) (Share, error) // "" name → DefaultName(path)
func (s *Store) Update(id string, p Patch) (Share, error)
func (s *Store) Remove(id string) error
func (s *Store) List() []Share
func (s *Store) Get(id string) (Share, bool)
func (s *Store) VisibleTo(pid string, groupsOf func(pid string) []string) []Share // enabled only, sorted by name
func (s *Store) SharingOn() bool
func (s *Store) SetSharing(on bool) error
func (s *Store) Status(id string) string // "ok" | "missing"
func DefaultName(path string) string     // `D:\` → "D-drive", `/` → "root", else base name
func Blocked(goos, home, path string, extra []string) bool
// groups package:
func (s *groups.Store) GroupsOf(persistentID string) []string // group IDs containing pid
```
Errors: `ErrNotFound`, `ErrDuplicate`, `ErrBlocked`, `ErrNotDir`, `ErrBadName` (empty, contains `/ \ :` or starts with `.`), `ErrBadMode`.

- [ ] **Step 1:** `go mod edit -go=1.25`; `go get golang.org/x/net/webdav`.
- [ ] **Step 2: Failing tests** (`blocked_test.go`, pure, both GOOS values):
  - `TestBlockedWindows`: `Blocked("windows", `C:\Users\hp`, X, nil)` true for `C:\Windows`, `c:\windows\system32`, `C:\Program Files (x86)\x`, `C:\ProgramData`, `C:\Users\hp\AppData\Local`, `C:\Users\other\AppData`, `D:\$Recycle.Bin\a`, `D:\System Volume Information`, `C:\pagefile.sys`; false for `C:\`, `D:\`, `C:\Users\hp\Pictures`, `D:\Movies`.
  - `TestBlockedUnix`: true for `/etc`, `/usr/bin`, `/proc/1`, `/home/u/Library`(darwin), `/storage/emulated/0/Android/data/x`(linux/android), `/data`; false for `/home/u/Music`, `/storage/emulated/0/DCIM`.
  - `TestBlockedExtra`: config dir in `extra` and anything under it → true.
- [ ] **Step 3: Failing tests** (`store_test.go`): `TestAddAndPersist`, `TestAddRejectsBlocked`, `TestAddRejectsFileNotDir`, `TestDuplicateNameCaseInsensitive`, `TestBadNames` (`""`→default used; `"a/b"`, `".x"`, `"c:"` rejected), `TestVisibleToDeviceGroupAll` (device pid, group via `groupsOf`, all, disabled hidden), `TestSharingDefaultOffAndPersists`, `TestShareStatusMissing` (dir removed → `"missing"`), `TestDefaultName`, `TestCorruptFileBackedUp`; groups: `TestGroupsOf`.
- [ ] **Step 4:** Run → FAIL. **Step 5:** Implement. **Step 6:** `go test ./internal/shares/ ./internal/groups/` → PASS.
- [ ] **Step 7:** Commit `feat(shares): share store, denylist and master switch`.

### Task 2: Drive accounts

**Files:** Create `internal/driveauth/store.go`, `store_test.go`.

**Interfaces — Produces:**
```go
type Account struct { PersistentID, Username string; CreatedAt, LastUsedAt time.Time } // json; hash+salt unexported in file record
func Open(configDir string, now func() time.Time) (*Store, error)
func (s *Store) Create(pid, deviceName string) (Account, string, error) // replaces any existing account (reset); returns plaintext password
func (s *Store) Revoke(pid string) error
func (s *Store) Get(pid string) (Account, bool)
func (s *Store) List() []Account
func (s *Store) Check(ip, username, password string) (Account, error) // ErrBadLogin | ErrIPLocked | ErrAllLocked; updates LastUsedAt (persist at most once/min)
func (s *Store) CurrentPassword(pid string) (string, bool)              // in memory, this run only
func (s *Store) Unlock()
```
- [ ] **Step 1: Failing tests:** `TestCreateGivesWorkingLogin`, `TestUsernameShape` (`^[a-z0-9-]{1,20}-[a-z0-9]{4}$`, emoji/space name → slug), `TestPasswordShape` (20 chars, alphabet only), `TestFileHasNoPlaintext` (password string absent from `drive-accounts.json`), `TestResetInvalidatesOld`, `TestRevoke`, `TestUnknownUserBadLogin` (same error as wrong password), `TestIPLockout` (10 wrong → `ErrIPLocked`, clock +10m → ok), `TestAllLockedUntilUnlock` (100 wrong across IPs), `TestCurrentPasswordNotPersisted` (reopen → false), `TestPersistAcrossOpen`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement (`crypto/rand`, `crypto/sha256`, `subtle.ConstantTimeCompare`; for unknown users compare against a dummy hash). **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(driveauth): persistent drive accounts with lockout`.

### Task 3: davfs — jailed WebDAV filesystem, names, trash

**Files:** Create `internal/davfs/fs.go`, `names.go`, `trash.go`, tests `fs_test.go`, `names_test.go`, `trash_test.go`.

**Interfaces:**
- Consumes: `shares.Share`, `shares.Blocked`.
- Produces:
```go
type FS struct{ /* grants by name, open roots, home, extra blocked, now */ }
func New(grants []shares.Share, home string, extraBlocked []string, now func() time.Time) *FS
func (f *FS) Close() error
// webdav.FileSystem:
func (f *FS) Mkdir(ctx context.Context, name string, perm os.FileMode) error
func (f *FS) OpenFile(ctx context.Context, name string, flag int, perm os.FileMode) (webdav.File, error)
func (f *FS) RemoveAll(ctx context.Context, name string) error   // → trash
func (f *FS) Rename(ctx context.Context, oldName, newName string) error // same share only
func (f *FS) Stat(ctx context.Context, name string) (os.FileInfo, error)
func SplitPath(name string) (share, rel string, err error) // "/" → ("",""), "/Movies/a.mp4" → ("Movies","a.mp4")
func CheckSegment(seg string, windows bool) error          // ErrBadName
func Hidden(name string) bool                              // ".x", "$x", ".jhatpat-trash", desktop.ini, thumbs.db (case-insensitive)
const TrashDir = ".jhatpat-trash"
func PurgeTrash(sharePath string, olderThan time.Duration, now time.Time) error
```
Rules: root `/` is a read-only virtual dir listing grants (ModTime = now, mode dir); any write at root → `os.ErrPermission`. View grant: `Mkdir`, `RemoveAll`, `Rename`, `OpenFile` with `O_WRONLY|O_RDWR|O_CREATE|O_TRUNC|O_APPEND` → `os.ErrPermission`. Every op: `SplitPath` → `CheckSegment` each segment → `shares.Blocked(abs)` false → op via that share's `os.Root`. `Readdir` hides `Hidden` and blocked entries. `OpenFile` with `O_TRUNC|O_CREATE` on an existing regular file in a Full share first moves the old file to trash. 8.3 rule per spec §8.2-5 (windows only).

- [ ] **Step 1: Failing tests (`names_test.go`):** `TestSplitPath`, `TestCheckSegment` (rejects `..`, `.`, `a:b`, `C:`, `x\y`, NUL, `PROGRA~1` on windows only; accepts `photo~1.jpg`? — rejected by pattern only when no such long name exists: tested in fs), `TestHidden`.
- [ ] **Step 2: Failing tests (`fs_test.go`, t.TempDir shares):**
  - `TestRootListsGrantsOnly`, `TestRootIsReadOnly`.
  - `TestReadWriteFullShare` (Mkdir, create, write, read back, Rename within share, Stat).
  - `TestViewShareRefusesEveryWrite` (each write op → `os.ErrPermission`, nothing created).
  - `TestTraversalRefused` (`/S/../x`, `/S/..%2f`, `/S/a/../../x` after `path.Clean` still caught by os.Root, `/S/C:/x`).
  - `TestSymlinkEscapeRefused` (symlink inside share → outside dir; read fails; skip if symlinks unsupported).
  - `TestJunctionEscapeRefused` (windows only: `mklink /J`; skip if it fails).
  - `TestRenameAcrossSharesRefused`.
  - `TestHiddenAndBlockedNotListed` (share at a temp "drive" containing `Windows/` dir passed via `extraBlocked` → not listed, not openable).
  - `TestShortNameRefusedUnlessReal` (windows only).
  - `TestDAVUnicodeNames` at FS level: create `🎉 shaadi.mp4`, `اردو.txt`, list and read back.
- [ ] **Step 3: Failing tests (`trash_test.go`):** `TestRemoveAllMovesToTrash` (file and dir; original gone; content under `.jhatpat-trash/<date>/rel`), `TestTrashNameClash` (same path deleted twice → second gets ` (1)`), `TestOverwriteKeepsOldInTrash`, `TestPurgeOlderThan30Days` (fake times via `os.Chtimes`).
- [ ] **Step 4:** FAIL. **Step 5:** Implement. **Step 6:** `go test ./internal/davfs/` PASS; `GOOS=linux go vet ./internal/davfs/` OK.
- [ ] **Step 7:** Commit `feat(davfs): jailed WebDAV filesystem with trash`.

### Task 4: /dav endpoint, request rules, activity log

**Files:** Create `internal/server/dav.go`, `internal/server/dav_test.go`; modify `internal/server/server.go` (fields `shares *shares.Store`, `drives *driveauth.Store`, `davLocks webdav.LockSystem`, `activity *activityLog`; open stores in `New`; route `/dav/` and `/dav`).

**Interfaces — Produces:**
```go
type Activity struct { Time time.Time; User, Method, Share, Path string; Status int } // json lower-camel
func (s *Server) handleDAV(w http.ResponseWriter, r *http.Request)
func (s *Server) Activity() []Activity // newest first, max 500
```
Order in `handleDAV`: master switch (503) → `Host` must be IP literal / `localhost` / `os.Hostname()` (403) → foreign `Origin` (403) → method allowlist (405) → Basic auth via `driveauth.Check` (401 + `WWW-Authenticate: Basic realm="JhatPat", charset="UTF-8"`; 429 when locked) → grants = `shares.VisibleTo(pid, groups.GroupsOf)` → `PROPFIND` with `Depth: infinity` (403) → target share View + write method (403) → write at root (403) → `COPY`/`MOVE` `Destination` same host, under `/dav/`, same share, share Full (403); missing `Overwrite` header → set `Overwrite: F` → `webdav.Handler{Prefix: "/dav", FileSystem: davfs.New(...), LockSystem: s.davLocks, Logger: s.logDAV}` → status recorded via wrapped ResponseWriter → activity (collapse same user+GET+path within 60 s) → `s.hub.Send("host", Event{Type:"drive-activity"})`. Disk full during PUT → 507 (map `syscall.ENOSPC` / Windows `ERROR_DISK_FULL`(112) / `ERROR_HANDLE_DISK_FULL`(39) in a build-tag-free helper using `errors.As(err, *syscall.Errno)`).

Trash purge: `davfs.PurgeTrash` for every share on `New` and every 24 h in a goroutine stopped by `Server.Shutdown`.

- [ ] **Step 1: Failing tests** (`httptest`, real `webdav` round-trips with Basic auth; helper `davReq(method, path, user, pass, headers, body)`):
  - `TestDAVOffByDefault` (503), `TestDAVNeedsAuth` (401 + header), `TestDAVWrongPasswordThenLock` (429 after 10),
  - `TestDAVListsOnlyGrantedShares` (PROPFIND Depth 1 on `/dav/`),
  - `TestDAVStreamRange` (GET with Range → 206),
  - `TestDAVPutMkcolMoveDeleteFull` (DELETE lands in trash),
  - `TestDAVViewShareRejectsWrites` (PUT, DELETE, MKCOL, MOVE, COPY-into, LOCK, PROPPATCH → 403; disk unchanged),
  - `TestDAVDepthInfinityRefused`, `TestDAVCrossShareMoveRefused`, `TestDAVDestinationOtherHostRefused`,
  - `TestDAVBadHostRefused` (`Host: evil.com` → 403), `TestDAVForeignOriginRefused`, `TestDAVMethodNotAllowed` (`TRACE` → 405),
  - `TestDAVGroupChangeAppliesImmediately` (remove pid from group → next PROPFIND hides share / 404),
  - `TestDAVRevokedAccount` (401), `TestDAVMissingShareFolder` (404 for that share, others fine),
  - `TestDAVUnicodeNames` (HTTP PUT/GET/PROPFIND with emoji + Urdu names),
  - `TestDAVActivityRecorded` (PUT then `s.Activity()[0]` has user/method/share/path/status; two GETs of same file within 60 s → one entry).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** `go test ./...` PASS.
- [ ] **Step 5:** Commit `feat(server): WebDAV endpoint with access rules and activity log`.

### Task 5: Drive APIs (host + device) and phone storage links

**Files:** Create `internal/phonelinks/store.go` (+ test), `internal/server/drives.go`, `internal/server/drives_test.go`; modify `server.go` (field `links`), `host.go` (state devices gain `storageURL`, `driveUser`).

**Interfaces — Produces:**
```go
// phonelinks
func Validate(raw string) (string, error) // scheme ftp|ftps|http|https; host = private IPv4 literal (10/8, 172.16/12, 192.168/16); optional port; no userinfo
func Open(configDir string) (*Store, error)
func (s *Store) Set(pid, raw string) error
func (s *Store) Get(pid string) (string, bool)
func (s *Store) Clear(pid string) error
```
Routes (host = loopback + host cookie; dev = approved device):

| Route | Body → Response |
|---|---|
| `GET /api/host/drives` | → `{sharing, davURL, shares:[Share+status], accounts:[Account+name], activity, locked}` |
| `POST /api/host/drives/sharing` | `{on}` |
| `POST /api/host/drives/shares` | `{name,path,mode,access}` → Share (400 with error code `blocked`/`not-dir`/`duplicate`/`bad-name`) |
| `PATCH /api/host/drives/shares/{id}` | Patch → Share |
| `DELETE /api/host/drives/shares/{id}` | never touches files |
| `POST /api/host/drives/accounts/{pid}` | create/reset → `{username,password}` (pid must be a known device) |
| `DELETE /api/host/drives/accounts/{pid}` | revoke |
| `POST /api/host/drives/unlock` | clears lockouts |
| `POST /api/host/devices/{id}/open-storage` | opens that device's link via `OpenPath`; 404 if none |
| `GET /api/dev/drive` | → `{granted, davURL, username, password?, shares:[{name,mode}]}` (password only if `CurrentPassword` exists) |
| `PUT /api/dev/storage` | `{url}` → validated, saved (`""` clears) |
| `GET /api/dev/storage` | → `{url}` |

`davURL` = `http://<current ip>:<port>/dav/` derived from the join URL.

- [ ] **Step 1: Failing tests:** phonelinks `TestValidate` (accepts `ftp://192.168.1.8:2121`, `http://10.0.0.5:8080/x`; rejects `ftp://8.8.8.8`, `file:///c:/`, `javascript:alert(1)`, `ftp://user:pw@192.168.1.8`, `ftp://phone.local`), `TestPersist`; server `TestDrivesHostOnly` (LAN → 403 on every host drive route), `TestShareCRUDOverAPI`, `TestBlockedShareRejected` (error `blocked`), `TestAccountCreateShowsPasswordOnceToHost` (second GET of `/api/host/drives` has no password), `TestDeviceSeesOwnDriveAccessOnly` (A sees its username/password; B sees `granted:false`), `TestDevicePasswordGoneAfterRestart`, `TestStorageLinkFlow` (dev PUT valid → host state `storageURL`; open-storage calls fake `OpenPath` with that URL; invalid → 400).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat: drive and phone-storage APIs`.

### Task 6: Host UI — Shared Drives card

**Files:** Modify `web/host.html`, `web/static/js/host.js`, `web/static/js/i18n.js`, `web/static/css/sunnypop.css`.

- Card "📂 Shared Drives" in the left column under Groups: big switch (OFF/ON), `davURL`, `+ Share a folder` (reuses browse modal in "folder mode": only folders selectable plus roots/drives; then dialog: name, View/Full, who = Everyone + groups + known phones checkboxes), share list (name, path, chip View/Full, who summary, ⚠️ when `status=missing`, enable toggle, Edit, Remove with confirm "Files are not deleted").
- Devices list: per approved device `🔑 Drive access` (create → modal with username/password + Copy + "Shown once"), existing account → `Reset password` / `Revoke`; `📱 Open phone storage` when `storageURL` set.
- Collapsible "Drive activity" list (time, user, method icon, share/path, status) live via SSE `drive-activity`.
- "Logins locked" banner with `Unlock` when `locked`.
- Spec §9 "Network Doctor: Drive sharing reachable" is covered by the card's ON/OFF state + `davURL` line (the Doctor already checks reachability of the same port); no separate Doctor check.
- [ ] **Step 1:** Implement; all strings via `i18n.js`.
- [ ] **Step 2:** `node -e` syntax check of JS; `go test ./...` PASS.
- [ ] **Step 3:** Manual in browser at 1280 px and 375 px, light + dark: create share, toggle, edit, give access, revoke; no console errors.
- [ ] **Step 4:** Commit `feat(web): Shared Drives card on laptop page`.

### Task 7: Phone UI — Laptop Drive sheet and My phone storage

**Files:** Modify `web/client.html`, `web/static/js/client.js`, `i18n.js`, CSS.

- Header button `💻 Drive` opens a sheet: if granted → server address, username, password (or "Ask the laptop to reset the password"), Copy buttons, visible shares with View/Full chips, 4-step CX guide ("CX File Explorer → Network → New location → WebDAV → paste address, username, password → OK"); if not → "Ask the laptop owner to give this phone drive access".
- Section "📱 My phone storage": text field + Save + help ("In CX File Explorer: Network → Access from network → Start, then paste the ftp:// address here").
- [ ] **Step 1:** Implement. **Step 2:** syntax check + `go test ./...`. **Step 3:** Manual at 375 px both themes. **Step 4:** Commit `feat(web): drive sheet and phone storage on phone page`.

### Task 8: Docs and acceptance with a real client

**Files:** Modify `README.md` (Shared Drives section, CX guide, security notes §8.5, Windows Map-network-drive limitation), `docs/acceptance-2026.md` (new rows).

- [ ] **Step 1:** README + acceptance rows.
- [ ] **Step 2:** Automated client check: start built exe on a test port with a temp config dir, create share + account via host API, then use `curl` (`-u user:pass`) for PROPFIND/PUT/GET/MOVE/DELETE; record results.
- [ ] **Step 3:** Owner checks with CX File Explorer on Android (connect, browse, stream video with seek, attach a laptop file in WhatsApp, View-share write refused) — rows marked ⏳ until done.
- [ ] **Step 4:** Commit `docs: Shared Drives guide and acceptance`.
