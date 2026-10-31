# Signing the Android app

Android only installs an APK that is signed, and it only accepts an update signed with **the same key**. The release workflow signs the APK with a key that only the maintainer holds, stored as GitHub secrets. The key is never committed to the repository.

> **Keep the keystore file and its passwords safe, and back them up.** If they are lost, nobody can update the app: people would have to uninstall it and install a new build signed with a new key.

## One-time setup

1. **Create the keystore** (needs a JDK; `keytool` comes with it):

   ```bash
   keytool -genkeypair -v -keystore jhatpat-release.jks -alias jhatpat -keyalg RSA -keysize 4096 -validity 10000
   ```

   It asks for a store password, a key password and a name. Remember the passwords and the alias (`jhatpat`).

2. **Turn the file into text** so it fits in a secret.

   - Linux or macOS: `base64 -w0 jhatpat-release.jks`
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("jhatpat-release.jks"))`

3. **Add four secrets** in the repository: *Settings → Secrets and variables → Actions → New repository secret*.

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | the text from step 2 |
   | `ANDROID_KEYSTORE_PASSWORD` | the store password |
   | `ANDROID_KEY_ALIAS` | `jhatpat` (or the alias you chose) |
   | `ANDROID_KEY_PASSWORD` | the key password |

4. **Store the keystore somewhere safe** (a password manager or an encrypted backup), then delete the local copy you do not need.

## Releasing

Push a version tag, for example `git tag v0.2.0 && git push origin v0.2.0`. The release workflow builds the laptop programs, then builds and signs the APK and attaches `JhatPat-0.2.0.apk` and its `.sha256` file to the same GitHub release. If the secrets are missing the workflow prints a warning and skips the APK.

The version code is derived from the tag (`0.2.1` becomes `201`), so each release is newer than the last, which Android requires for updates.

## Building a signed APK on your own machine

Put the values in `JhatPat-app/local.properties` (this file is git-ignored) or in environment variables:

```properties
release.storeFile=C:/keys/jhatpat-release.jks
release.storePassword=...
release.keyAlias=jhatpat
release.keyPassword=...
```

```bash
./gradlew assembleRelease -PversionName=0.2.0
```

The result is `app/build/outputs/apk/release/app-release.apk`. Check it with `apksigner verify --print-certs app-release.apk` (part of the Android build tools). Without a keystore the build still works but produces an unsigned APK that cannot be installed.
