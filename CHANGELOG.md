# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Android app** (`JhatPat-app/`): a phone can be the JhatPat host. It runs the same server, can create a hotspot (one QR at a time), accepts "Share to JhatPat" from other apps, saves received files to the Gallery or Downloads, can share its own APK, and keeps running with the screen off. Signed APKs are attached to each GitHub release.
- Server options for the app: `-config-dir`, `-json-startup`, `-exit-on-stdin-close`, `-no-doctor`, `-host-name`, `-host-kind` and `-apk`, plus a runtime `POST /api/host/ip`, `/app.apk` (only while the host switches it on), and "received" and "moved" events.

### Changed
- The repository is now a monorepo: the Go server lives in `web/` (module `github.com/Hamza-Bhai/jhatpat/web`) and the Android app will live in `JhatPat-app/`.

## [0.1.1] - 2026-10-31

### Fixed
- The Windows release build is now a GUI app, so double-clicking `jhatpat.exe` no longer opens a terminal window (v0.1.0 shipped a console build by mistake).

## [0.1.0] - 2026-10-31

First public release: PIN and QR joining with per-run approval, groups, resumable uploads, folder transfer, and the Network Doctor.

### Added
- Chat-style interface on the laptop and phone pages, with WhatsApp-style previews and a full-screen viewer.
- Phones can rename themselves.
- "Quit JhatPat" button on the laptop page.
- Windows builds run without a terminal window.
- `-version` flag.
- Security headers on every response; the host token is removed from the address bar after the first visit.

### Changed
- The interface is English only.
- Network Doctor no longer reports a problem while a phone is still connecting.
- A Public Windows network profile is only a warning when the firewall already allows JhatPat.
