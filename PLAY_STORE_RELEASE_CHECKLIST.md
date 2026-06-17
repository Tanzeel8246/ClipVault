# ClipVault Play Store Release Checklist

## Required before production upload

- Build artifact: upload an Android App Bundle (`.aab`), not the manually installed APK.
- Target SDK: Android 15 / API 35 or higher.
- Signing: configure a private production keystore through these Gradle properties or environment variables:
  - `CLIPVAULT_RELEASE_STORE_FILE`
  - `CLIPVAULT_RELEASE_STORE_PASSWORD`
  - `CLIPVAULT_RELEASE_KEY_ALIAS`
  - `CLIPVAULT_RELEASE_KEY_PASSWORD`
- Version: `versionName 1.0.1`, `versionCode 2`.
- Privacy policy: publish a real policy page at `https://mindcrafts.net/privacy-policy` or change the in-app link before upload.
- Data Safety: disclose clipboard text, microphone access for voice typing, local encrypted storage, optional Google Drive backup, optional export/restore, and sensitive-data handling.
- Google Drive backup: configure the OAuth client ID and consent screen in `GOOGLE_DRIVE_BACKUP_SETUP.md` before enabling the feature publicly.
- Prominent disclosure: keep the first-run privacy disclosure enabled.
- Closed testing: if this is a new personal Play Console account, complete the required closed test before production access.

## Commands

```powershell
npm run typecheck
cd android
gradlew.bat :app:verifyPlayReleaseConfig
gradlew.bat :app:bundleRelease
```

The final bundle will be under `android/app/build/outputs/bundle/release/` after release signing is configured.
