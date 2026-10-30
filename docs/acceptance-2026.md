# JhatPat v1 — acceptance checklist

Date: 2026-10-30 · Build: `dist/jhatpat.exe` (Windows 10, Go 1.27)

| # | Check | Result |
|---|---|---|
| 1 | Built exe starts, prints banner with PIN + terminal QR, host page loads | ✅ pass |
| 2 | Network Doctor on owner laptop | ✅ runs — found Wi-Fi profile **Public** + no firewall rule (real cause of owner's failed transfers) |
| 3 | PIN join → waiting screen → host Allow → phone page | ✅ pass (browser tab acting as phone, via LAN IP) |
| 4 | Host → phone text with link, inbox badge, Copy | ✅ pass |
| 5 | Phone → laptop 20 MiB file with emoji name, SHA-256 matches | ✅ pass (288 ms on loopback) |
| 6 | Light/dark + English/Roman Urdu at 375 px, no horizontal scroll, no console errors | ✅ pass |
| 7 | Privacy: group of 2 with 3rd device connected → 3rd sees nothing | ✅ automated (`TestGroupSend`, `TestDeviceIsolation`) |
| 8 | Folder laptop → phone downloads as zip | ✅ automated (`TestFolderDownloadIsZip`) |
| 9 | QR join from a **real Android phone and iPhone** | ⏳ owner — needs Wi-Fi set to Private first |
| 10 | ≥2 GB video phone → laptop on real WiFi | ⏳ owner |
| 11 | Resume: turn phone WiFi off 10 s mid-upload, back on → continues | ⏳ owner |
| 12 | Folder phone → laptop (Android) | ⏳ owner |
| 13 | Approval asked again after restarting JhatPat | ✅ automated (`TestRestartRequiresApproval`) |
| 14 | Public → Private fixes Doctor status | ⏳ owner |
| 15 | First-time user under 2 minutes | ⏳ owner |
