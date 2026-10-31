# JhatPat for Android

The Android app turns a phone into the JhatPat host: other people, on laptops or phones, join from their browser and you share files with them. The app runs the same server as the laptop version (it is bundled inside the APK), shows the same chat page, and adds what a phone needs: a hotspot, sharing from other apps, saving to the Gallery, and sharing the app itself.

See [DESIGN.md](DESIGN.md) for how it works and why.

## Build it

You need:

- JDK 17 or newer
- The Android SDK with platform 35 (set `sdk.dir` in `local.properties`, or `ANDROID_HOME`)
- Go 1.24 or newer on your `PATH`: the build compiles the server in [`../web`](../web) for each CPU type and packs it into the APK

```bash
cd JhatPat-app
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat` instead of `./gradlew`.

### Tests on a phone

Some checks need a real device: running the server from the app, the hotspot, the foreground service and sharing to several devices. With a phone connected over USB or wireless debugging:

```bash
./gradlew connectedDebugAndroidTest
```

Keep the phone unlocked and turn Location on, because Android only creates a hotspot for an app in the foreground. The checklist for manual checks is in [../docs/manual-testing.md](../docs/manual-testing.md).

### Release builds

Releases are built and signed by GitHub Actions when a version tag is pushed. How the signing key works, and how to build a signed APK yourself, is in [docs/signing.md](docs/signing.md).

## Layout

| Path | What it is |
|---|---|
| `app/src/main/java/.../service` | The foreground service, its state machine, the notification |
| `app/src/main/java/.../server` | Runs the bundled server and talks to its API |
| `app/src/main/java/.../net` | The hotspot |
| `app/src/main/java/.../ui` | Start, Connect, Chat and Share-this-app screens |
| `app/src/main/java/.../share` | "Share to JhatPat" from other apps |
| `app/src/main/java/.../gallery` | Saving received files to the Gallery and Downloads |
| `app/src/main/java/.../logic` | Small pure pieces (WiFi QR text, file naming, addresses) |
