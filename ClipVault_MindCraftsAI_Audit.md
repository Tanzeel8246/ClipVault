# ClipVault / MindCrafts_AI Audit

Date: 2026-06-02

## Current Build Status

- Branded installable APK created: `ClipVault-MindCraftsAI-release.apk`
- Latest keyboard-focused APK created: `ClipVault-MindCraftsAI-keyboard-pro.apk`
- Latest VaultBoard APK created: `ClipVault-MindCraftsAI-vaultboard.apk`
- Latest VaultBoard UX APK created: `ClipVault-MindCraftsAI-vaultboard-ux.apk`
- Latest VaultBoard layout APK created: `ClipVault-MindCraftsAI-vaultboard-layout.apk`
- Latest VaultBoard professional keyboard APK created: `ClipVault-MindCraftsAI-vaultboard-pro.apk`
- Latest VaultBoard compact keyboard APK created: `ClipVault-MindCraftsAI-vaultboard-compact.apk`
- Package: `com.clipvault.mobile`
- Version: `1.0.0`, versionCode `1`
- Current min SDK: `23`
- Current target SDK: `34`
- Branding added in app header and Settings screen: `MindCrafts_AI`
- Contact links added:
  - WhatsApp: `https://wa.me/923023448246`
  - Website: `https://codehubb.com`
  - Website: `https://mindcrafts.net`
- New assets added:
  - `assets/icon.png`
  - `assets/adaptive-icon.png`
  - `assets/splash-icon.png`
  - `assets/play-store-icon.png` - 512 x 512
  - `assets/play-feature-graphic.png` - 1024 x 500
- Android launcher icons regenerated for mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi.
- Android permission surface was kept focused. The APK now declares only:
  - `POST_NOTIFICATIONS`
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_SPECIAL_USE`
  - `RECORD_AUDIO`
  - `USE_BIOMETRIC`
  - `USE_FINGERPRINT`
  - `VIBRATE`
  - app-internal dynamic receiver permission

## Play Store Readiness Gap

This APK is installable, but it is not yet the final Play Store upload artifact.

Google Play now requires new apps and updates to target Android 15 / API 35 or higher. This build targets API 34, so the next technical release must upgrade the Android/Expo build stack to target SDK 35 before Play upload.

Google Play also requires Android App Bundle (`.aab`) publishing for new apps. APK is good for manual install/testing, but Play upload should be an AAB.

If the Play Console account is a new personal developer account, production access requires a closed test with at least 12 opted-in testers for 14 continuous days.

## Market Baseline

Current clipboard apps commonly offer:

- Automatic clipboard history where Android permits it.
- Search, folders/categories, favorites, pinned clips.
- Quick snippets for repeated text.
- Notification or floating access.
- Import/export and backup.
- Optional cloud sync.
- Keyboard-based paste access.
- Smart actions such as URL/email/phone handling.
- Some newer apps market AI text tools and screen/OCR capture.

The biggest platform constraint is Android itself: since Android 10, background apps cannot freely read clipboard data unless they are the focused app or the default input method. This means a modern clipboard app should not depend only on background polling. It needs user-visible capture paths.

## 2026-06-02 Reliable Capture Update

There is no Play Store or Google approval path that turns a normal background app into a permanently clipboard-readable "focused app". On Android 10 and higher, focused means the app is currently active on screen. The durable path is to make ClipVault a user-enabled input method / keyboard when the user wants continuous capture.

Implemented in this update:

- ClipVault Keyboard registered as an Android IME.
- Keyboard clipboard capture saves copied text into the same local ClipVault database.
- Keyboard layouts added for English, Urdu, Arabic, and Symbols.
- Keyboard theme switching added: Dark, Emerald, Light.
- Voice typing foundation added with Android `SpeechRecognizer`.
- App Settings now include Enable Keyboard, Pick Keyboard, and Voice Permission actions.
- JSON backup/export and restore flow remains available for Drive/file backup use.

Notes:

- Gboard or other keyboard private clipboard history cannot be imported by ClipVault. Android does not expose another keyboard's private clipboard list.
- To capture every new copy going forward, ClipVault Keyboard must be enabled and selected as the current keyboard.
- Microphone permission is used only when the user presses the keyboard Mic button.

## 2026-06-02 VaultBoard Update

The keyboard should not be marketed as a Google Gboard clone. Google branding, trade dress, proprietary prediction, Translate, GIF/sticker integrations, and Google account services should not be copied. The safer strategy is to position this as an original MindCrafts_AI keyboard with familiar modern keyboard workflows.

Implemented in this update:

- VaultBoard-style top status area.
- Suggestion strip with quick brand/contact suggestions and simple prefix suggestions.
- Toolbar actions for Clips, Emoji, Mic, Language, Theme, and keyboard switcher.
- Built-in recent clips panel backed by the ClipVault database.
- Built-in emoji panel.
- Persistent number row on language layouts.
- English, Urdu, Arabic, and Symbols layouts retained.
- Voice typing, clipboard capture, and theme switching retained.

Still not implemented and should not be represented as done:

- Google/Gboard proprietary prediction quality.
- Glide typing / gesture typing.
- Google Translate, GIF, sticker, handwriting, or Gemini integrations.
- Exact Google UI, naming, icons, or brand style.

## 2026-06-02 VaultBoard UX Fixes

Implemented after keyboard usability review:

- Button label font sizes increased substantially for readability.
- Language, emoji, clips, mic, theme, symbols, next-keyboard, and enter controls moved into the keyboard control row.
- Top toolbar controls removed; the upper strip is now used for live suggestions only.
- Clips and emoji buttons now toggle their own panels back to normal keys.
- Delete now supports press-and-hold continuous deletion.
- Suggestions and auto-correction now react to the currently typed word instead of showing saved clips/brand words.
- Urdu and Arabic diacritic panel added for zabar, zer, pesh, tanween, sukun, shadda, maddah, khari zabar, khari zer, and ulta pesh.
- Voice typing now requests partial speech results and inserts live partial text before finalizing the recognized phrase.
- Keyboard switch button now attempts one-tap switching to the next installed input method, with picker fallback where Android does not allow direct switching.

Quality note:

- Real-time voice typing depends on the device speech recognition service returning partial results. Some Android speech providers may still only return final results.

## 2026-06-02 VaultBoard Layout Refinement

Implemented after the second keyboard layout review:

- Keyboard row heights increased slightly.
- Main key label size reduced from oversized values to fit better on small screens while remaining readable.
- Urdu and Arabic layouts now support a second "more letters" page through the same shift key area.
- Urdu and Arabic similar-letter alternates added through long-press panels.
- Urdu coverage now includes base and extended forms such as آ، ء، ئ، ؤ، پ، ٹ، چ، ڈ، ڑ، ژ، گ، ں، ے، ھ، ۂ، ۃ.
- Arabic coverage now includes base and extended forms such as أ، إ، آ، ء، ؤ، ئ، ة، ى، لا and Arabic marks.
- Shift key label now changes by language: English keeps ABC/abc, Urdu shows اصل/مزید, Arabic shows أصل/مزيد.
- Theme button now opens a selectable minimalist theme panel instead of cycling blindly.
- Theme options added: Dark, Graphite, Light, Green, Ocean, Rose.
- IME metadata already declares `supportsSwitchingToNextInputMethod=true`; Android controls whether the system keyboard switcher appears near an input field when another keyboard is active.

## 2026-06-03 VaultBoard Professional Redesign

Implemented after screenshot-based keyboard review:

- Default enabled languages are now English and Urdu.
- Urdu is guaranteed in the enabled language cycle and appears in the language panel.
- Arabic is optional: selecting Arabic from the language panel adds it to the enabled cycle.
- Urdu layout changed to a Gboard-style one-screen pattern with four Urdu letter rows and no separate number row.
- English layout keeps the Gboard-style number row plus QWERTY rows.
- Urdu long-press alternates added for related/sub letters, including آ/أ/إ on ا, ۃ/ۂ/ھ on ہ, ؤ/ئ/hamza mark on ء, ئے on ے, ں on ن, ژ on ز, گ on ک, چ on ج, پ on ب, ٹ/ث on ت, ڈ/ذ on د, and others.
- Arabic long-press alternates retained for optional Arabic.
- Bottom row now follows a familiar keyboard pattern: symbols, emoji, globe/language, space with language label, marks/period, enter.
- Toolbar now uses compact action icons for theme, mic, clips, emoji, language manager, and next keyboard.
- Theme button opens a selectable panel instead of cycling.
- Screenshot-inspired theme styles added: Classic dark, Soft ivory, Neon glass, Midnight gold, and Graphite.
- Button backgrounds now use rounded drawable styling with strokes/elevation instead of flat color blocks.

Limits:

- Exact Google/Gboard branding, icons, and proprietary styling are intentionally not copied.
- Runtime downloadable language packs require a separate language-pack backend or packaged dictionaries/layout bundles. This build includes the language manager foundation and optional built-in Arabic.
- Android does not let ClipVault show its own keyboard-select button beside input fields while another IME is active. That area is controlled by the Android system keyboard switcher.

## 2026-06-03 VaultBoard Compact UX Fixes

Implemented after compact keyboard review:

- Keyboard height reduced by roughly 25% from the previous build.
- Key row heights and vertical padding reduced.
- Emoji panel expanded from a small fixed pack into a paged emoji grid with broad common categories.
- Emoji page navigation added.
- Urdu and Arabic top letter rows now expose numbers 1-0 through long-press alternates.
- Keyboard clipboard panel now loads the last 10 clips.
- Clipboard panel changed from vertical list style to a 2-column grid style.
- Theme selector buttons now use each theme's own preview colors.
- Delete button behavior changed so press/release no longer recreates the keyboard view; this should prevent keyboard closing after one delete.
- Diacritic panel labels now show dotted standalone marks instead of all marks attached to alif.
- Language panel now has built-in Arabic add/remove controls and required English/Urdu indicators.
- Keyboard config migration added so existing installs reset to the guaranteed English + Urdu baseline for this version.

Limits:

- "All market emojis" is treated as an expanded built-in common emoji set with pages. A truly complete, always-current Unicode emoji database would need a dedicated emoji metadata package and periodic updates.
- Language download is implemented as a built-in pack workflow for Arabic. Real network-downloaded language packs need a separate signed language-pack distribution system.

## 2026-06-03 VaultBoard Symbols / Voice / Scroll Update

Implemented after the latest keyboard review:

- Urdu keyboard bottom row now includes direct Urdu comma and full stop: `،` and `۔`.
- English and symbols bottom rows now include direct comma and full stop: `,` and `.`.
- Symbols mode now keeps full keyboard height and exposes five horizontally scrollable symbol rows instead of a short three-row panel.
- Symbols mode return button now shows the previous text language (`English`, `اردو`, or `عربي`) instead of always showing `ABC`.
- Symbols mode bottom row now keeps a visible delete key.
- Emoji panel changed from left/right paged navigation to a vertically scrollable emoji grid.
- Long-press alternates now open as a small near-key standalone popup instead of replacing the full keyboard with an alternates panel.
- Voice typing now normalizes common Urdu `ا` / `آ` recognition mistakes such as `امین` to `آمین`.
- Voice typing now treats speech-session silence as a line-break boundary and restarts listening while mic mode remains active.
- Realtime suggestion strip now prioritizes correction candidates, prefix matches, fuzzy matches, and common recommendations.
- Urdu/Arabic key labels now request Nastaliq-style system typefaces first (`Noto Nastaliq Urdu`, `Jameel Noori Nastaleeq`) with Android fallback.

Limits:

- A keyboard can send characters, but it cannot force the receiving app's editor to render typed text in Nastaliq. The target app controls its own text font.
- Full Gboard-style drag-selection for long-press alternates needs a custom touch engine. This build removes the disruptive full alternates panel and uses a near-key popup.
- Real downloadable world-language packs still need a separate signed language-pack backend or packaged layout/dictionary bundle system. The current panel exposes the pack-manager workflow foundation.

## 2026-06-03 Dashboard / Capture / Suggestions Fix

Implemented after dashboard and keyboard QA:

- Dashboard Capture button now performs a forced manual clipboard capture instead of being blocked by the in-memory duplicate guard.
- Existing duplicate clip captures now refresh the saved clip timestamp instead of silently doing nothing.
- Capture action now gives visible toast feedback and haptic feedback.
- Category tabs/chips were restyled with stronger contrast, larger text, fixed height, centered labels, and clearer active state.
- Keyboard touch handling was changed to a more responsive ACTION_DOWN path for normal keys and smaller inter-key gaps for easier touch targets.
- Long-press behavior is preserved for alternate-letter keys and language-manager access.
- Keyboard suggestions now combine fixed dictionaries, typo maps, and words learned from recently saved native clips.
- Auto-correction now includes common English typo corrections and Urdu `ا` / `آ` corrections.

Limits:

- This is still a lightweight local suggestion engine, not a large neural prediction model. It improves with saved clips and built-in word lists, but true Gboard-level prediction requires a much larger language model/dictionary pipeline.

## Best Differentiation Strategy

ClipVault should become a privacy-first "memory layer" for copied text, not just a passive clipboard history list.

Recommended features that can create real differentiation:

1. Reliable Capture Hub
   - Add Android Share Target: users can share selected text, URLs, images, or documents into ClipVault.
   - Add clipboard keyboard / paste panel: this is the most reliable way to capture and paste across apps under modern Android limits.
   - Add quick settings tile or persistent foreground action only if it is clearly explained and policy-safe.

2. Sensitive Clip Protection
   - Auto-detect OTPs, passwords, tokens, API keys, card-like numbers, emails, phone numbers.
   - Add "expire after 30 seconds / 5 minutes / app close" rules.
   - Add masked preview for sensitive clips until biometric unlock.
   - Add one-tap redact before sharing.

3. Semantic Search
   - Search by meaning, not only exact words.
   - Example: searching "client phone" should find a copied number or WhatsApp message.
   - First implementation can be local keyword/category scoring; later can use embeddings.

4. Clip Timeline and Versioning
   - Detect near-duplicates and group them as versions.
   - Useful for prompts, code snippets, messages, captions, and contract text.

5. Smart Actions
   - URLs: clean trackers, open, save domain, QR code.
   - Emails: compose, extract domain, save contact.
   - Phone numbers: WhatsApp, call, save contact.
   - Code: detect language, format, copy fenced Markdown.
   - Long text: summarize, extract tasks, rewrite tone.

6. Workspaces
   - Personal, Work, Social, Code, Clients, Research.
   - Each workspace can have its own retention, biometric lock, and export settings.

7. Offline OCR Intake
   - Let users share screenshots/images into ClipVault and extract text.
   - This solves a real pain: text trapped inside images, PDFs, social posts, receipts, and screenshots.

8. Template Memory
   - Save reusable replies, business intros, customer support responses, hashtags, prompts, addresses.
   - Add variables like `{name}`, `{date}`, `{phone}`, `{brand}`.

9. Privacy Dashboard
   - Show what is stored, what is sensitive, what will expire, and what has been exported.
   - Give a "panic clean" button for sensitive clips.

10. Optional Encrypted Sync Later
   - Do not add cloud sync until the privacy policy, encryption model, and Data Safety answers are mature.
   - If added, prefer end-to-end encryption with user-controlled passphrase.

## Suggested Build Phases

### Phase 1 - Done Now

- Add MindCrafts_AI identity.
- Add logo/icon/splash assets.
- Add support links.
- Clean Android permissions.
- Build installable branded APK.

### Phase 2 - Play Store Compliance

- Upgrade Expo/Android stack so target SDK is 35+.
- Build signed release AAB.
- Create and publish privacy policy on `mindcrafts.net` or `codehubb.com`.
- Complete Data Safety form honestly as local-first/no cloud collection unless future features change it.
- Prepare screenshots, short description, full description, feature graphic, and app icon.
- Run closed testing if required by account type.

### Phase 3 - Reliability Upgrade

- Add Android Share Target.
- Add manual capture flow.
- Add keyboard/paste panel or quick-paste surface. Initial ClipVault Keyboard implementation is done.
- Improve empty states and onboarding to explain Android clipboard limits without sounding broken.

### Phase 4 - Differentiating Intelligence

- Add smart sensitive detection.
- Add auto-expiry rules.
- Add semantic search.
- Add duplicate/version grouping.
- Add smart actions.

### Phase 5 - Premium Layer

- Add workspaces.
- Add encrypted backup/sync.
- Add desktop bridge or browser extension.
- Add local AI rewrite/summarize/extract tools.

## Store Listing Positioning

Recommended positioning:

ClipVault by MindCrafts_AI is a private clipboard memory vault for people who copy important text, links, codes, prompts, contact details, and ideas every day.

Short description idea:

Private clipboard history, smart search, secure clips, and export tools by MindCrafts_AI.

Core promise:

Copy once. Find it again. Keep it private.

## Sources Checked

- Google Play target API requirement: https://support.google.com/googleplay/android-developer/answer/11926878
- Android clipboard access limits: https://developer.android.com/about/versions/10/privacy/changes
- Android input method service guidance: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- Android SpeechRecognizer API: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android 13 clipboard preview and sensitive content guidance: https://developer.android.com/guide/topics/text/copy-paste
- Google Play Data Safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Google Play testing requirements for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Android App Bundle publishing requirement: https://developer.android.com/guide/app-bundle
- Competitor examples: Clipper and Clipboard Manager Play listings, Microsoft SwiftKey clipboard support, ClipZ feature pages.
