# JhatPat Android app: design

Date: 2026-10-31 · Status: approved by the owner on 2026-10-31
Builds on: the JhatPat server in `../web` (v0.1.x)

## 1. Intent and why

JhatPat today needs a laptop to run the server. The owner's real moment: a
group of people (laptops and phones) wanted files, and the only device at hand
was a phone. The phone could not host.

The Android app makes **a phone the JhatPat host**, exactly like the laptop
program: other people (on laptops or phones, any OS) join from their browser
with a QR code or PIN, the host approves them, and the host sends files to one
person, a group or everyone. Nobody else installs anything. Where there is no
WiFi, the app creates its own hotspot.

The app reuses the existing Go server and web UI unchanged wherever possible, so
every feature of the laptop version works on the phone.

## 2. Goals (all required for V1, decided by the owner)

1. **Phone as host.** All current JhatPat features: PIN/QR join, per-run
   approval, groups, privacy between devices, chat, previews, resumable
   uploads, folders, text and links.
2. **One QR at a time, hotspot included.** The app can create a local hotspot
   (no internet). Only one QR is on screen at once: with WiFi, one QR; with
   a hotspot, step 1 is the WiFi QR, step 2 (after "Next") is the page QR
   (see section 8).
3. **Share from other apps.** In Gallery, WhatsApp, Files and others, *Share →
   JhatPat* sends the selected files to the connected people.
4. **Send to everyone** in one action.
5. **The app can share itself.** The host serves its own APK so a nearby person
   can install the app from the host phone, without internet.
6. **Runs in the background.** A foreground service with a notification
   (progress, number of people, Stop button) keeps transfers going with the
   screen off; the phone does not sleep mid-transfer.
7. **Received files appear in the Gallery** (photos and videos in the normal
   Gallery, other files in Downloads).

## 3. Non-goals

- iPhone as a host (iOS cannot run a server like this). iPhones join by browser.
- Joining *someone else's* host inside the app (use the browser).
- Internet access, accounts, analytics, ads. No outbound network calls at all.
- Play Store distribution (direct APK from GitHub Releases only, for now).
- Shared Drives / WebDAV (separate project).
- Starting at boot or running without the user starting it.

## 4. Anti-goals (a failure even if it works)

- Another device ever reaching the host page or host API (still loopback-only
  plus a per-run token, exactly as on the laptop).
- A device seeing files that were not sent to it.
- The hotspot or server staying on after the user taps Stop or the service dies.
- Draining the battery while idle (no wake lock unless sharing is on).
- Files written outside the app's own folder and the user's Gallery/Downloads.
- Asking for the "all files" storage permission.
- Leaking the signing keystore or its passwords into the repository.

## 5. Decisions made by the owner

| Topic | Decision |
|---|---|
| Name | **JhatPat** (folder `JhatPat-app`) |
| Repo | Same repository as the server |
| Distribution | APK in GitHub Releases, installed directly |
| Scope | All six features above in V1 |
| Hotspot | Yes; one QR on screen at a time |

## 6. Architecture

```
JhatPat-app (Kotlin, Android)                 web/ (Go)
┌─────────────────────────────────┐           ┌──────────────────────┐
│ UI: Start / Connect / Settings  │           │ jhatpat server       │
│ WebView ── http://127.0.0.1 ────┼──────────▶│  (same code, built   │
│ Foreground service ── starts ───┼─ process ▶│   for android/arm64) │
│ Hotspot, QR, share target, MediaStore       └──────────────────────┘
└─────────────────────────────────┘
```

- **The Go server runs as a child process.** It is built with
  `CGO_ENABLED=0` for `arm64-v8a` (`GOOS=android`) and for `armeabi-v7a` and `x86_64`
  (static `GOOS=linux`, because `android/arm` and `android/amd64` need cgo and the NDK),
  packaged as `lib/<abi>/libjhatpat.so` and started from the native library
  directory (`android:extractNativeLibs="true"`), because Android forbids
  executing files from the app's data directory. No NDK and no gomobile.
  A spike on a real phone (Android 11, arm64) confirmed that this binary starts,
  joins, approves and transfers a 20 MiB file correctly.
- **The UI for hosting is the existing host page** shown in a WebView at
  `http://127.0.0.1:<port>/host`, so the app inherits every feature and fix.
  Native screens exist only for what the web cannot do: starting/stopping,
  hotspot, QR steps, permissions, share target, notifications.
- **Controlling the server.** The server gets small, backwards compatible
  additions (section 7). The app reads one JSON startup line from the server's
  stdout (port, host token, PIN, join secret) and talks to the existing host API
  over loopback with the host cookie.
- **Share target.** The app receives `ACTION_SEND` / `ACTION_SEND_MULTIPLE`,
  asks "Send to Everyone / a person / a group" (default Everyone), then streams
  each content URI into the server through the existing host upload API
  (`/api/host/uploads`, chunked, resumable), addressed to the chosen target.
  If nobody is connected yet, the app says so and does not send (nothing is queued).
- **Gallery.** When a file arrives for the host, the app moves it from the
  server's private folder into MediaStore (images → `Pictures/JhatPat`, videos →
  `Movies/JhatPat`, everything else → `Download/JhatPat`), which needs no
  storage permission on Android 10+. Moving, not copying, so large videos are
  never stored twice. Tapping the item in the chat opens it with an Android
  intent through a small WebView bridge.
- **Hotspot.** `WifiManager.startLocalOnlyHotspot` (API 26+): random SSID and
  password, no internet. The WiFi QR is `WIFI:T:WPA;S:<ssid>;P:<password>;;`.
  The host IP is read from the hotspot network interface.
- **Self-sharing APK.** The server gets an optional `-apk <path>` flag; the app
  passes its own installed APK (`applicationInfo.sourceDir`). `GET /app.apk` is
  served only while the host has turned on "Share this app", with a QR for it.
- **Signing.** Releases are signed with a keystore the owner creates once.

## 7. Server changes (in `web/`, all backwards compatible)

- `-config-dir`: where state lives (Android has no home directory).
- `-json-startup`: print one JSON line `{port, hostToken, pin, secret, ip}` instead
  of the banner and QR.
- `-exit-on-stdin-close`: the server exits when the app's pipe closes, so a killed
  app never leaves a server (or an open port) behind.
- `-ip` already exists; the app always passes it (Android 11+ hides network
  interfaces from apps, so the server cannot discover the address itself).
- `-apk <path>` plus `POST /api/host/apk/enable` and `GET /app.apk`.
- The Network Doctor must not shell out on Android and must not show
  Windows-only advice.
- A host event or endpoint the app can watch to learn that a file for the host
  has finished (to move it into the Gallery).

## 8. Screens

Mockups: `.superpowers/brainstorm/…/app-flow.html` and `app-connect-v2.html`.

1. **Start.** Choice: "Use my current WiFi" or "Create a hotspot"; *Start sharing*.
2. **Connect.** With WiFi: one QR and PIN. With a hotspot: step 1 shows only the
   WiFi QR; the host taps *They're connected → Next*; step 2 shows the page QR
   and PIN; *Back* returns to step 1. Also: *Share this app (APK)* and *Stop*.
3. **Chat.** The host page in a WebView.
4. **Share sheet.** Share from other apps → *Send to Everyone* confirmation.
5. **Notification.** Progress, people, files received, *Open* and *Stop*.

## 9. Permissions

`INTERNET` (needed for the local server's sockets; the app makes no outgoing
requests), `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
`ACCESS_FINE_LOCATION` (up to Android 12; required for hotspots, and Location
must be on), `NEARBY_WIFI_DEVICES` with `neverForLocation` (Android 13+),
`FOREGROUND_SERVICE` and its type permission, `POST_NOTIFICATIONS` (13+),
`WAKE_LOCK`. Permissions are requested only when the feature needing them is used.

## 10. Constraints

- `minSdk` 26 (hotspot API), `targetSdk` and `compileSdk` 35, Kotlin, Jetpack
  Compose. Same toolchain as the owner's other Android project: JDK 17+, Gradle 8.10,
  AGP 8.7.
- English only. No dependencies beyond AndroidX, Compose and one QR library
  (ZXing core).
- The Go server code under `web/internal` stays portable (no desktop-only code
  outside build-tagged files).

## 11. Known risks and how they are handled

- **Hotspot while connected to WiFi.** Some phones refuse a local-only hotspot
  while WiFi is on. The app detects the failure and shows a fallback: "Turn on
  your phone's Hotspot in Settings, then tap Continue" (opens the hotspot
  settings). To be tested on the owner's TECNO phone first.
- **Battery optimisation / OEM task killers** (TECNO, Xiaomi, etc.). The service
  is foreground with a wake lock only while sharing; a one-time prompt asks to
  exempt the app from battery optimisation.
- **Plain HTTP**, as on the laptop: same warning in the README.
- **"Install unknown apps" warning** when installing the APK directly: a short
  guide in the README and on the in-app sharing screen.
- **Android versions**: hotspot details differ between 8, 11 and 13+; each is
  covered by the manual checklist.

## 12. Testing

- Go: unit tests for each new flag and endpoint; the existing suite keeps passing.
- Kotlin: unit tests for pure logic (WiFi QR string, picking the hotspot IP,
  turning a shared URI list into upload jobs, MediaStore target selection).
- CI builds the APK (`assembleRelease`) on every push; a release job signs it and
  attaches it to the GitHub Release next to the laptop zips.
- Manual checklist (`docs/manual-testing.md`, new Android section): start with
  WiFi and with a hotspot, join from an Android phone, an iPhone and a laptop,
  send to one and to everyone, share from Gallery, receive into Gallery, screen
  off during a 1 GB transfer, stop cleans up, install the APK from another phone.

## 13. Build order (for the plan)

1. Server changes (section 7) with tests.
2. App skeleton: project, service that runs the server, WebView host page, WiFi mode.
3. Hotspot, WiFi QR and the two-step connect screens, with the fallback.
4. Share target and "send to everyone".
5. Notification, wake lock, Gallery saving.
6. APK self-sharing, signing, CI/release job, README and checklist.

## 14. Calls made by Claude (the owner may change any of these)

- Package id `io.github.hamzabhai.jhatpat`.
- Jetpack Compose for the native screens; ZXing core for QR codes.
- *Next* button between the two hotspot steps (no automatic detection).
- Received files are *moved* into the Gallery rather than copied.
- `/app.apk` is only served while "Share this app" is on, and is not behind the PIN
  (it is the public app, nothing secret).
- Foreground service type chosen to fit Android 14+ rules (`specialUse`).
- `minSdk` 26.
- Three CPU architectures in one APK (arm64, armv7, x86_64).

## 15. Left to the builder

- Exact JSON field names and endpoint shapes in section 7.
- Compose layout details, colors (match the web UI's orange and yellow) and
  icon design.
- How the WebView bridge exposes "open this received file".
- Gradle task that builds the Go binaries (and what happens when Go is missing).
