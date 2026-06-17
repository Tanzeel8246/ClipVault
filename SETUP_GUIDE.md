# 🚀 ClipVault — Setup & Launch Guide

## ایپ انسٹال اور چلانے کا طریقہ

### ضروری چیزیں
```
✅ Node.js 18+ (nodejs.org سے ڈاؤنلوڈ کریں)
✅ VS Code
✅ Android Studio یا Xcode (iOS کے لیے Mac ضروری)
✅ Expo Go app موبائل پر
```

---

## Step 1 — Project بنائیں

```bash
# Expo install کریں
npm install -g expo-cli eas-cli

# نیا project بنائیں
npx create-expo-app ClipVault --template blank-typescript

cd ClipVault
```

## Step 2 — یہ files کاپی کریں

```
ClipVault/
  ├── app/
  │   ├── _layout.tsx         ← Tab navigation
  │   ├── (tabs)/
  │   │   ├── index.tsx       ← HomeScreen.tsx (ہماری file)
  │   │   ├── favorites.tsx
  │   │   └── settings.tsx    ← SettingsScreen.tsx (ہماری file)
  ├── services/
  │   ├── ClipboardMonitor.ts ← ہماری file
  │   └── CoreServices.ts     ← ہماری file
  └── package.json            ← ہماری file
```

## Step 3 — Dependencies انسٹال کریں

```bash
npx expo install \
  expo-clipboard \
  expo-sqlite \
  expo-file-system \
  expo-sharing \
  expo-print \
  expo-notifications \
  expo-local-authentication \
  expo-crypto \
  expo-haptics \
  @react-native-async-storage/async-storage \
  react-native-safe-area-context \
  zustand \
  firebase
```

## Step 4 — موبائل پر test کریں

```bash
npx expo start
```
موبائل پر **Expo Go** app کھولیں → QR code scan کریں → ایپ چل جائے گی!

## Step 5 — Android APK بنائیں

```bash
# EAS account بنائیں (مفت)
eas login

# Build کریں
eas build --platform android --profile preview
```

---

## Background Monitoring — Android

Android پر true background monitoring کے لیے:

```bash
# Bare workflow میں eject کریں
npx expo eject

# پھر android/app/src/main/java/com/clipvault/ میں:
# ClipboardService.kt کاپی کریں (کوڈ ClipboardMonitor.ts میں موجود ہے)
```

**آسان طریقہ:** ایپ foreground میں ہو تو 100% کام کرے گی۔
Background کے لیے ایک notification service چلتی رہے گی۔

---

## Firebase Setup (Cloud Backup)

1. [Firebase Console](https://console.firebase.google.com) جائیں
2. نیا project بنائیں: **clipvault-app**
3. Android app add کریں → `google-services.json` ڈاؤنلوڈ کریں
4. `android/app/` میں ڈالیں
5. Firestore Database enable کریں

```typescript
// services/CloudSync.ts
import { initializeApp } from 'firebase/app';
import { getFirestore, collection, addDoc, getDocs } from 'firebase/firestore';
import { getAuth, signInWithGoogle } from 'firebase/auth';

const firebaseConfig = {
  apiKey: "آپ کی KEY",
  projectId: "clipvault-app",
  // ... باقی config
};

const app = initializeApp(firebaseConfig);
const db = getFirestore(app);

export const syncToCloud = async (clips: ClipItem[]) => {
  const ref = collection(db, 'clips');
  for (const clip of clips) {
    await addDoc(ref, clip);
  }
};
```

---

## Play Store پر Publish کریں

```bash
# Production build
eas build --platform android --profile production

# Submit
eas submit --platform android
```

**Play Store Requirements:**
- Privacy Policy URL (ضروری ہے)
- App Screenshots (5-8)
- Feature graphic (1024×500)
- App icon (512×512)

---

## App Store Listing

**App Name:** ClipVault - Clipboard Manager
**Category:** Productivity
**Price:** Free (with Pro IAP)

**Keywords:**
clipboard manager, copy paste manager, clip saver, auto clipboard,
text manager, clipboard history, note saver, copy tracker

---

## Revenue کیسے کریں؟

```typescript
// services/Purchases.ts
import Purchases from 'react-native-purchases';

// RevenueCat استعمال کریں (آسان ترین)
Purchases.configure({ apiKey: 'your_revenuecat_key' });

const offerings = await Purchases.getOfferings();
// Pro Monthly: $2.99
// Pro Yearly: $19.99
// Lifetime: $49.99
```

---

## اگلے Steps

1. ✅ Expo Go پر test کریں
2. ✅ APK بنائیں اور اصل موبائل پر test کریں
3. ✅ Firebase connect کریں
4. ✅ Play Store account بنائیں ($25 ایک بار)
5. ✅ ایپ submit کریں
6. ✅ Marketing شروع کریں

**مارکیٹنگ کے لیے:**
- TikTok/Instagram Reels: "میں نے ایک ایپ بنائی جو سب کچھ یاد رکھتی ہے"
- Reddit: r/androidapps, r/productivity
- Product Hunt launch

---

*ClipVault — "Copy Once, Remember Forever"*
*by [آپ کا نام]*
