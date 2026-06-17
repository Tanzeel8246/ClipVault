# ClipVault Google Drive Backup Setup

ClipVault now has optional Google sign-in and Google Drive backup support. Backups are stored in the user's own Google Drive `appDataFolder`, which is hidden from normal Drive browsing and only accessible to this app.

## Google Cloud setup

1. Create or open a Google Cloud project for `MindCrafts_AI ClipVault`.
2. Enable the Google Drive API.
3. Configure the OAuth consent screen.
4. Add the Drive scope:
   - `https://www.googleapis.com/auth/drive.appdata`
5. Create an OAuth client for the Android app package:
   - Package name: `com.clipvault.mobile`
   - Add the release signing certificate SHA-1 after production keystore creation.
6. Add the OAuth client ID in:
   - `services/GoogleDriveBackup.ts`
   - constant: `GOOGLE_CLIENT_ID`

## App behavior

- Google sign-in is optional.
- Local clipboard storage works without Google.
- After sign-in, ClipVault can back up clips to Drive automatically.
- On a new phone, sign in with the same Google account and tap restore; the app also attempts one automatic merge restore after sign-in.
- Restore is merge-based: existing local clips are kept and duplicate clips are refreshed by hash.

## Play Store notes

- Mention Google Drive backup in the privacy policy.
- Mention clipboard text, optional Drive backup, microphone use for voice typing, and local encrypted storage in Data Safety.
- If Google marks the Drive scope as sensitive, complete OAuth app verification before public launch.
