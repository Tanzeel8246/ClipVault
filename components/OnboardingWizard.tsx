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
    return (
      <View style={styles.langContainer}>
        <Text style={styles.langWelcomeTitle}>Welcome / خوش آمدید</Text>
        <Text style={styles.langWelcomeSubtitle}>Select App Language / ایپ کی زبان منتخب کریں</Text>
        
        <TouchableOpacity 
          style={styles.langCard} 
          onPress={() => selectLanguage('en')}
          activeOpacity={0.9}
        >
          <Text style={styles.langCardEmoji}>🇬🇧</Text>
          <View style={styles.langCardTextContainer}>
            <Text style={styles.langCardTitle}>English</Text>
            <Text style={styles.langCardSubtitle}>Standard interface language and typography</Text>
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
            <Text style={[styles.langCardSubtitle, { fontFamily: NASTALEEQ_FONT }]}>جمیل نوری نستعلیق فونٹ اور مقامی زبان کا انتخاب</Text>
          </View>
        </TouchableOpacity>
      </View>
    );
  };

  // Render Setup & Privacy & Permissions screen
  const renderSetupScreen = () => {
    const textNasta = lang === 'ur' ? { fontFamily: NASTALEEQ_FONT } : {};
    return (
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        <Text style={[styles.stepTitle, textNasta]}>
          {lang === 'en' ? '🔒 Privacy & Setup' : '🔒 پرائیویسی اور سیٹ اپ'}
        </Text>

        {/* Privacy summary card */}
        <View style={styles.privacySummaryCard}>
          <Text style={[styles.privacySummaryTitle, textNasta]}>
            {lang === 'en' ? 'Privacy Policy Summary' : 'پرائیویسی پالیسی کا خلاصہ'}
          </Text>
          <View style={styles.privacyBulletRow}>
            <Text style={styles.bulletIcon}>🛡️</Text>
            <Text style={[styles.privacyBulletText, textNasta]}>
              {lang === 'en' 
                ? 'Your clipboard clips are stored 100% locally on this device. We never upload your data to any servers.'
                : 'آپ کے کلپ بورڈ کلپس 100٪ آپ کے اسی ڈیوائس پر محفوظ رہتے ہیں۔ ہم کبھی بھی آپ کا ڈیٹا کسی کلاؤڈ سرور پر اپلوڈ نہیں کرتے۔'}
            </Text>
          </View>
          <View style={styles.privacyBulletRow}>
            <Text style={styles.bulletIcon}>🔑</Text>
            <Text style={[styles.privacyBulletText, textNasta]}>
              {lang === 'en'
                ? 'Sensitive data like OTPs, passwords, and card numbers are automatically masked and kept secure.'
                : 'حساس ڈیٹا جیسے او ٹی پی (OTP)، پاس ورڈ اور کارڈ نمبرز خود بخود چھپ جاتے ہیں اور محفوظ رہتے ہیں۔'}
            </Text>
          </View>
        </View>

        {/* Permissions setup section */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {lang === 'en' ? '1. App Permissions' : '۱۔ ایپ کی اجازتیں'}
        </Text>
        <TouchableOpacity style={styles.permissionActionBtn} onPress={() => void requestNotificationPermission()}>
          <Text style={[styles.permissionActionText, textNasta]}>
            {lang === 'en' ? 'Post Notifications Permission' : 'نوٹیفکیشن کی اجازت دیں'}
          </Text>
        </TouchableOpacity>

        {/* Keyboard configuration section */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {lang === 'en' ? '2. Keyboard Setup (Required)' : '۲۔ کی بورڈ سیٹ اپ (لازمی)'}
        </Text>
        <Text style={[styles.sectionDesc, textNasta]}>
          {lang === 'en'
            ? 'Auto Clipboard capture requires the ClipVault custom keyboard. Please enable it in Settings first, then select it as active.'
            : 'کلپس کو خود کار طریقے سے محفوظ کرنے کے لیے والٹ بورڈ کی بورڈ کو فعال اور منتخب کرنا لازمی ہے۔ پہلے اسے ترتیبات میں فعال کریں، اور پھر اسے منتخب کریں۔'}
        </Text>
        
        <View style={styles.keyboardBtnRow}>
          <TouchableOpacity 
            style={[styles.kbdConfigBtn, kbdEnabled && { borderColor: T.green, backgroundColor: `${T.green}11` }]} 
            onPress={() => void openKeyboardSettings()}
          >
            <Text style={[styles.kbdConfigBtnText, textNasta, kbdEnabled && { color: T.green }]}>
              {kbdEnabled
                ? (lang === 'en' ? '✅ Keyboard Enabled' : '✅ کی بورڈ فعال ہو گیا')
                : (lang === 'en' ? '⚙️ Enable VaultBoard' : '⚙️ کی بورڈ فعال کریں')}
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
              {kbdSelected
                ? (lang === 'en' ? '✅ Keyboard Active' : '✅ کی بورڈ منتخب ہے')
                : (lang === 'en' ? '🔄 Select VaultBoard' : '🔄 کی بورڈ منتخب کریں')}
            </Text>
          </TouchableOpacity>
        </View>

        {/* Google Drive Backup (Optional) */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {lang === 'en' ? '3. Cloud Backup (Optional)' : '۳۔ کلاؤڈ بیک اپ (اختیاری)'}
        </Text>
        <Text style={[styles.sectionDesc, textNasta]}>
          {lang === 'en'
            ? 'Securely back up your clipboard data to your personal Google Drive hidden app folder. Useful for restoring data on new devices.'
            : 'اپنے کلپ بورڈ ڈیٹا کو اپنے گوگل ڈرائیو پر محفوظ کریں۔ یہ ڈیٹا ایپ کے مخصوص خفیہ فولڈر میں محفوظ ہوتا ہے جسے کوئی دوسرا نہیں پڑھ سکتا۔ نئے موبائل پر منتقلی کے لیے یہ انتہائی کارآمد ہے۔'}
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
                {lang === 'en' ? '✓ Google Drive Connected' : '✓ گوگل ڈرائیو کنیکٹ ہو گئی'}
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
                {lang === 'en' ? 'Disconnect' : 'منقطع کریں'}
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
                {lang === 'en' ? '🔗 Connect Google Drive' : '🔗 گوگل ڈرائیو کنیکٹ کریں'}
              </Text>
            )}
          </TouchableOpacity>
        )}

        {/* Security Lock option */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {lang === 'en' ? '4. Security Lock (Optional)' : '۴۔ سیکیورٹی لاک (اختیاری)'}
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
              {lang === 'en' ? '🔐 App Lock' : '🔐 ایپ لاک'}
            </Text>
            <Text style={{ color: T.muted, fontSize: 12, marginTop: 4 }}>
              {lang === 'en'
                ? 'Require phone fingerprint, PIN, pattern, or passcode on launch.'
                : 'ایپ کھولنے پر فون فنگر پرنٹ، پن، پیٹرن، یا پاس کوڈ کا مطالبہ کریں۔'}
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
                        lang === 'en' ? 'No Device Lock Set' : 'ڈیوائس لاک سیٹ نہیں ہے',
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
              {appLockEnabled 
                ? (lang === 'en' ? 'Enabled' : 'فعال ہے') 
                : (lang === 'en' ? 'Enable' : 'فعال کریں')}
            </Text>
          </TouchableOpacity>
        </View>

        {/* Privacy Mode choice */}
        <Text style={[styles.sectionHeader, textNasta]}>
          {lang === 'en' ? '5. Select Privacy Mode' : '۵۔ پرائیویسی موڈ کا انتخاب'}
        </Text>
        <PrivacyChoice
          active={privacyMode === 'standard'}
          title={lang === 'en' ? 'Standard Privacy' : 'معمول کی پرائیویسی'}
          desc={lang === 'en' ? 'Mask sensitive clips but keep them until you manually delete them.' : 'حساس کلپس ماسک رہیں گی اور دستی طور پر حذف کرنے تک محفوظ رہیں گی۔'}
          onPress={() => setPrivacyMode('standard')}
          T={T}
          nasta={nasta}
        />
        <PrivacyChoice
          active={privacyMode === 'strict'}
          title={lang === 'en' ? 'Strict Privacy' : 'سخت پرائیویسی'}
          desc={lang === 'en' ? 'Mask OTP/passwords and automatically delete sensitive clips after 5 minutes.' : 'او ٹی پی اور پاس ورڈز خود کار طریقے سے چھپیں گے اور ۵ منٹ بعد خود بخود مٹ جائیں گے۔'}
          onPress={() => setPrivacyMode('strict')}
          T={T}
          nasta={nasta}
        />

        <PrimaryButton 
          label={lang === 'en' ? 'Finish Setup & Start' : 'سیٹ اپ مکمل کریں اور شروع کریں'} 
          onPress={() => void finishSetup()} 
          T={T} 
        />
      </ScrollView>
    );
  };

  // Render app introduction and guide inside a popup Modal overlay
  const renderIntroModal = () => {
    const textNasta = lang === 'ur' ? { fontFamily: NASTALEEQ_FONT } : {};
    return (
      <Modal visible={isIntroVisible} transparent animationType="fade">
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={[styles.modalTitle, textNasta]}>
              {lang === 'en' ? 'Welcome to ClipVault! 🎉' : 'ClipVault میں خوش آمدید! 🎉'}
            </Text>
            <Text style={[styles.modalSubtitle, textNasta]}>
              {lang === 'en' ? 'Your local secure clipboard manager' : 'آپ کا مقامی اور محفوظ کلپ بورڈ مینیجر'}
            </Text>

            <ScrollView style={styles.modalScroll} showsVerticalScrollIndicator={false}>
              <View style={styles.guideStepItem}>
                <Text style={styles.guideStepIcon}>📋</Text>
                <View style={styles.guideStepTextWrap}>
                  <Text style={[styles.guideStepTitle, textNasta]}>
                    {lang === 'en' ? 'Auto Copy Capture' : 'خودکار کاپی کیپچر'}
                  </Text>
                  <Text style={[styles.guideStepDesc, textNasta]}>
                    {lang === 'en' 
                      ? 'Any text, links, emails, and phone numbers you copy are saved locally into searchable categories.'
                      : 'متن، لنکس، ای میلز اور فون نمبرز جو بھی آپ کاپی کریں گے، وہ زمرہ جات کے مطابق خود بخود محفوظ ہو جائیں گے۔'}
                  </Text>
                </View>
              </View>

              <View style={styles.guideStepItem}>
                <Text style={styles.guideStepIcon}>📁</Text>
                <View style={styles.guideStepTextWrap}>
                  <Text style={[styles.guideStepTitle, textNasta]}>
                    {lang === 'en' ? 'Bilingual Search & Pins' : 'تلاش اور پسندیدہ کلپس'}
                  </Text>
                  <Text style={[styles.guideStepDesc, textNasta]}>
                    {lang === 'en'
                      ? 'Easily filter and find saved replies, and pin important templates to access them quickly.'
                      : 'محفوظ کردہ کلپس کو آسانی سے تلاش کریں، اور اہم کلپس کو پن کریں تاکہ وہ سرفہرست رہیں۔'}
                  </Text>
                </View>
              </View>

              <View style={styles.guideStepItem}>
                <Text style={styles.guideStepIcon}>⌨️</Text>
                <View style={styles.guideStepTextWrap}>
                  <Text style={[styles.guideStepTitle, textNasta]}>
                    {lang === 'en' ? 'VaultBoard Keyboard' : 'والٹ بورڈ کی بورڈ'}
                  </Text>
                  <Text style={[styles.guideStepDesc, textNasta]}>
                    {lang === 'en'
                      ? 'Use the VaultBoard custom keyboard to copy/paste clips directly inside any chat application.'
                      : 'کسی بھی چیٹ ایپ میں کلپس تک براہِ راست رسائی اور پیسٹ کرنے کے لیے والٹ بورڈ کی بورڈ استعمال کریں۔'}
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
                {lang === 'en' ? 'Understood / Let\'s Setup' : 'سمجھ گیا / سیٹ اپ شروع کریں'}
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    );
  };



  return (
    <SafeAreaView style={[styles.container, { backgroundColor: T.bg }]}>
      <View style={styles.top}>
        <Image source={require('../assets/splash-icon.png')} style={styles.logo} />
        <View style={{ flex: 1 }}>
          <Text style={styles.brand}>ClipVault</Text>
          <Text style={styles.brandSub}>by MindCrafts_AI</Text>
        </View>
        {step > 0 && (
          <TouchableOpacity style={styles.langResetBtn} onPress={() => setStep(0)}>
            <Text style={[styles.langResetText, nasta ? { fontFamily: nasta } : {}]}>
              {lang === 'en' ? '🌐 Language' : '🌐 زبان'}
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

function PrimaryButton({ label, onPress, T }: { label: string; onPress: () => void; T: ThemeColors }) {
  return (
    <TouchableOpacity style={[sharedStyles.primaryButton, { backgroundColor: T.accent }]} onPress={onPress} activeOpacity={0.85}>
      <Text style={sharedStyles.primaryButtonText}>{label}</Text>
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
