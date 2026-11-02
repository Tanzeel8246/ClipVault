import AsyncStorage from '@react-native-async-storage/async-storage';
import React, { useMemo, useState, useEffect } from 'react';
import {
  Image,
  PermissionsAndroid,
  Platform,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  Modal,
  ScrollView,
  AppState,
  ActivityIndicator,
  TextInput,
  Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  openKeyboardSettings,
  showKeyboardPicker,
  startBackgroundMonitor,
  isKeyboardEnabled,
  isKeyboardSelected,
} from '../services/BackgroundMonitor';
import { GoogleDriveBackupService } from '../services/GoogleDriveBackup';
import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import { NASTALEEQ_FONT } from '../services/i18n';
import { AppLanguage, ClipSettings, DEFAULT_SETTINGS, SETTINGS_KEY } from '../services/types';
import { useClipStore } from '../store/useClipStore';
import { ThemeColors } from '../theme/colors';
import { useAppTheme } from '../theme/useAppTheme';

const ONBOARDING_VERSION_KEY = 'onboarding_v2_complete';

interface Props {
  onComplete: () => void;
}

type PrivacyMode = 'standard' | 'strict';

async function readSettings(): Promise<ClipSettings> {
  try {
    const saved = await AsyncStorage.getItem(SETTINGS_KEY);
    return saved ? { ...DEFAULT_SETTINGS, ...JSON.parse(saved) } : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}

const WIZARD_TEXT = {
  en: {
    brand: 'ClipVault',
    brandSub: 'by MindCrafts_AI',
    langButton: '🌐 Language',
    welcomeTitle: 'Welcome',
    welcomeSubtitle: 'Select App Language',
    enCardTitle: 'English',
    enCardDesc: 'Standard English interface and typography',
    urCardTitle: 'Urdu (اردو)',
    urCardDesc: 'Pure Urdu interface with Jameel Noori Nastaleeq font',
    stepTitle: '🔒 Privacy & Setup',
    policyTitle: 'Privacy Policy Summary',
    policyBullet1: 'Your clipboard clips are stored 100% locally on this device. We never upload your data to any servers.',
    policyBullet2: 'Sensitive data like OTPs, passwords, and card numbers are automatically masked and kept secure.',
    sec1: '1. App Permissions',
    sec1Btn: 'Notifications Permission',
    sec2: '2. Keyboard Setup (Required)',
    sec2Desc: 'Auto clipboard capture requires the custom keyboard. Please enable it in Settings first, then select it as active.',
    kbdEnabled: '✅ Keyboard Enabled',
    kbdEnableBtn: '⚙️ Enable Keyboard',
    kbdActive: '✅ Keyboard Active',
    kbdSelectBtn: '🔄 Select Keyboard',
    sec3: '3. Cloud Backup (Optional)',
    sec3Desc: 'Securely back up your clipboard data to your personal Google Drive hidden app folder. Useful for restoring data on new devices.',
    driveConnected: '✓ Google Drive Connected',
    driveDisconnect: 'Disconnect',
    driveConnect: '🔗 Connect Google Drive',
    sec4: '4. Security Lock (Optional)',
    sec4Title: '🔐 App Lock',
    sec4Desc: 'Require phone fingerprint, PIN, pattern, or passcode on launch.',
    sec4Enabled: 'Enabled',
    sec4Enable: 'Enable',
    sec5: '5. Select Privacy Mode',
    privacyStdTitle: 'Standard Privacy',
    privacyStdDesc: 'Mask sensitive clips but keep them until you manually delete them.',
    privacyStrictTitle: 'Strict Privacy',
    privacyStrictDesc: 'Mask OTP/passwords and automatically delete sensitive clips after 5 minutes.',
    finishBtn: 'Finish Setup & Start',
    introTitle: 'Welcome to ClipVault! 🎉',
    introSub: 'Your secure local clipboard manager',
    introG1Title: 'Auto Copy Capture',
    introG1Desc: 'Any text, links, emails, and phone numbers you copy are saved locally into searchable categories.',
    introG2Title: 'Search & Favorites',
    introG2Desc: 'Easily filter and find saved clips, and pin important templates to access them quickly.',
    introG3Title: 'Custom Keyboard',
    introG3Desc: 'Use the custom keyboard to access and paste clips directly inside any application.',
    introBtn: 'Let\'s Get Started',
  },
  ur: {
    brand: 'کلپ والٹ',
    brandSub: 'پیشکش: مائنڈ کرافٹس',
    langButton: '🌐 زبان بدلیں',
    welcomeTitle: 'خوش آمدید',
    welcomeSubtitle: 'ایپ کی زبان منتخب کریں',
    enCardTitle: 'انگریزی (English)',
    enCardDesc: 'انگریزی زبان اور معیاری خط کا انتخاب',
    urCardTitle: 'اردو',
    urCardDesc: 'خالص اردو انٹرفیس اور جمیل نوری نستعلیق فونٹ',
    stepTitle: '🔒 رازداری اور ترتیبات',
    policyTitle: 'رازداری کی پالیسی کا خلاصہ',
    policyBullet1: 'آپ کے تمام کلپس مکمل طور پر اسی موبائل میں محفوظ رہتے ہیں۔ ہم کبھی بھی آپ کا ڈیٹا کسی سرور پر نہیں بھیجتے۔',
    policyBullet2: 'حساس معلومات، پاس ورڈ اور کارڈ نمبرز خود بخود محفوظ اور اوجھل رکھے جاتے ہیں۔',
    sec1: '۱۔ ایپ کی اجازتیں',
    sec1Btn: 'اطلاعات (نوٹیفکیشن) کی اجازت دیں',
    sec2: '۲۔ کی بورڈ سیٹ اپ (لازمی)',
    sec2Desc: 'کلپس کو خود کار طریقے سے محفوظ کرنے کے لیے کی بورڈ کو فعال اور منتخب کرنا لازمی ہے۔ پہلے اسے ترتیبات میں فعال کریں، اور پھر اسے منتخب کریں۔',
    kbdEnabled: '✅ کی بورڈ فعال ہے',
    kbdEnableBtn: '⚙️ کی بورڈ فعال کریں',
    kbdActive: '✅ کی بورڈ منتخب ہے',
    kbdSelectBtn: '🔄 کی بورڈ منتخب کریں',
    sec3: '۳۔ کلاؤڈ بیک اپ (اختیاری)',
    sec3Desc: 'اپنے کلپ بورڈ ڈیٹا کو گوگل ڈرائیو پر محفوظ کریں۔ یہ ڈیٹا محفوظ رہتا ہے اور نئے موبائل پر منتقلی کے لیے کارآمد ہے۔',
    driveConnected: '✓ گوگل ڈرائیو منسلک ہے',
    driveDisconnect: 'منقطع کریں',
    driveConnect: '🔗 گوگل ڈرائیو منسلک کریں',
    sec4: '۴۔ سیکیورٹی لاک (اختیاری)',
    sec4Title: '🔐 ایپ پر قفل لگائیں',
    sec4Desc: 'ایپ کھولنے پر انگلی کا نشان (فنگر پرنٹ)، کوڈ یا پاس ورڈ کی تصدیق لازمی بنائیں۔',
    sec4Enabled: 'فعال ہے',
    sec4Enable: 'فعال کریں',
    sec5: '۵۔ پرائیویسی موڈ کا انتخاب',
    privacyStdTitle: 'عام رازداری',
    privacyStdDesc: 'حساس مواد پوشیدہ رہے گا اور دستی طور پر حذف کرنے تک محفوظ رہے گا۔',
    privacyStrictTitle: 'سخت رازداری',
    privacyStrictDesc: 'پاس ورڈ اور خفیہ کوڈز ۵ منٹ بعد خود بخود مٹ جائیں گے۔',
    finishBtn: 'سیٹ اپ مکمل کریں اور شروع کریں',
    introTitle: 'کلپ والٹ میں خوش آمدید! 🎉',
    introSub: 'آپ کا محفوظ اور ذاتی کلپ بورڈ مینیجر',
    introG1Title: 'خودکار کاپی کا اندراج',
    introG1Desc: 'آپ جو بھی تحریر، ربط، ای میل یا فون نمبر کاپی کریں گے وہ خود بخود متعلقہ زمرے میں محفوظ ہو جائے گا۔',
    introG2Title: 'تلاش اور پسندیدہ کلپس',
    introG2Desc: 'محفوظ شدہ کلپس کو باآسانی تلاش کریں، اور اہم پیغامات کو سرفہرست پن کریں۔',
    introG3Title: 'مخصوص کی بورڈ',
    introG3Desc: 'کسی بھی چیٹ ایپ میں اپنے کلپس براہِ راست چسپاں کرنے کے لیے کی بورڈ استعمال کریں۔',
    introBtn: 'سیٹ اپ شروع کریں',
  },
};

export function OnboardingWizard({ onComplete }: Props) {
  const T = useAppTheme();
  const styles = useMemo(() => getStyles(T), [T]);
  const loadSettings = useClipStore(state => state.loadSettings);
  
  // Wizard state variables
  const [step, setStep] = useState(0);
  const [lang, setLang] = useState<AppLanguage>('ur');
  const [privacyMode, setPrivacyMode] = useState<PrivacyMode>('standard');
  const [isIntroVisible, setIsIntroVisible] = useState(false);
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;

  // Keyboard check states
  const [kbdEnabled, setKbdEnabled] = useState(false);
  const [kbdSelected, setKbdSelected] = useState(false);

  // Security lock states
  const [hasBiometric, setHasBiometric] = useState(false);
  const [appLockEnabled, setAppLockEnabled] = useState(false);

  // Google Drive states
  const [gdriveEmail, setGdriveEmail] = useState('');
  const [driveBusy, setDriveBusy] = useState(false);

  // Read/Save settings helper
  const saveSettings = async (overrides: Partial<ClipSettings>) => {
    const current = await readSettings();
    await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify({ ...current, ...overrides }));
    await loadSettings();
  };

  // Check keyboard state
  const checkKeyboard = async () => {
    try {
      const enabled = await isKeyboardEnabled();
      const selected = await isKeyboardSelected();
      setKbdEnabled(enabled);
      setKbdSelected(selected);
    } catch {
      // Ignore
    }
  };

  // Bootstrap biometrics and keyboard status
  useEffect(() => {
    const bootstrap = async () => {
      const hasHardware = await LocalAuthentication.hasHardwareAsync();
      const isEnrolled = await LocalAuthentication.isEnrolledAsync();
      setHasBiometric(hasHardware && isEnrolled);
      await checkKeyboard();
    };
    void bootstrap();

    // Check keyboard whenever returning to app
    const sub = AppState.addEventListener('change', (nextState) => {
      if (nextState === 'active') {
        void checkKeyboard();
      }
    });

    return () => sub.remove();
  }, []);

  // Fetch Google account status on step 1 (Setup Screen)
  useEffect(() => {
    if (step === 1) {
      void GoogleDriveBackupService.getAccountEmail().then(email => {
        setGdriveEmail(email);
      });
    }
  }, [step]);

  // Load transient state in case of redirect
  useEffect(() => {
    const loadTempState = async () => {
      const tempLang = await AsyncStorage.getItem('onboarding_temp_lang');
      const tempStep = await AsyncStorage.getItem('onboarding_temp_step');
      if (tempLang) setLang(tempLang as AppLanguage);
      if (tempStep) setStep(Number(tempStep));
    };
    void loadTempState();
  }, []);

  const changeStep = async (nextStep: number) => {
    setStep(nextStep);
    await AsyncStorage.setItem('onboarding_temp_step', String(nextStep));
  };

  const changeLang = async (nextLang: AppLanguage) => {
    setLang(nextLang);
    await AsyncStorage.setItem('onboarding_temp_lang', nextLang);
  };

  const selectLanguage = async (selectedLang: AppLanguage) => {
    await changeLang(selectedLang);
    await changeStep(1);
    setIsIntroVisible(true);
  };

  const requestNotificationPermission = async () => {
    if (Platform.OS === 'android') {
      try {
        if (Number(Platform.Version) >= 33) {
          await PermissionsAndroid.request('android.permission.POST_NOTIFICATIONS' as any);
        }
      } catch {
        // Notification permission should not block first run.
      }
    }
  };

  const connectGoogle = async () => {
    try {
      setDriveBusy(true);
      await GoogleDriveBackupService.startSignIn();
    } catch (err) {
      Alert.alert(
        lang === 'en' ? 'Google Sign-in Failed' : 'Google سائن اِن ناکام',
        err instanceof Error ? err.message : String(err)
      );
    } finally {
      setDriveBusy(false);
    }
  };

  const disconnectGoogle = async () => {
    Alert.alert(
      lang === 'en' ? 'Disconnect Google Drive?' : 'Google Drive منقطع کریں؟',
      lang === 'en'
        ? 'Automatic Drive backup will stop.'
        : 'Drive آٹو بیک اپ بند ہو جائے گا۔',
      [
        { text: lang === 'en' ? 'Cancel' : 'منسوخ', style: 'cancel' },
        {
          text: lang === 'en' ? 'Disconnect' : 'منقطع کریں',
          style: 'destructive',
          onPress: async () => {
            await GoogleDriveBackupService.signOut();
            setGdriveEmail('');
          },
        },
      ]
    );
  };

  const finishSetup = async () => {
    if (Platform.OS === 'android') {
      await startBackgroundMonitor(0);
    }

    await saveSettings({
      appLanguage: lang,
      monitoringEnabled: true,
      backgroundMonitorEnabled: true,
      autoMonitorMinutes: 0,
      showNotifications: true,
      privacyDisclosureAccepted: true,
      maskSensitive: true,
      sensitiveExpiry: privacyMode === 'strict' ? '5m' : 'never',
      biometricLock: appLockEnabled,
      pinLockEnabled: false,
    });

    await AsyncStorage.setItem('onboarding_complete', 'true');
    await AsyncStorage.setItem(ONBOARDING_VERSION_KEY, 'true');
    await AsyncStorage.removeItem('onboarding_temp_step');
    await AsyncStorage.removeItem('onboarding_temp_lang');
    onComplete();
  };

  // Render language selection card screen
  const renderLanguageSelection = () => {
    const cur = WIZARD_TEXT[lang];
    const textNasta = lang === 'ur' ? { fontFamily: NASTALEEQ_FONT } : {};

    return (
      <View style={styles.langContainer}>
        <Text style={[styles.langWelcomeTitle, textNasta]}>{cur.welcomeTitle}</Text>
        <Text style={[styles.langWelcomeSubtitle, textNasta]}>{cur.welcomeSubtitle}</Text>
        
        <TouchableOpacity 
          style={styles.langCard} 
          onPress={() => selectLanguage('en')}
          activeOpacity={0.9}
        >
          <Text style={styles.langCardEmoji}>🇬🇧</Text>
          <View style={styles.langCardTextContainer}>
            <Text style={styles.langCardTitle}>English</Text>
            <Text style={styles.langCardSubtitle}>{cur.enCardDesc}</Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity 
          style={styles.langCard} 
          onPress={() => selectLanguage('ur')}
          activeOpacity={0.9}
        >
          <Text style={styles.langCardEmoji}>🇵🇰</Text>
          <View style={styles.langCardTextContainer}>
            <Text style={[styles.langCardTitle, { fontFamily: NASTALEEQ_FONT }]}>اردو</Text>
            <Text style={[styles.langCardSubtitle, { fontFamily: NASTALEEQ_FONT }]}>{cur.urCardDesc}</Text>
          </View>
        </TouchableOpacity>
      </View>
    );
  };

  // Render Setup & Privacy & Permissions screen
  const renderSetupScreen = () => {
    const cur = WIZARD_TEXT[lang];
    const textNasta = lang === 'ur' ? { fontFamily: NASTALEEQ_FONT } : {};

    return (
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        <Text style={[styles.stepTitle, textNasta]}>
          {cur.stepTitle}
        </Text>

        {/* Privacy summary card */}
        <View style={styles.privacySummaryCard}>
          <Text style={[styles.privacySummaryTitle, textNasta]}>
            {cur.policyTitle}
          </Text>
          <View style={styles.privacyBulletRow}>
            <Text style={styles.bulletIcon}>🛡️</Text>
            <Text style={[styles.privacyBulletText, textNasta]}>
              {cur.policyBullet1}
            </Text>
          </View>
          <View style={styles.privacyBulletRow}>
            <Text style={styles.bulletIcon}>🔑</Text>
            <Text style={[styles.privacyBulletText, textNasta]}>
              {cur.policyBullet2}
            </Text>
          </View>
        </View>

        {/* Permissions setup section */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {cur.sec1}
        </Text>
        <TouchableOpacity style={styles.permissionActionBtn} onPress={() => void requestNotificationPermission()}>
          <Text style={[styles.permissionActionText, textNasta]}>
            {cur.sec1Btn}
          </Text>
        </TouchableOpacity>

        {/* Keyboard configuration section */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {cur.sec2}
        </Text>
        <Text style={[styles.sectionDesc, textNasta]}>
          {cur.sec2Desc}
        </Text>
        
        <View style={styles.keyboardBtnRow}>
          <TouchableOpacity 
            style={[styles.kbdConfigBtn, kbdEnabled && { borderColor: T.green, backgroundColor: `${T.green}11` }]} 
            onPress={() => void openKeyboardSettings()}
          >
            <Text style={[styles.kbdConfigBtnText, textNasta, kbdEnabled && { color: T.green }]}>
              {kbdEnabled ? cur.kbdEnabled : cur.kbdEnableBtn}
            </Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            disabled={!kbdEnabled}
            style={[
              styles.kbdConfigBtn, 
              !kbdEnabled && { opacity: 0.4 },
              kbdSelected && { borderColor: T.green, backgroundColor: `${T.green}11` }
            ]} 
            onPress={() => void showKeyboardPicker()}
          >
            <Text style={[styles.kbdConfigBtnText, textNasta, kbdSelected && { color: T.green }]}>
              {kbdSelected ? cur.kbdActive : cur.kbdSelectBtn}
            </Text>
          </TouchableOpacity>
        </View>

        {/* Google Drive Backup (Optional) */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {cur.sec3}
        </Text>
        <Text style={[styles.sectionDesc, textNasta]}>
          {cur.sec3Desc}
        </Text>
        
        {gdriveEmail ? (
          <View style={{
            flexDirection: 'row',
            alignItems: 'center',
            backgroundColor: `${T.green}11`,
            borderColor: T.green,
            borderWidth: 1,
            borderRadius: 12,
            padding: 14,
            marginBottom: 16,
            justifyContent: 'space-between'
          }}>
            <View style={{ flex: 1 }}>
              <Text style={[{ color: T.green, fontSize: 13, fontWeight: '800' }, textNasta]}>
                {cur.driveConnected}
              </Text>
              <Text style={{ color: T.muted, fontSize: 12, marginTop: 2 }}>{gdriveEmail}</Text>
            </View>
            <TouchableOpacity 
              onPress={() => void disconnectGoogle()}
              style={{
                backgroundColor: `${T.red}15`,
                paddingHorizontal: 10,
                paddingVertical: 6,
                borderRadius: 8,
                borderWidth: 1,
                borderColor: T.red,
              }}
            >
              <Text style={[{ color: T.red, fontSize: 11, fontWeight: '800' }, textNasta]}>
                {cur.driveDisconnect}
              </Text>
            </TouchableOpacity>
          </View>
        ) : (
          <TouchableOpacity 
            style={[styles.permissionActionBtn, { borderColor: T.accent, backgroundColor: `${T.accent}11`, marginBottom: 16 }]} 
            onPress={() => void connectGoogle()}
            disabled={driveBusy}
          >
            {driveBusy ? (
              <ActivityIndicator size="small" color={T.accent} />
            ) : (
              <Text style={[styles.permissionActionText, { color: T.accent }, textNasta]}>
                {cur.driveConnect}
              </Text>
            )}
          </TouchableOpacity>
        )}

        {/* Security Lock option */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {cur.sec4}
        </Text>
        
        <View style={{
          flexDirection: 'row',
          alignItems: 'center',
          backgroundColor: appLockEnabled ? `${T.accent}11` : T.surface,
          borderColor: appLockEnabled ? T.accent : T.border,
          borderWidth: 1,
          borderRadius: 12,
          padding: 14,
          marginBottom: 16,
          justifyContent: 'space-between'
        }}>
          <View style={{ flex: 1, paddingRight: 10 }}>
            <Text style={[{ color: T.text, fontSize: 15, fontWeight: '800' }, textNasta]}>
              {cur.sec4Title}
            </Text>
            <Text style={[{ color: T.muted, fontSize: 12, marginTop: 4 }, textNasta]}>
              {cur.sec4Desc}
            </Text>
          </View>
          <TouchableOpacity 
            onPress={async () => {
              if (!appLockEnabled) {
                try {
                  const result = await LocalAuthentication.authenticateAsync({
                    promptMessage: lang === 'en' ? 'Confirm Screen Lock' : 'سکرین لاک کی تصدیق کریں',
                    disableDeviceFallback: false,
                  });
                  if (result.success) {
                    setAppLockEnabled(true);
                  } else {
                    if ('error' in result && result.error === 'not_enrolled') {
                      Alert.alert(
                        lang === 'en' ? 'No Device Lock Set' : 'کوئی لاک سیٹ نہیں ہے',
                        lang === 'en'
                          ? 'Please enable a PIN, pattern, password, or biometric lock in your phone settings first.'
                          : 'براہِ کرم پہلے اپنے فون کی ترتیبات میں پن، پیٹرن، پاس ورڈ، یا فنگر پرنٹ لاک فعال کریں۔'
                      );
                    }
                  }
                } catch (err) {
                  Alert.alert(
                    lang === 'en' ? 'Error' : 'خرابی',
                    err instanceof Error ? err.message : String(err)
                  );
                }
              } else {
                setAppLockEnabled(false);
              }
            }}
            style={{
              backgroundColor: appLockEnabled ? T.accent : T.border,
              paddingHorizontal: 16,
              paddingVertical: 10,
              borderRadius: 8,
            }}
          >
            <Text style={[{ color: '#fff', fontSize: 13, fontWeight: '800' }, textNasta]}>
              {appLockEnabled ? cur.sec4Enabled : cur.sec4Enable}
            </Text>
          </TouchableOpacity>
        </View>

        {/* Privacy Mode choice */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {cur.sec5}
        </Text>
        <PrivacyChoice
          active={privacyMode === 'standard'}
          title={cur.privacyStdTitle}
          desc={cur.privacyStdDesc}
          onPress={() => setPrivacyMode('standard')}
          T={T}
          nasta={nasta}
        />
        <PrivacyChoice
          active={privacyMode === 'strict'}
          title={cur.privacyStrictTitle}
          desc={cur.privacyStrictDesc}
          onPress={() => setPrivacyMode('strict')}
          T={T}
          nasta={nasta}
        />

        <PrimaryButton 
          label={cur.finishBtn} 
          onPress={() => void finishSetup()} 
          T={T} 
          nasta={nasta}
        />
      </ScrollView>
    );
  };

  // Render app introduction and guide inside a popup Modal overlay
  const renderIntroModal = () => {
    const cur = WIZARD_TEXT[lang];
    const textNasta = lang === 'ur' ? { fontFamily: NASTALEEQ_FONT } : {};

    return (
      <Modal visible={isIntroVisible} transparent animationType="fade">
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={[styles.modalTitle, textNasta]}>
              {cur.introTitle}
            </Text>
            <Text style={[styles.modalSubtitle, textNasta]}>
              {cur.introSub}
            </Text>

            <ScrollView style={styles.modalScroll} showsVerticalScrollIndicator={false}>
              <View style={styles.guideStepItem}>
                <Text style={styles.guideStepIcon}>📋</Text>
                <View style={styles.guideStepTextWrap}>
                  <Text style={[styles.guideStepTitle, textNasta]}>
                    {cur.introG1Title}
                  </Text>
                  <Text style={[styles.guideStepDesc, textNasta]}>
                    {cur.introG1Desc}
                  </Text>
                </View>
              </View>

              <View style={styles.guideStepItem}>
                <Text style={styles.guideStepIcon}>📁</Text>
                <View style={styles.guideStepTextWrap}>
                  <Text style={[styles.guideStepTitle, textNasta]}>
                    {cur.introG2Title}
                  </Text>
                  <Text style={[styles.guideStepDesc, textNasta]}>
                    {cur.introG2Desc}
                  </Text>
                </View>
              </View>

              <View style={styles.guideStepItem}>
                <Text style={styles.guideStepIcon}>⌨️</Text>
                <View style={styles.guideStepTextWrap}>
                  <Text style={[styles.guideStepTitle, textNasta]}>
                    {cur.introG3Title}
                  </Text>
                  <Text style={[styles.guideStepDesc, textNasta]}>
                    {cur.introG3Desc}
                  </Text>
                </View>
              </View>
            </ScrollView>

            <TouchableOpacity 
              style={[sharedStyles.primaryButton, { backgroundColor: T.accent, marginTop: 15 }]} 
              onPress={() => setIsIntroVisible(false)}
              activeOpacity={0.85}
            >
              <Text style={[sharedStyles.primaryButtonText, textNasta]}>
                {cur.introBtn}
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    );
  };

  const cur = WIZARD_TEXT[lang];

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: T.bg }]}>
      <View style={styles.top}>
        <Image source={require('../assets/splash-icon.png')} style={styles.logo} />
        <View style={{ flex: 1 }}>
          <Text style={[styles.brand, nasta ? { fontFamily: nasta } : {}]}>{cur.brand}</Text>
          <Text style={[styles.brandSub, nasta ? { fontFamily: nasta } : {}]}>{cur.brandSub}</Text>
        </View>
        {step > 0 && (
          <TouchableOpacity style={styles.langResetBtn} onPress={() => setStep(0)}>
            <Text style={[styles.langResetText, nasta ? { fontFamily: nasta } : {}]}>
              {cur.langButton}
            </Text>
          </TouchableOpacity>
        )}
      </View>

      <View style={styles.content}>
        {step === 0 ? renderLanguageSelection() : renderSetupScreen()}
      </View>

      {renderIntroModal()}
    </SafeAreaView>
  );
}

function PrimaryButton({ label, onPress, T, nasta }: { label: string; onPress: () => void; T: ThemeColors; nasta?: string }) {
  return (
    <TouchableOpacity style={[sharedStyles.primaryButton, { backgroundColor: T.accent }]} onPress={onPress} activeOpacity={0.85}>
      <Text style={[sharedStyles.primaryButtonText, nasta ? { fontFamily: nasta } : {}]}>{label}</Text>
    </TouchableOpacity>
  );
}

function PrivacyChoice({
  active,
  title,
  desc,
  onPress,
  T,
  nasta,
}: {
  active: boolean;
  title: string;
  desc: string;
  onPress: () => void;
  T: ThemeColors;
  nasta?: string;
}) {
  return (
    <TouchableOpacity
      style={[sharedStyles.choice, { borderColor: active ? T.accent : T.border, backgroundColor: active ? `${T.accent}22` : T.surface }]}
      onPress={onPress}
      activeOpacity={0.85}
    >
      <Text style={[sharedStyles.choiceTitle, { color: active ? T.accent : T.text }, nasta ? { fontFamily: nasta } : {}]}>{title}</Text>
      <Text style={[sharedStyles.choiceDesc, { color: T.muted }, nasta ? { fontFamily: nasta, fontSize: 14 } : {}]}>{desc}</Text>
    </TouchableOpacity>
  );
}

const sharedStyles = StyleSheet.create({
  primaryButton: {
    width: '100%',
    paddingVertical: 15,
    borderRadius: 12,
    alignItems: 'center',
    marginTop: 18,
  },
  primaryButtonText: {
    color: '#fff',
    fontSize: 17,
    fontWeight: '800',
  },
  choice: {
    width: '100%',
    borderWidth: 1,
    borderRadius: 12,
    padding: 14,
    marginBottom: 10,
  },
  choiceTitle: {
    fontSize: 17,
    fontWeight: '900',
  },
  choiceDesc: {
    fontSize: 13,
    lineHeight: 20,
    marginTop: 4,
  },
});

const getStyles = (T: ThemeColors) => StyleSheet.create({
  container: {
    flex: 1,
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  top: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginBottom: 10,
  },
  logo: {
    width: 44,
    height: 44,
    borderRadius: 12,
  },
  brand: {
    color: T.text,
    fontSize: 22,
    fontWeight: '900',
  },
  brandSub: {
    color: T.accent,
    fontSize: 12,
    fontWeight: '800',
  },
  langResetBtn: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: T.border,
    backgroundColor: T.surface,
  },
  langResetText: {
    color: T.text,
    fontSize: 12,
    fontWeight: '800',
  },
  content: {
    flex: 1,
    marginTop: 10,
  },
  scrollContent: {
    paddingBottom: 30,
  },
  stepTitle: {
    color: T.text,
    fontSize: 26,
    fontWeight: '900',
    marginBottom: 16,
  },
  langContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 10,
  },
  langWelcomeTitle: {
    color: T.text,
    fontSize: 32,
    fontWeight: '900',
    marginBottom: 8,
    textAlign: 'center',
  },
  langWelcomeSubtitle: {
    color: T.muted,
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 40,
    textAlign: 'center',
  },
  langCard: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: T.surface,
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 16,
    padding: 20,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 2,
  },
  langCardEmoji: {
    fontSize: 36,
    marginRight: 16,
  },
  langCardTextContainer: {
    flex: 1,
  },
  langCardTitle: {
    color: T.text,
    fontSize: 20,
    fontWeight: '900',
    marginBottom: 4,
  },
  langCardSubtitle: {
    color: T.muted,
    fontSize: 13,
    lineHeight: 18,
  },
  privacySummaryCard: {
    backgroundColor: T.surface,
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 16,
    padding: 16,
    marginBottom: 20,
  },
  privacySummaryTitle: {
    color: T.text,
    fontSize: 17,
    fontWeight: '800',
    marginBottom: 12,
  },
  privacyBulletRow: {
    flexDirection: 'row',
    marginBottom: 10,
    gap: 10,
  },
  bulletIcon: {
    fontSize: 16,
    marginTop: 2,
  },
  privacyBulletText: {
    flex: 1,
    color: T.muted,
    fontSize: 13.5,
    lineHeight: 20,
  },
  sectionHeader: {
    color: T.text,
    fontSize: 16,
    fontWeight: '900',
    marginTop: 14,
    marginBottom: 8,
  },
  sectionDesc: {
    color: T.muted,
    fontSize: 13,
    lineHeight: 19,
    marginBottom: 12,
  },
  permissionActionBtn: {
    width: '100%',
    backgroundColor: T.surface,
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
    marginBottom: 8,
  },
  permissionActionText: {
    color: T.text,
    fontSize: 15,
    fontWeight: '800',
  },
  keyboardBtnRow: {
    flexDirection: 'row',
    gap: 10,
    marginBottom: 16,
  },
  kbdConfigBtn: {
    flex: 1,
    backgroundColor: T.surface,
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
  },
  kbdConfigBtnText: {
    color: T.text,
    fontSize: 13.5,
    fontWeight: '800',
  },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.75)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  modalCard: {
    width: '100%',
    maxHeight: '80%',
    backgroundColor: T.surface,
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 24,
    padding: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.3,
    shadowRadius: 20,
    elevation: 10,
  },
  modalTitle: {
    color: T.text,
    fontSize: 24,
    fontWeight: '900',
    textAlign: 'center',
    marginBottom: 6,
  },
  modalSubtitle: {
    color: T.muted,
    fontSize: 14,
    textAlign: 'center',
    marginBottom: 20,
  },
  modalScroll: {
    marginVertical: 10,
  },
  guideStepItem: {
    flexDirection: 'row',
    marginBottom: 18,
    gap: 14,
  },
  guideStepIcon: {
    fontSize: 28,
    marginTop: 2,
  },
  guideStepTextWrap: {
    flex: 1,
  },
  guideStepTitle: {
    color: T.text,
    fontSize: 16,
    fontWeight: '900',
    marginBottom: 4,
  },
  guideStepDesc: {
    color: T.muted,
    fontSize: 13,
    lineHeight: 18,
  },
});
