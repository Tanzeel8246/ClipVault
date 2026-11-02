/**
 * SettingsScreen.tsx — ClipVault Premium Settings
 *
 * بہتریاں:
 * - Zustand گلوبل اسٹیٹ
 * - Privacy Dashboard (حساس کلپس کی تعداد)
 * - Panic Button — تمام حساس کلپس فوری حذف
 * - Sensitive Masking ٹوگل
 * - Sensitive Expiry آپشن
 * - بہتر UI/UX سیکشنز
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system';
import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Animated,
  Image,
  Linking,
  PermissionsAndroid,
  Platform,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
  Modal,
  TextInput,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  openKeyboardSettings,
  showKeyboardPicker,
  startBackgroundMonitor,
  stopBackgroundMonitor,
} from '../services/BackgroundMonitor';
import { ClipboardMonitor } from '../services/ClipboardMonitor';
import { DatabaseService, Exporter, getErrorMessage } from '../services/CoreServices';
import { GoogleDriveBackupService } from '../services/GoogleDriveBackup';
import { NASTALEEQ_FONT, t } from '../services/i18n';
import { useAppTheme } from '../theme/useAppTheme';
import { ThemeColors } from '../theme/colors';
import { AppLanguage, AutoMonitorMinutes, ClipSettings, ClipStats, DEFAULT_SETTINGS, SensitiveExpiry, SETTINGS_KEY } from '../services/types';
import { useClipStore } from '../store/useClipStore';
import { useLocalSearchParams } from 'expo-router';
import { PinManagerModal } from '../components/PinManagerModal';
import { SyncPasswordModal } from '../components/SyncPasswordModal';

// ─────────────────────────────────────────────────────────────
// Design Tokens
// ─────────────────────────────────────────────────────────────

const WHATSAPP_URL = 'https://wa.me/923023448246';
const CODEHUBB_URL = 'https://codehubb.com';
const MINDCRAFTS_URL = 'https://mindcraftsai.com';
const PRIVACY_POLICY_URL = 'https://mindcraftsai.com/privacy-policy';

async function openExternal(url: string) {
  try {
    await Linking.openURL(url);
  } catch {
    Alert.alert('لنک نہیں کھل سکا', url);
  }
}

// ─────────────────────────────────────────────────────────────
// Privacy Dashboard Card
// ─────────────────────────────────────────────────────────────
function PrivacyDashboard({ stats, onClearSensitive, lang, T }: { stats: any; onClearSensitive: () => void; lang: AppLanguage; T: ThemeColors }) {
  const privacyStyles = React.useMemo(() => getPrivacyStyles(T, lang), [T, lang]);
  const panicAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    if (stats.sensitive > 0) {
      const anim = Animated.loop(
        Animated.sequence([
          Animated.timing(panicAnim, { toValue: 1.04, duration: 900, useNativeDriver: true }),
          Animated.timing(panicAnim, { toValue: 1, duration: 900, useNativeDriver: true }),
        ]),
      );
      anim.start();
      return () => anim.stop();
    }
  }, [stats.sensitive, panicAnim]);

  return (
    <View style={privacyStyles.card}>
      <View style={privacyStyles.header}>
        <Text style={privacyStyles.title}>{t('privacySection', lang)}</Text>
        <View style={privacyStyles.badge}>
          <Text style={privacyStyles.badgeText}>{t('privacySafe', lang)}</Text>
        </View>
      </View>

      <View style={privacyStyles.grid}>
        <View style={privacyStyles.statBox}>
          <Text style={privacyStyles.statNum}>{stats.total}</Text>
          <Text style={privacyStyles.statLabel}>{t('totalClips', lang)}</Text>
        </View>
        <View style={privacyStyles.statBox}>
          <Text style={privacyStyles.statNum}>{stats.today}</Text>
          <Text style={privacyStyles.statLabel}>{t('todayClips', lang)}</Text>
        </View>
        <View style={privacyStyles.statBox}>
          <Text style={privacyStyles.statNum}>{stats.favorites}</Text>
          <Text style={privacyStyles.statLabel}>{t('favourites', lang)}</Text>
        </View>
        <View style={[privacyStyles.statBox, stats.sensitive > 0 && privacyStyles.statBoxAlert]}>
          <Text style={[privacyStyles.statNum, stats.sensitive > 0 && { color: T.red }]}>
            {stats.sensitive}
          </Text>
          <Text style={privacyStyles.statLabel}>{t('sensitiveClips', lang)}</Text>
        </View>
      </View>

      {stats.sensitive > 0 && (
        <Animated.View style={{ transform: [{ scale: panicAnim }] }}>
          <TouchableOpacity style={privacyStyles.panicBtn} onPress={onClearSensitive} activeOpacity={0.8}>
            <Text style={privacyStyles.panicIcon}>🚨</Text>
            <View>
              <Text style={privacyStyles.panicTitle}>{t('panicTitle', lang)}</Text>
              <Text style={privacyStyles.panicSub}>{stats.sensitive} {t('panicSub', lang)}</Text>
            </View>
          </TouchableOpacity>
        </Animated.View>
      )}

      <Text style={privacyStyles.note}>{t('privacyNote', lang)}</Text>
    </View>
  );
}

const getPrivacyStyles = (T: ThemeColors, lang: AppLanguage) => {
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;
  const ff = nasta ? { fontFamily: nasta } : {};
  return StyleSheet.create({
    card: {
      margin: 12,
      backgroundColor: T.surface,
      borderRadius: 14,
      padding: 16,
      borderWidth: 1,
      borderColor: T.border,
    },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 },
    title: { color: T.text, fontSize: 15, fontWeight: '800', ...ff },
    badge: { backgroundColor: `${T.green}22`, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 3 },
    badgeText: { color: T.green, fontSize: 11, fontWeight: '700', ...ff },
    grid: { flexDirection: 'row', gap: 8, marginBottom: 14 },
    statBox: {
      flex: 1,
      backgroundColor: T.card,
      borderRadius: 10,
      padding: 12,
      alignItems: 'center',
      borderWidth: 1,
      borderColor: T.border,
    },
    statBoxAlert: { borderColor: `${T.red}55`, backgroundColor: `${T.red}11` },
    statNum: { fontSize: 22, fontWeight: '800', color: T.accent },
    statLabel: { fontSize: 11, color: T.muted, marginTop: 2, textAlign: 'center', ...ff },
    panicBtn: {
      backgroundColor: `${T.red}15`,
      borderWidth: 1,
      borderColor: `${T.red}55`,
      borderRadius: 12,
      padding: 14,
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      marginBottom: 12,
    },
    panicIcon: { fontSize: 28 },
    panicTitle: { color: T.red, fontSize: 13, fontWeight: '800', ...ff },
    panicSub: { color: `${T.red}bb`, fontSize: 11, marginTop: 2, ...ff },
    note: { color: T.muted, fontSize: 12, lineHeight: 18, ...ff },
  });
};

// ─────────────────────────────────────────────────────────────
// Reusable Section / Row Components
// ─────────────────────────────────────────────────────────────
function SettingsSection({ title, icon, children, T, lang }: { title: string; icon: string; children: React.ReactNode; T: ThemeColors; lang: AppLanguage }) {
  const styles = React.useMemo(() => getStyles(T, lang, 0), [T, lang]);
  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionIcon}>{icon}</Text>
        <Text style={styles.sectionTitle}>{title}</Text>
      </View>
      <View style={styles.sectionCard}>{children}</View>
    </View>
  );
}

function SettingRow({
  label,
  description,
  value,
  onToggle,
  danger,
  T,
  lang,
}: {
  label: string;
  description?: string;
  value: boolean;
  onToggle: (value: boolean) => void;
  danger?: boolean;
  T: ThemeColors;
  lang: AppLanguage;
}) {
  const styles = React.useMemo(() => getStyles(T, lang, 0), [T, lang]);
  return (
    <View style={styles.settingRow}>
      <View style={styles.settingText}>
        <Text style={[styles.settingLabel, danger && { color: T.red }]}>{label}</Text>
        {description ? <Text style={styles.settingDesc}>{description}</Text> : null}
      </View>
      <Switch
        value={value}
        onValueChange={onToggle}
        trackColor={{ false: '#1e1e38', true: `${T.accent}88` }}
        thumbColor={value ? T.accent : '#555'}
      />
    </View>
  );
}

function OptionGroup<OptionType extends string>({
  label,
  options,
  value,
  onChange,
  T,
  lang,
}: {
  label: string;
  options: Array<{ label: string; value: OptionType; desc?: string }>;
  value: OptionType;
  onChange: (v: OptionType) => void;
  T: ThemeColors;
  lang: AppLanguage;
}) {
  const styles = React.useMemo(() => getStyles(T, lang, 0), [T, lang]);
  return (
    <View>
      <Text style={styles.subLabel}>{label}</Text>
      {options.map(opt => (
        <TouchableOpacity
          key={opt.value}
          style={[styles.optionRow, value === opt.value && styles.optionActive]}
          onPress={() => onChange(opt.value)}
        >
          <View style={{ flex: 1 }}>
            <Text style={[styles.optionText, value === opt.value && styles.optionTextActive]}>
              {opt.label}
            </Text>
            {opt.desc && <Text style={styles.optionDesc}>{opt.desc}</Text>}
          </View>
          {value === opt.value && <Text style={{ color: T.accent, fontSize: 16 }}>✓</Text>}
        </TouchableOpacity>
      ))}
    </View>
  );
}

type PinMode = 'setup' | 'confirm' | 'disable' | 'change_old' | 'change_new' | 'change_confirm';

// ─────────────────────────────────────────────────────────────
// Main SettingsScreen
// ─────────────────────────────────────────────────────────────
export default function SettingsScreen() {
  const insets = useSafeAreaInsets();
  const { stats, settings, loadSettings, updateSetting, loadStats, showToast, clearSensitive } = useClipStore();
  const lang: AppLanguage = (settings.appLanguage as AppLanguage) ?? 'ur';
  const T = useAppTheme();
  const styles = React.useMemo(() => getStyles(T, lang, insets.top), [T, lang, insets.top]);
  const [hasBiometric, setHasBiometric] = useState(false);
  const [localApiKey, setLocalApiKey] = useState(settings.geminiApiKey || '');
  const [showApiKey, setShowApiKey] = useState(false);
  const [isApiKeyModified, setIsApiKeyModified] = useState(false);
  const [monitorBusy, setMonitorBusy] = useState(false);
  const [driveBusy, setDriveBusy] = useState(false);
  const [driveAccount, setDriveAccount] = useState('');
  
  const [pinModalVisible, setPinModalVisible] = useState(false);
  const [pinMode, setPinMode] = useState<PinMode>('setup');
  const [syncPasswordPromptVisible, setSyncPasswordPromptVisible] = useState(false);
  const [syncPasswordMode, setSyncPasswordMode] = useState<'setup' | 'disable' | 'change'>('setup');

  const apiKeyInputRef = useRef<TextInput>(null);
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;

  const params = useLocalSearchParams();

  useEffect(() => {
    if (!isApiKeyModified) {
      setLocalApiKey(settings.geminiApiKey || '');
    }
  }, [settings.geminiApiKey, isApiKeyModified]);

  useEffect(() => {
    const bootstrap = async () => {
      await DatabaseService.init();
      await loadSettings();
      await loadStats();
      setDriveAccount(await GoogleDriveBackupService.getAccountEmail());

      const hasHardware = await LocalAuthentication.hasHardwareAsync();
      const isEnrolled = await LocalAuthentication.isEnrolledAsync();
      setHasBiometric(hasHardware && isEnrolled);

      if (params.focusApiKey === 'true') {
        setTimeout(() => {
          apiKeyInputRef.current?.focus();
        }, 300);
      }
    };

    void bootstrap();
  }, [loadSettings, loadStats, params.focusApiKey]);

  // ── Biometric toggle ──────────────────────────────────────
  const toggleBiometric = async (value: boolean) => {
    if (!value) {
      await updateSetting('biometricLock', false);
      return;
    }

    const result = await LocalAuthentication.authenticateAsync({
      promptMessage: t('authEnableLock', lang),
      cancelLabel: t('cancel', lang),
      disableDeviceFallback: false,
    });

    if (result.success) {
      await updateSetting('biometricLock', true);
      showToast(lang === 'en' ? '🔒 Biometric lock enabled' : '🔒 بایومیٹرک لاک فعال ہو گئی');
    }
  };

  const handlePinToggle = () => {
    if (settings.pinLockEnabled) {
      setPinMode('disable');
    } else {
      setPinMode('setup');
    }
    setPinModalVisible(true);
  };

  const handlePinSuccess = async (newHash: string | null) => {
    setPinModalVisible(false);
    if (newHash === null) {
      await updateSetting('pinHash', '');
      await updateSetting('pinLockEnabled', false);
      showToast(lang === 'en' ? '🔓 PIN lock disabled' : '🔓 پن لاک غیر فعال ہو گیا');
    } else {
      await updateSetting('pinHash', newHash);
      await updateSetting('pinLockEnabled', true);
      showToast(lang === 'en' ? '🔒 PIN lock enabled' : '🔒 پن لاک فعال ہو گیا');
    }
  };

  // ── Data actions ──────────────────────────────────────────
  const clearAllData = () => {
    Alert.alert(lang === 'en' ? 'Delete All Clips' : 'تمام کلپس حذف کریں', lang === 'en' ? 'Are you sure you want to erase all clipboard history?' : 'کیا آپ واقعی تمام کلپ بورڈ ہسٹری مٹانا چاہتے ہیں؟', [
      { text: t('cancel', lang), style: 'cancel' },
      {
        text: t('delete', lang),
        style: 'destructive',
        onPress: async () => {
          try {
            await DatabaseService.clearAll();
            await loadStats();
            showToast(lang === 'en' ? '🗑️ All clips deleted' : '🗑️ تمام کلپس حذف ہو گئیں');
          } catch (err) {
            Alert.alert(lang === 'en' ? 'Failed' : 'ناکام', getErrorMessage(err));
          }
        },
      },
    ]);
  };

  const handleClearSensitive = () => {
    Alert.alert(
      t('panicTitle', lang),
      `${stats.sensitive} ${lang === 'en' ? 'sensitive clips (OTP, Passwords, Card numbers) will be securely deleted. This action cannot be undone.' : 'حساس کلپس (OTP، پاس ورڈ، کارڈ نمبر) فوری حذف ہو جائیں گی۔ یہ عمل واپس نہیں ہوگا۔'}`,
      [
        { text: t('cancel', lang), style: 'cancel' },
        {
          text: t('panicBtn', lang),
          style: 'destructive',
          onPress: async () => {
            const count = await clearSensitive();
            showToast(lang === 'en' ? `🛡️ ${count} sensitive clips securely deleted` : `🛡️ ${count} حساس کلپس محفوظ طریقے سے حذف ہوئیں`);
          },
        },
      ],
    );
  };

  const backupToDrive = async () => {
    try {
      await Exporter.exportBackupJSON();
      showToast(lang === 'en' ? '✅ Backup ready' : '✅ بیک اپ تیار ہو گیا');
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Backup Failed' : 'بیک اپ ناکام', getErrorMessage(err));
    }
  };

  const restoreBackup = async () => {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ['application/json', 'text/*', '*/*'],
        copyToCacheDirectory: true,
      });

      if (result.canceled || !result.assets?.[0]?.uri) return;

      const content = await FileSystem.readAsStringAsync(result.assets[0].uri);
      const restored = await DatabaseService.restoreFromBackupText(content);
      await loadStats();
      showToast(lang === 'en' ? `✅ ${restored} clips restored` : `✅ ${restored} کلپس بحال ہو گئیں`);
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Restore Failed' : 'بحالی ناکام', getErrorMessage(err));
    }
  };

  const connectGoogleDrive = async () => {
    try {
      if (!GoogleDriveBackupService.isConfigured()) {
        Alert.alert(t('driveNotConfigured', lang), lang === 'en'
          ? 'Add your Google OAuth client ID in services/GoogleDriveBackup.ts before publishing.'
          : 'Publish سے پہلے services/GoogleDriveBackup.ts میں Google OAuth client ID شامل کریں۔');
        return;
      }
      setDriveBusy(true);
      await GoogleDriveBackupService.startSignIn();
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Google Sign-in Failed' : 'Google سائن اِن ناکام', getErrorMessage(err));
    } finally {
      setDriveBusy(false);
    }
  };

  const disconnectGoogleDrive = () => {
    Alert.alert(
      lang === 'en' ? 'Disconnect Google Drive?' : 'Google Drive منقطع کریں؟',
      lang === 'en'
        ? 'Local clips will stay on this phone. Automatic Drive backup will stop.'
        : 'مقامی کلپس اسی فون پر رہیں گی۔ Drive آٹو بیک اپ بند ہو جائے گا۔',
      [
        { text: t('cancel', lang), style: 'cancel' },
        {
          text: lang === 'en' ? 'Disconnect' : 'منقطع کریں',
          style: 'destructive',
          onPress: async () => {
            await GoogleDriveBackupService.signOut();
            await loadSettings();
            setDriveAccount('');
            showToast(lang === 'en' ? 'Google Drive disconnected' : 'Google Drive منقطع ہو گئی');
          },
        },
      ],
    );
  };

  const backupToGoogleDriveNow = async () => {
    try {
      setDriveBusy(true);
      const res = await GoogleDriveBackupService.backupNow();
      await loadSettings();
      showToast(lang === 'en' ? `✅ Google Drive backup saved (${res.clipCount} clips)` : `✅ Google Drive بیک اپ محفوظ ہو گیا (${res.clipCount} کلپس)`);
      setDriveAccount(settings.googleDriveAccountEmail || driveAccount);
    } catch (err: any) {
      const msg = getErrorMessage(err);
      if (msg.includes('EMPTY_LOCAL_BACKUP_BLOCKED:')) {
        const count = msg.split('EMPTY_LOCAL_BACKUP_BLOCKED:')[1] || '0';
        Alert.alert(
          t('driveBackupProtected', lang),
          (lang === 'en' ? t('driveBackupProtectedMsg', lang) : t('driveBackupProtectedMsg', lang)).replace('%d', count),
          [
            { text: t('cancel', lang), style: 'cancel' },
            {
              text: t('driveRestoreNow', lang),
              onPress: () => restoreFromGoogleDriveNow(),
            },
          ],
        );
        return;
      }
      Alert.alert(lang === 'en' ? 'Drive Backup Failed' : 'Drive بیک اپ ناکام', msg);
    } finally {
      setDriveBusy(false);
    }
  };

  const restoreFromGoogleDriveNow = () => {
    Alert.alert(
      t('driveRestoreNow', lang),
      lang === 'en'
        ? 'This will merge the latest Drive backup into this phone. Existing clips are kept.'
        : 'یہ Drive کے تازہ بیک اپ کو اس فون میں merge کرے گا۔ موجودہ کلپس برقرار رہیں گی۔',
      [
        { text: t('cancel', lang), style: 'cancel' },
        {
          text: t('restore', lang),
          onPress: async () => {
            try {
              setDriveBusy(true);
              const restored = await GoogleDriveBackupService.restoreLatestBackup();
              await loadSettings();
              await loadStats();
              if (restored > 0) {
                showToast(lang === 'en' ? `✅ ${restored} clips restored from Drive` : `✅ Drive سے ${restored} کلپس بحال ہو گئیں`);
              } else {
                Alert.alert(
                  lang === 'en' ? 'No Clips Found in Latest Backup' : 'تازہ بیک اپ میں کوئی کلپس نہیں ملیں',
                  lang === 'en'
                    ? 'The latest backup file has 0 clips. Would you like to scan Google Drive history for older versions?'
                    : 'تازہ بیک اپ فائل خالی ہے۔ کیا آپ پچھلے ورژنز اور ہسٹری اسکین کر کے ڈیٹا تلاش کرنا چاہتے ہیں؟',
                  [
                    { text: t('cancel', lang), style: 'cancel' },
                    {
                      text: lang === 'en' ? 'Scan History' : 'ہسٹری اسکین کریں',
                      onPress: () => recoverFromDriveHistoryNow(),
                    },
                  ],
                );
              }
            } catch (err) {
              Alert.alert(lang === 'en' ? 'Drive Restore Failed' : 'Drive بحالی ناکام', getErrorMessage(err));
            } finally {
              setDriveBusy(false);
            }
          },
        },
      ],
    );
  };

  const recoverFromDriveHistoryNow = () => {
    Alert.alert(
      t('driveRecoverHistory', lang),
      lang === 'en'
        ? 'Scan past Google Drive revisions and safety copies to recover clips from previous backups?'
        : 'کیا آپ گوگل ڈرائیو کی ہسٹری اور پرانے ورژنز کو اسکین کر کے سابقہ کلپس تلاش اور بحال کرنا چاہتے ہیں؟',
      [
        { text: t('cancel', lang), style: 'cancel' },
        {
          text: lang === 'en' ? 'Scan & Recover' : 'اسکین اور بحال کریں',
          onPress: async () => {
            try {
              setDriveBusy(true);
              const { restored, totalVersionsFound } = await GoogleDriveBackupService.recoverFromHistory();
              await loadSettings();
              await loadStats();
              if (restored > 0) {
                Alert.alert(
                  lang === 'en' ? 'Recovery Successful' : 'کامیاب بحالی',
                  lang === 'en'
                    ? `Successfully recovered ${restored} clips from ${totalVersionsFound} past versions!`
                    : `گوگل ڈرائیو کے ${totalVersionsFound} پرانے ورژنز سے ${restored} کلپس کامیابی سے واپس آ گئیں!`,
                );
              } else {
                Alert.alert(
                  lang === 'en' ? 'No Clips Found' : 'کوئی کلپس نہیں ملیں',
                  t('driveHistoryNone', lang),
                );
              }
            } catch (err) {
              Alert.alert(lang === 'en' ? 'Recovery Failed' : 'بحالی ناکام', getErrorMessage(err));
            } finally {
              setDriveBusy(false);
            }
          },
        },
      ],
    );
  };

  // ── Monitor actions ───────────────────────────────────────
  const startAutoMonitorNow = async () => {
    setMonitorBusy(true);
    try {
      await ClipboardMonitor.requestPermissions(true);
      await startBackgroundMonitor(settings.autoMonitorMinutes);
      const label = settings.autoMonitorMinutes === 0
        ? t('unlimited', lang)
        : `${settings.autoMonitorMinutes} منٹ`;
      showToast(lang === 'en' ? `▶️ Monitor started — ${label}` : `▶️ مانیٹر شروع — ${label}`);
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Failed' : 'ناکام', getErrorMessage(err));
    } finally {
      setMonitorBusy(false);
    }
  };

  const stopAutoMonitorNow = async () => {
    setMonitorBusy(true);
    try {
      await stopBackgroundMonitor();
      showToast(lang === 'en' ? '⏹️ Monitor stopped' : '⏹️ مانیٹر بند ہو گئی');
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Failed' : 'ناکام', getErrorMessage(err));
    } finally {
      setMonitorBusy(false);
    }
  };

  const requestVoicePermission = async () => {
    if (Platform.OS !== 'android') return;
    try {
      const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO, {
        title: t('voicePermission', lang),
        message: lang === 'en' ? 'ClipVault Keyboard uses the microphone only when the Mic button is pressed.' : 'ClipVault Keyboard صرف Mic بٹن دبانے پر مائیکروفون استعمال کرتا ہے۔',
        buttonPositive: lang === 'en' ? 'Allow' : 'اجازت دیں',
        buttonNegative: lang === 'en' ? 'Deny' : 'نہیں',
      });
      if (result === PermissionsAndroid.RESULTS.GRANTED) {
        showToast(lang === 'en' ? '🎤 Voice typing enabled' : '🎤 وائس ٹائپنگ فعال ہو گئی');
      }
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Failed' : 'ناکام', getErrorMessage(err));
    }
  };

  // ─────────────────────────────────────────────────────────
  // Render
  // ─────────────────────────────────────────────────────────
  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>

      {/* Header: page title left + lang toggle right */}
      <View style={styles.headerRow}>
        <Text style={[styles.pageTitle, nasta ? { fontFamily: nasta } : {}]}>{t('settingsTitle', lang)}</Text>
        <View style={styles.langPill}>
          <TouchableOpacity
            style={[styles.langPillBtn, lang === 'en' && styles.langPillBtnActive]}
            onPress={() => void updateSetting('appLanguage', 'en')}
          >
            <Text style={[styles.langPillText, lang === 'en' && styles.langPillTextActive]}>EN</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.langPillBtn, lang === 'ur' && styles.langPillBtnActive]}
            onPress={() => void updateSetting('appLanguage', 'ur')}
          >
            <Text style={[styles.langPillText, lang === 'ur' && styles.langPillTextActive]}>اردو</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Privacy Dashboard */}
      <PrivacyDashboard stats={stats} onClearSensitive={handleClearSensitive} lang={lang} T={T} />

      {/* Security */}
      <SettingsSection title={lang === 'en' ? 'Security' : 'سیکیورٹی'} icon="🔐" T={T} lang={lang}>
        {hasBiometric && (
          <SettingRow
            T={T} lang={lang}
            label={lang === 'en' ? 'Biometric Lock' : 'بایومیٹرک لاک'}
            description={lang === 'en' ? 'Require phone fingerprint or face to unlock app' : 'ایپ کھولنے پر فون فنگر پرنٹ یا چہرے کی تصدیق کا مطالبہ کریں'}
            value={settings.biometricLock}
            onToggle={value => void toggleBiometric(value)}
          />
        )}
        <SettingRow
          T={T} lang={lang}
          label={lang === 'en' ? 'App PIN Lock' : 'ایپ پن لاک'}
          description={lang === 'en' ? 'Require a 4-digit PIN to access sensitive clips' : 'حساس کلپس تک رسائی کے لیے 4 ہندسوں کا پن درکار کریں'}
          value={settings.pinLockEnabled}
          onToggle={handlePinToggle}
        />
        {settings.pinLockEnabled && (
          <TouchableOpacity 
            style={{ paddingVertical: 12, paddingHorizontal: 4, flexDirection: 'row', alignItems: 'center' }}
            onPress={() => { setPinMode('change_old'); setPinModalVisible(true); }}
          >
            <Text style={{ fontSize: 18, marginRight: 12 }}>🔑</Text>
            <Text style={{ flex: 1, color: T.text, fontSize: 16, fontFamily: nasta }}>{lang === 'en' ? 'Change PIN' : 'پن تبدیل کریں'}</Text>
            <Text style={{ color: T.accent, fontSize: 16 }}>›</Text>
          </TouchableOpacity>
        )}
      </SettingsSection>

      {/* Sensitive Content */}
      <SettingsSection title={lang === 'en' ? 'Privacy Controls' : 'پرائیویسی کنٹرولز'} icon="🛡️" T={T} lang={lang}>
        <SettingRow
          T={T} lang={lang}
          label={t('maskRow', lang)}
          description={t('maskDesc', lang)}
          value={settings.maskSensitive}
          onToggle={value => void updateSetting('maskSensitive', value)}
        />
        <OptionGroup<SensitiveExpiry>
          T={T} lang={lang}
          label={t('sensitiveExpiry', lang)}
          value={settings.sensitiveExpiry}
          onChange={v => void updateSetting('sensitiveExpiry', v)}
          options={[
            { label: t('expiryNever', lang), value: 'never', desc: t('expiryNeverDesc', lang) },
            { label: t('expiry30s', lang), value: '30s', desc: t('expiry30sDesc', lang) },
            { label: t('expiry5m', lang), value: '5m', desc: t('expiry5mDesc', lang) },
            { label: t('expirySession', lang), value: 'session', desc: t('expirySessionDesc', lang) },
          ]}
        />
      </SettingsSection>

      {/* Gemini AI Settings */}
      <SettingsSection title={lang === 'en' ? 'AI Summary' : 'اے آئی خلاصہ'} icon="✨" T={T} lang={lang}>
        <View style={styles.infoBox}>
          <Text style={styles.infoText}>
            {lang === 'en'
              ? 'Enter your Gemini API key to activate premium AI Summarization of long clips directly inside the app.'
              : 'ایپ کے اندر طویل کلپس کے پریمیم اے آئی خلاصہ کو فعال کرنے کے لیے اپنی جیمینائی API کی درج کریں۔'}
          </Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', marginTop: 8, alignItems: 'center' }}>
            <Text style={[styles.infoText, { color: T.muted }]}>
              {lang === 'en' ? 'If you do not have an API key, get a free API key ' : 'اگر آپ کے پاس API کی نہیں ہے تو، '}
            </Text>
            <TouchableOpacity onPress={() => void openExternal('https://aistudio.google.com/app/apikey')}>
              <Text style={{ color: T.accent, fontSize: 13, fontWeight: '700', textDecorationLine: 'underline', fontFamily: nasta }}>
                {lang === 'en' ? 'here' : 'یہاں سے'}
              </Text>
            </TouchableOpacity>
            {lang === 'ur' && (
              <Text style={styles.infoText}>
                {' حاصل کریں۔'}
              </Text>
            )}
          </View>
        </View>
        <View style={{ padding: 14 }}>
          <View style={{
            flexDirection: 'row',
            alignItems: 'center',
            backgroundColor: `${T.bg}88`,
            borderColor: T.border,
            borderWidth: 1,
            borderRadius: 10,
            paddingRight: 12,
          }}>
            <TextInput
              ref={apiKeyInputRef}
              style={{
                flex: 1,
                padding: 12,
                color: T.text,
                fontSize: 14,
                fontFamily: nasta,
              }}
              placeholder={lang === 'en' ? 'Paste Gemini API Key...' : 'جیمینائی API کی پیسٹ کریں...'}
              placeholderTextColor={T.muted}
              value={localApiKey}
              onChangeText={value => {
                setLocalApiKey(value);
                setIsApiKeyModified(true);
              }}
              secureTextEntry={!showApiKey}
              autoCapitalize="none"
              autoCorrect={false}
            />
            <TouchableOpacity onPress={() => setShowApiKey(!showApiKey)} style={{ padding: 4 }}>
              <Text style={{ color: T.accent, fontSize: 13, fontFamily: nasta, fontWeight: '700' }}>
                {showApiKey
                  ? (lang === 'en' ? 'Hide' : 'چھپائیں')
                  : (lang === 'en' ? 'Show' : 'دکھائیں')}
              </Text>
            </TouchableOpacity>
          </View>

          <TouchableOpacity
            style={{
              marginTop: 10,
              backgroundColor: T.accent,
              borderRadius: 10,
              padding: 12,
              alignItems: 'center',
              justifyContent: 'center',
            }}
            onPress={async () => {
              await updateSetting('geminiApiKey', localApiKey.trim());
              setIsApiKeyModified(false);
              showToast(lang === 'en' ? '✨ Gemini API Key saved!' : '✨ جیمینائی API کی محفوظ ہو گئی!');
            }}
          >
            <Text style={{ color: '#fff', fontSize: 14, fontWeight: '700', fontFamily: nasta }}>
              {lang === 'en' ? 'Save API Key' : 'API کی محفوظ کریں'}
            </Text>
          </TouchableOpacity>
        </View>
      </SettingsSection>

      {/* Monitoring */}
      <SettingsSection title={lang === 'en' ? 'Capture' : 'کیپچر'} icon="👁" T={T} lang={lang}>
        <SettingRow
          T={T} lang={lang}
          label={t('autoSaveRow', lang)}
          description={t('autoSaveDesc', lang)}
          value={settings.monitoringEnabled}
          onToggle={value => void updateSetting('monitoringEnabled', value)}
        />
        <SettingRow
          T={T} lang={lang}
          label={t('bgMonitorRow', lang)}
          description={t('bgMonitorDesc', lang)}
          value={settings.backgroundMonitorEnabled}
          onToggle={value => void updateSetting('backgroundMonitorEnabled', value)}
        />
        <OptionGroup<string>
          T={T} lang={lang}
          label={t('bgDurationLabel', lang)}
          value={String(settings.autoMonitorMinutes)}
          onChange={v => void updateSetting('autoMonitorMinutes', Number(v) as AutoMonitorMinutes)}
          options={[
            { label: t('unlimited', lang), value: '0', desc: t('unlimitedDesc', lang) },
            { label: t('min15', lang), value: '15' },
            { label: t('min30', lang), value: '30' },
            { label: t('min60', lang), value: '60' },
            { label: t('min120', lang), value: '120' },
          ]}
        />
        <View style={styles.monitorActions}>
          <TouchableOpacity
            disabled={monitorBusy}
            style={[styles.monitorBtn, monitorBusy && styles.btnDisabled]}
            onPress={() => void startAutoMonitorNow()}
          >
            <Text style={styles.monitorBtnText}>{t('startMonitor', lang)}</Text>
          </TouchableOpacity>
          <TouchableOpacity
            disabled={monitorBusy}
            style={[styles.monitorBtn, styles.monitorStopBtn, monitorBusy && styles.btnDisabled]}
            onPress={() => void stopAutoMonitorNow()}
          >
            <Text style={styles.monitorBtnText}>{t('stopMonitor', lang)}</Text>
          </TouchableOpacity>
        </View>
        <SettingRow
          T={T} lang={lang}
          label={t('notificationsRow', lang)}
          description={t('notificationsDesc', lang)}
          value={settings.showNotifications}
          onToggle={value => void updateSetting('showNotifications', value)}
        />
      </SettingsSection>

      {/* Theme Section */}
      <SettingsSection title={lang === 'en' ? 'Keyboard Theme' : 'کی بورڈ تھیم'} icon="🎨" T={T} lang={lang}>
        <OptionGroup<string>
          T={T} lang={lang}
          label={lang === 'en' ? 'Select Theme' : 'تھیم منتخب کریں'}
          value={settings.theme || 'CLASSIC_DARK'}
          onChange={v => void updateSetting('theme', v as any)}
          options={[
            { label: 'Classic Dark', value: 'CLASSIC_DARK', desc: lang === 'en' ? 'Default dark mode' : 'ڈیفالٹ ڈارک موڈ' },
            { label: 'Soft Ivory (Light)', value: 'SOFT_IVORY', desc: lang === 'en' ? 'Clean light mode' : 'خوبصورت لائٹ موڈ' },
            { label: 'Ocean Blue', value: 'OCEAN_BLUE', desc: lang === 'en' ? 'Deep sea blue' : 'گہرا نیلا موڈ' },
            { label: 'Forest Green', value: 'FOREST_GREEN', desc: lang === 'en' ? 'Emerald dark green' : 'سبز ایمرلڈ موڈ' },
            { label: 'Neon Glass', value: 'NEON_GLASS', desc: lang === 'en' ? 'Cyberpunk style' : 'سائبر پنک سٹائل' },
            { label: 'Midnight Gold', value: 'MIDNIGHT_GOLD', desc: lang === 'en' ? 'Premium gold accents' : 'پریمیم گولڈ موڈ' },
            { label: 'Graphite', value: 'GRAPHITE', desc: lang === 'en' ? 'Pure dark gray' : 'خالص گرے موڈ' },
            { label: 'Royal Purple', value: 'ROYAL_PURPLE', desc: lang === 'en' ? 'Rich velvet purple' : 'خوبصورت شاہی ارغوانی موڈ' },
            { label: 'Sunset Orange', value: 'SUNSET_ORANGE', desc: lang === 'en' ? 'Warm sunset orange' : 'خوبصورت نارنجی موڈ' },
            { label: 'Rose Petal', value: 'ROSE_PETAL', desc: lang === 'en' ? 'Warm rose pink' : 'خوبصورت گلابی موڈ' },
          ]}
        />
      </SettingsSection>

      {/* Language Section */}
      <SettingsSection title={lang === 'en' ? 'Language' : 'زبان'} icon="🌐" T={T} lang={lang}>
        <View style={styles.langToggleRow}>
          <TouchableOpacity
            style={[styles.langOption, lang === 'ur' && styles.langOptionActive]}
            onPress={() => void updateSetting('appLanguage', 'ur')}
          >
            <Text style={[styles.langOptionText, lang === 'ur' && styles.langOptionTextActive]}
              numberOfLines={1}>
              {t('langUrdu', lang)}
            </Text>
            <Text style={[styles.langOptionSub]}>{t('fontNastaleeq', lang)}</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.langOption, lang === 'en' && styles.langOptionActive]}
            onPress={() => void updateSetting('appLanguage', 'en')}
          >
            <Text style={[styles.langOptionText, lang === 'en' && styles.langOptionTextActive]}
              numberOfLines={1}>
              {t('langEnglish', lang)}
            </Text>
            <Text style={styles.langOptionSub}>{t('fontSystem', lang)}</Text>
          </TouchableOpacity>
        </View>
      </SettingsSection>

      {/* VaultBoard Keyboard */}
      <SettingsSection title={t("keyboardSection", lang)} icon="⎨️" T={T} lang={lang}>
        <View style={styles.infoBox}>
          <Text style={styles.infoText}>
            {lang === 'en' ? 'Keyboard Customization' : 'کی بورڈ کسٹمائزیشن'}
          </Text>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 }}>
            <Text style={{ color: T.text, fontSize: 13, fontFamily: nasta }}>{lang === 'en' ? 'Keyboard Height' : 'کی بورڈ کی اونچائی'}</Text>
            <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: T.surface, borderRadius: 8, borderWidth: 1, borderColor: T.border }}>
              <TouchableOpacity onPress={() => updateSetting('keyboardHeight', Math.max(0.85, settings.keyboardHeight - 0.05))} style={{ padding: 8, paddingHorizontal: 16 }}>
                <Text style={{ color: T.text, fontSize: 16 }}>-</Text>
              </TouchableOpacity>
              <Text style={{ color: T.accent, fontSize: 13, fontWeight: 'bold', width: 45, textAlign: 'center' }}>
                {Math.round(settings.keyboardHeight * 100)}%
              </Text>
              <TouchableOpacity onPress={() => updateSetting('keyboardHeight', Math.min(1.2, settings.keyboardHeight + 0.05))} style={{ padding: 8, paddingHorizontal: 16 }}>
                <Text style={{ color: T.text, fontSize: 16 }}>+</Text>
              </TouchableOpacity>
            </View>
          </View>
          
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 16 }}>
            <Text style={{ color: T.text, fontSize: 13, fontFamily: nasta }}>{lang === 'en' ? 'Key Font Size' : 'بٹنز کا فونٹ سائز'}</Text>
            <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: T.surface, borderRadius: 8, borderWidth: 1, borderColor: T.border }}>
              <TouchableOpacity onPress={() => updateSetting('keyboardFontSize', Math.max(0.85, settings.keyboardFontSize - 0.05))} style={{ padding: 8, paddingHorizontal: 16 }}>
                <Text style={{ color: T.text, fontSize: 16 }}>-</Text>
              </TouchableOpacity>
              <Text style={{ color: T.accent, fontSize: 13, fontWeight: 'bold', width: 45, textAlign: 'center' }}>
                {Math.round(settings.keyboardFontSize * 100)}%
              </Text>
              <TouchableOpacity onPress={() => updateSetting('keyboardFontSize', Math.min(1.2, settings.keyboardFontSize + 0.05))} style={{ padding: 8, paddingHorizontal: 16 }}>
                <Text style={{ color: T.text, fontSize: 16 }}>+</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
        <View style={[styles.infoBox, { marginTop: 10 }]}>
          <Text style={styles.infoText}>
            {t('keyboardInfo', lang)}
          </Text>
          <Text style={[styles.infoText, { marginTop: 8 }]}>
            {t('disclosureKeyboard', lang)}
          </Text>
        </View>
        <View style={styles.monitorActions}>
          <TouchableOpacity style={styles.monitorBtn} onPress={() => void openKeyboardSettings()}>
            <Text style={styles.monitorBtnText}>{t('keyboardEnable', lang)}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.monitorBtn} onPress={() => void showKeyboardPicker()}>
            <Text style={styles.monitorBtnText}>{t('keyboardSelect', lang)}</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.monitorActions}>
          <TouchableOpacity style={[styles.monitorBtn, { flex: 0, paddingHorizontal: 20 }]} onPress={() => void requestVoicePermission()}>
            <Text style={styles.monitorBtnText}>{t('voicePermission', lang)}</Text>
          </TouchableOpacity>
        </View>
      </SettingsSection>

      {/* Retention */}
      <SettingsSection title={t("retentionSection", lang)} icon="🗃️" T={T} lang={lang}>
        <OptionGroup<string>
          T={T} lang={lang}
          label={t('autoDelete', lang)}
          value={settings.autoDelete}
          onChange={v => void updateSetting('autoDelete', v as any)}
          options={[
            { label: t('autoDeleteNever', lang), value: 'never' },
            { label: t('autoDelete7d', lang), value: '7d' },
            { label: t('autoDelete30d', lang), value: '30d' },
            { label: t('autoDelete90d', lang), value: '90d' },
          ]}
        />
        <OptionGroup<string>
          T={T} lang={lang}
          label={t('maxClips', lang)}
          value={settings.maxClips}
          onChange={v => void updateSetting('maxClips', v as any)}
          options={[
            { label: t('maxClips500', lang), value: '500' },
            { label: t('maxClips1000', lang), value: '1000' },
            { label: t('maxClipsUnlimited', lang), value: 'unlimited' },
          ]}
        />
      </SettingsSection>

      {/* Google Drive Backup */}
      <SettingsSection title={t("driveSection", lang)} icon="☁️" T={T} lang={lang}>
        <View style={styles.infoBox}>
          <Text style={[styles.infoText]}>
            {t('driveInfo', lang)}
          </Text>
          <Text style={styles.driveStatus}>
            {settings.googleDriveBackupEnabled || driveAccount
              ? `${t('driveConnected', lang)}${driveAccount ? `: ${driveAccount}` : ''}`
              : t('driveNotConnected', lang)}
          </Text>
          {settings.googleDriveLastBackupAt ? (
            <Text style={styles.driveMeta}>
              {t('driveLastBackup', lang)}: {new Date(settings.googleDriveLastBackupAt).toLocaleString(lang === 'en' ? 'en-US' : 'ur-PK')}
            </Text>
          ) : null}
        </View>
        <SettingRow
          T={T} lang={lang}
          label={t('driveAutoBackup', lang)}
          description={t('driveAutoBackupDesc', lang)}
          value={settings.googleDriveBackupEnabled}
          onToggle={value => {
            if (value && !driveAccount && !settings.googleDriveAccountEmail) {
              void connectGoogleDrive();
              return;
            }
            void updateSetting('googleDriveBackupEnabled', value);
          }}
        />
        <View style={styles.monitorActions}>
          <TouchableOpacity
            disabled={driveBusy}
            style={[styles.monitorBtn, driveBusy && styles.btnDisabled]}
            onPress={() => {
              if (settings.googleDriveBackupEnabled || driveAccount) void backupToGoogleDriveNow();
              else void connectGoogleDrive();
            }}
          >
            <Text style={styles.monitorBtnText}>
              {settings.googleDriveBackupEnabled || driveAccount ? t('driveBackupNow', lang) : t('driveConnect', lang)}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            disabled={driveBusy || (!settings.googleDriveBackupEnabled && !driveAccount)}
            style={[styles.monitorBtn, (driveBusy || (!settings.googleDriveBackupEnabled && !driveAccount)) && styles.btnDisabled]}
            onPress={restoreFromGoogleDriveNow}
          >
            <Text style={styles.monitorBtnText}>{t('driveRestoreNow', lang)}</Text>
          </TouchableOpacity>
        </View>

        {(settings.googleDriveBackupEnabled || driveAccount) && (
          <TouchableOpacity
            disabled={driveBusy}
            style={[
              styles.monitorBtn,
              { marginTop: 10, backgroundColor: 'rgba(59, 130, 246, 0.12)', borderColor: '#3b82f6', borderWidth: 1 },
              driveBusy && styles.btnDisabled,
            ]}
            onPress={recoverFromDriveHistoryNow}
          >
            <Text style={[styles.monitorBtnText, { color: '#60a5fa', fontWeight: '600' }]}>
              🕒 {t('driveRecoverHistory', lang)}
            </Text>
          </TouchableOpacity>
        )}

        <SettingRow
          T={T} lang={lang}
          label={lang === 'en' ? 'Sync Password (Encryption)' : 'سنک پاس ورڈ (انکرپشن)'}
          description={lang === 'en' ? 'Encrypt backups. Required to view clips on PC or restore.' : 'بیک اپ کو محفوظ بنائیں۔ پی سی پر دیکھنے کے لیے لازمی ہے۔'}
          value={!!settings.syncPassword}
          onToggle={value => {
            setSyncPasswordMode(value ? 'setup' : 'disable');
            setSyncPasswordPromptVisible(true);
          }}
        />

        {(settings.googleDriveBackupEnabled || driveAccount) && (
          <TouchableOpacity
            disabled={driveBusy}
            style={[styles.dangerBtn, driveBusy && styles.btnDisabled]}
            onPress={disconnectGoogleDrive}
          >
            <Text style={styles.dangerText}>{t('driveDisconnect', lang)}</Text>
          </TouchableOpacity>
        )}
      </SettingsSection>

      {/* Data Management */}
      <SettingsSection title={lang === 'en' ? 'Backup' : 'بیک اپ'} icon="💾" T={T} lang={lang}>
        <TouchableOpacity style={styles.dataBtn} onPress={() => void backupToDrive()}>
          <Text style={styles.dataBtnText}>{t('backup', lang)}</Text>
          <Text style={styles.dataBtnDesc}>{t('backupDesc', lang)}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.dataBtn} onPress={() => void restoreBackup()}>
          <Text style={styles.dataBtnText}>{t('restore', lang)}</Text>
          <Text style={styles.dataBtnDesc}>{t('restoreDesc', lang)}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.dangerBtn]} onPress={clearAllData}>
          <Text style={styles.dangerText}>{t('clearAll', lang)}</Text>
        </TouchableOpacity>
      </SettingsSection>

      {/* About */}
      <View style={styles.about}>
        <Image source={require('../assets/splash-icon.png')} style={styles.aboutLogo} />
        <Text style={styles.aboutTitle}>{t('appName', lang)}</Text>
        <Text style={styles.aboutBrand}>by {t('mindcrafts', lang)}</Text>
        <Text style={styles.aboutVersion}>{t('version', lang)} 1.2.7 · Build 24</Text>
        <Text style={styles.aboutTagline}>{t('appTagline', lang)}</Text>

        <View style={styles.contactRow}>
          {[
            { label: `⭐ ${lang === 'en' ? 'Rate on Google Play' : 'پلے اسٹور پر ریویو دیں'}`, url: 'https://play.google.com/store/apps/details?id=com.clipvault.mobile' },
            { label: `💬 ${t('whatsapp', lang)}`, url: WHATSAPP_URL },
            { label: `🌐 codehubb.com`, url: CODEHUBB_URL },
            { label: `🌐 mindcrafts.net`, url: MINDCRAFTS_URL },
            { label: `🔒 ${t('privacyPolicy', lang)}`, url: PRIVACY_POLICY_URL },
          ].map(c => (
            <TouchableOpacity key={c.url} style={styles.contactPill} onPress={() => void openExternal(c.url)}>
              <Text style={styles.contactText}>{c.label}</Text>
            </TouchableOpacity>
          ))}
        </View>
    </View>

      <PinManagerModal 
        visible={pinModalVisible}
        mode={pinMode}
        lang={lang}
        T={T}
        currentPinHash={settings.pinHash || ''}
        onSuccess={handlePinSuccess}
        onCancel={() => setPinModalVisible(false)}
      />

      <SyncPasswordModal
        visible={syncPasswordPromptVisible}
        mode={syncPasswordMode}
        lang={lang}
        T={T}
        currentPassword={settings.syncPassword}
        onSuccess={async (newPassword) => {
          setSyncPasswordPromptVisible(false);
          await updateSetting('syncPassword', newPassword || undefined);
          if (newPassword) {
            showToast(lang === 'en' ? '✅ Sync Password set. Backups will be encrypted.' : '✅ سنک پاس ورڈ سیٹ ہو گیا۔ بیک اپ اب انکرپٹ ہوں گے۔');
          } else {
            showToast(lang === 'en' ? '🔓 Sync Password removed. Backups are unencrypted.' : '🔓 سنک پاس ورڈ ہٹا دیا گیا۔ بیک اپ غیر محفوظ ہیں۔');
          }
        }}
        onCancel={() => setSyncPasswordPromptVisible(false)}
      />

    </ScrollView>
  );
}

// ─────────────────────────────────────────────────────────────
// Styles
// ─────────────────────────────────────────────────────────────
const getStyles = (T: ThemeColors, lang: AppLanguage, topInset: number) => {
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;
  const ff = nasta ? { fontFamily: nasta } : {};

  return StyleSheet.create({
    container: { flex: 1, backgroundColor: T.bg },
    content: { paddingBottom: 40 },

    // Header row: lang toggle left + page title right
    headerRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 16,
      paddingTop: Math.max(16, topInset + 8),
      paddingBottom: 4,
    },
    pageTitle: { fontSize: 22, fontWeight: '800', color: T.text, ...ff },

    // Inline lang toggle pill in header
    langPill: {
      flexDirection: 'row',
      backgroundColor: T.surface,
      borderRadius: 20,
      borderWidth: 1,
      borderColor: T.border,
      overflow: 'hidden',
    },
    langPillBtn: { paddingHorizontal: 12, paddingVertical: 6 },
    langPillBtnActive: { backgroundColor: T.accent, borderRadius: 18 },
    langPillText: { color: T.muted, fontSize: 13, fontWeight: '700', ...ff },
    langPillTextActive: { color: '#fff' },

    section: { marginHorizontal: 12, marginBottom: 16 },
    sectionHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 8 },
    sectionIcon: { fontSize: 14 },
    sectionTitle: { fontSize: 12, color: T.muted, fontWeight: '800', textTransform: 'uppercase', letterSpacing: 1, ...ff },
    sectionCard: {
      backgroundColor: T.surface,
      borderRadius: 14,
      borderWidth: 1,
      borderColor: T.border,
      overflow: 'hidden',
    },

    settingRow: {
      flexDirection: 'row',
      alignItems: 'center',
      padding: 14,
      borderBottomWidth: 1,
      borderBottomColor: T.border,
    },
    settingText: { flex: 1, paddingRight: 12 },
    settingLabel: { fontSize: 15, color: T.text, fontWeight: '600', ...ff },
    settingDesc: { fontSize: 12, color: T.muted, marginTop: 2, lineHeight: 17, ...ff },

    subLabel: { fontSize: 12, color: T.muted, paddingHorizontal: 14, paddingTop: 14, paddingBottom: 4, fontWeight: '700', ...ff },
    optionRow: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingVertical: 12,
      paddingHorizontal: 16,
      borderBottomWidth: 1,
      borderBottomColor: T.border,
    },
    optionActive: { backgroundColor: `${T.accent}12` },
    optionText: { fontSize: 14, color: T.muted, ...ff },
    optionTextActive: { color: T.accent, fontWeight: '700' },
    optionDesc: { fontSize: 11, color: T.muted, marginTop: 2, opacity: 0.7, ...ff },

    monitorActions: { flexDirection: 'row', gap: 8, padding: 12 },
    monitorBtn: {
      flex: 1,
      borderRadius: 10,
      padding: 12,
      alignItems: 'center',
      backgroundColor: T.accentGlow,
      borderWidth: 1,
      borderColor: `${T.accent}44`,
    },
    monitorStopBtn: { backgroundColor: T.redDim, borderColor: `${T.red}44` },
    btnDisabled: { opacity: 0.45 },
    monitorBtnText: { color: T.text, fontSize: 13, fontWeight: '700', ...ff },

    infoBox: { padding: 14, borderBottomWidth: 1, borderBottomColor: T.border },
    infoText: { color: T.muted, fontSize: 13, lineHeight: 20, ...ff },
    driveStatus: { color: T.text, fontSize: 13, fontWeight: '800', marginTop: 10, ...ff },
    driveMeta: { color: T.muted, fontSize: 11, marginTop: 4, ...ff },

    dataBtn: {
      margin: 10,
      marginBottom: 0,
      borderRadius: 10,
      padding: 14,
      backgroundColor: T.accentGlow,
      borderWidth: 1,
      borderColor: `${T.accent}33`,
    },
    dataBtnText: { color: T.text, fontSize: 14, fontWeight: '700', ...ff },
    dataBtnDesc: { color: T.muted, fontSize: 12, marginTop: 3, ...ff },
    dangerBtn: {
      margin: 10,
      marginTop: 8,
      borderRadius: 10,
      padding: 14,
      alignItems: 'center',
      backgroundColor: T.redDim,
      borderWidth: 1,
      borderColor: `${T.red}44`,
    },
    dangerText: { color: T.red, fontSize: 14, fontWeight: '700', ...ff },

    about: { alignItems: 'center', padding: 24, paddingTop: 8 },
    aboutLogo: {
      width: 80,
      height: 80,
      borderRadius: 18,
      borderWidth: 1,
      borderColor: T.border,
      marginBottom: 12,
    },
    aboutTitle: { fontSize: 20, fontWeight: '800', color: T.text, ...ff },
    aboutBrand: { fontSize: 13, color: T.accent, fontWeight: '700', marginTop: 4, ...ff },
    aboutVersion: { fontSize: 12, color: T.muted, marginTop: 4, ...ff },
    aboutTagline: { fontSize: 13, color: T.accent, marginTop: 8, fontStyle: 'italic', ...ff },
    contactRow: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: 8, marginTop: 16 },
    contactPill: {
      borderWidth: 1,
      borderColor: T.border,
      borderRadius: 10,
      paddingHorizontal: 12,
      paddingVertical: 9,
      backgroundColor: T.surface,
    },
    contactText: { color: T.text, fontSize: 12, fontWeight: '700', ...ff },

    // Language toggle section styles
    langToggleRow: {
      flexDirection: 'row',
      gap: 10,
      padding: 10,
      paddingTop: 12,
    },
    langOption: {
      flex: 1,
      alignItems: 'center',
      paddingVertical: 14,
      paddingHorizontal: 10,
      borderRadius: 12,
      borderWidth: 1.5,
      borderColor: T.border,
      backgroundColor: T.surface,
    },
    langOptionActive: {
      borderColor: T.accent,
      backgroundColor: `${T.accent}22`,
    },
    langOptionText: {
      color: T.muted,
      fontSize: 16,
      fontWeight: '700',
      ...ff
    },
    langOptionTextActive: {
      color: T.accent,
    },
    langOptionSub: {
      color: T.muted,
      fontSize: 10,
      marginTop: 4,
      ...ff
    },
  });
};
