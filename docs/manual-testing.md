# Manual testing checklist

Automated tests cover the server logic (`go test ./...`). Run this checklist on real devices before a release or after changing the UI or network code.

Setup: laptop and phone on the same WiFi, laptop network profile set to **Private** (Windows), a fresh run of `jhatpat`.

| # | Check | Expected |
|---|---|---|
| 1 | Start JhatPat | Browser opens the laptop page; no terminal window on Windows |
| 2 | Scan the QR code with an Android phone | Phone shows "waiting", laptop shows an approval request |
| 3 | Tap **Allow** on the laptop | Phone shows the chat page |
| 4 | Join with the 4-digit PIN instead of the QR | Same as above |
| 5 | Phone → laptop: photo, small file, file with an emoji in its name | Files appear in `Downloads/JhatPat` and in the chat |
| 6 | Laptop → phone: text with a link, then a file | Text has a working Copy button; file downloads on the phone |
| 7 | Laptop → phone: a folder | Downloads as a ZIP that opens correctly |
| 8 | Phone → laptop: a folder (Android Chrome) | Folder structure is kept |
| 9 | Phone → laptop: a video larger than 1 GB | Completes; checksums match |
| 10 | Resume: turn the phone's WiFi off for ~10 s during a large upload, then on | Upload continues instead of restarting |
| 11 | Image, video and PDF previews | Preview opens full screen |
| 12 | Rename the phone | New name shows on the laptop |
| 13 | Two phones, one group: send to the group | The third device sees nothing |
| 14 | Quit with the button, start again, rejoin | Laptop asks for approval again |
| 15 | Network Doctor on a Public Windows profile | Explains the problem and how to fix it; reports OK after switching to Private |
| 16 | Light and dark themes at 375 px width | No horizontal scrolling, readable text |
| 17 | iPhone Safari | Joining, sending and receiving work |
| 18 | A first-time user joins without help | Succeeds in under two minutes |

## Android app

Use a real phone (Android 8 or newer is supported, so try an old and a new one if you can). Install the debug APK or a release APK, and have a laptop or second phone ready to join.

| # | Check | Expected |
|---|---|---|
| A1 | Start sharing with **Use my current WiFi** | One QR, a PIN and an address; a notification "JhatPat is sharing" appears |
| A2 | Join from a laptop browser with the PIN, tap **Allow** in the chat | The laptop sees the chat; "1 connected" shows on the Connect screen |
| A3 | Laptop sends a text and a 20 MB file to the phone | The text shows; the file appears in `Download/JhatPat` (or the Gallery for a photo or video) and the chat says "Saved to ..." with a working **Open** |
| A4 | Phone sends text, and a file with the attach button, to the laptop | Both arrive; the checksum of the file matches |
| A5 | Same photo sent twice | Two files, the second with `(1)` in its name; nothing is overwritten |
| A6 | Stop sharing, start with **Create a hotspot** (Location on) | Step 1 shows a WiFi QR; scanning it with another phone's camera joins that WiFi |
| A7 | Tap **They're connected, next** | Step 2 shows the join QR and PIN; opening it on the other phone reaches JhatPat |
| A8 | Hotspot refused (for example Location off) | A clear message with *Open hotspot settings* and *Continue with my current WiFi* |
| A9 | Gallery or Files: select several files, **Share → JhatPat**, send to Everyone | Everyone connected receives all files |
| A10 | Share to JhatPat while sharing is off, and while nobody is connected | "Start sharing first", then "Nobody is connected yet"; nothing is sent |
| A11 | **Share this app**, open the link from another phone, install | The app installs (after allowing unknown apps) and opens; the link stops working after Stop, after leaving the screen or after 10 minutes |
| A12 | Turn the screen off during a transfer of about 1 GB | The transfer finishes |
| A13 | Swipe the app away or force-stop it while sharing | The notification disappears, the hotspot stops and the port is closed |
| A14 | Stop, then Start straight away | Sharing starts again normally |
| A15 | Phone changes WiFi network while sharing in WiFi mode | The QR and address update to the new address |

