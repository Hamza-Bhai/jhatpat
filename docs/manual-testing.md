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
