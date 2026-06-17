import AsyncStorage from '@react-native-async-storage/async-storage';
import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import * as Haptics from 'expo-haptics';
import { Slot, useSegments } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Animated,
  AppState,
  AppStateStatus,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  Linking,
  ActivityIndicator,
} from 'react-native';
import { useFonts } from 'expo-font';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { NASTALEEQ_FONT, t } from '../services/i18n';
import { AppLanguage, DEFAULT_SETTINGS, SETTINGS_KEY } from '../services/types';
import { useClipStore } from '../store/useClipStore';
import { useAppTheme } from '../theme/useAppTheme';
import { ThemeColors } from '../theme/colors';
import { OnboardingWizard } from '../components/OnboardingWizard';
import { GoogleDriveBackupService } from '../services/GoogleDriveBackup';

const ONBOARDING_VERSION_KEY = 'onboarding_v2_complete';

async function readAppSettings() {
  try {
    const saved = await AsyncStorage.getItem(SETTINGS_KEY);
    return saved ? { ...DEFAULT_SETTINGS, ...JSON.parse(saved) } : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}

async function isBiometricEnabled(): Promise<boolean> {
  const s = await readAppSettings();
  return s.biometricLock === true;
}

function LockScreen({
  onUnlock,
  message,
  lang,
  T,
}: {
  onUnlock: () => void;
  message: string;
  lang: AppLanguage;
  T: ThemeColors;
}) {
  const insets = useSafeAreaInsets();
  const pulse = useRef(new Animated.Value(1)).current;
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;

  useEffect(() => {
    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1.08, duration: 1200, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 1, duration: 1200, useNativeDriver: true }),
      ]),
    );
    animation.start();
    return () => animation.stop();
  }, [pulse]);

  const lockStyles = React.useMemo(() => getLockStyles(T), [T]);

  return (
    <View style={[lockStyles.container, { paddingTop: insets.top + 24, paddingBottom: insets.bottom + 24 }]}>
      <Animated.View style={[lockStyles.iconWrap, { transform: [{ scale: pulse }] }]}>
        <Text style={lockStyles.icon}>🔐</Text>
      </Animated.View>
      <Text style={lockStyles.title}>{t('lockTitle', lang)}</Text>
      <Text style={lockStyles.brand}>by MindCrafts_AI</Text>
      <Text style={[lockStyles.subtitle, nasta ? { fontFamily: nasta, fontSize: 18 } : {}]}>{t('lockSubtitle', lang)}</Text>
      {message ? <Text style={[lockStyles.error, nasta ? { fontFamily: nasta } : {}]}>{message}</Text> : null}
      <TouchableOpacity style={lockStyles.unlockBtn} onPress={onUnlock} activeOpacity={0.8}>
        <Text style={lockStyles.unlockText}>{t('lockBtn', lang)}</Text>
      </TouchableOpacity>
      <Text style={[lockStyles.hint, nasta ? { fontFamily: nasta } : {}]}>{t('lockHint', lang)}</Text>
    </View>
  );
}

const getLockStyles = (T: ThemeColors) => StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: T.bg,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 32,
  },
  iconWrap: {
    width: 100,
    height: 100,
    borderRadius: 28,
    backgroundColor: `${T.accent}33`,
    borderWidth: 1,
    borderColor: `${T.accent}66`,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 24,
  },
  icon: {
    fontSize: 48,
  },
  title: {
    color: T.text,
    fontSize: 28,
    fontWeight: '800',
    letterSpacing: 1,
  },
  brand: {
    color: T.accent,
    fontSize: 13,
    fontWeight: '700',
    marginTop: 4,
    marginBottom: 8,
  },
  subtitle: {
    color: T.muted,
    fontSize: 14,
    marginBottom: 24,
    textAlign: 'center',
  },
  error: {
    color: T.red,
    fontSize: 13,
    marginBottom: 16,
    textAlign: 'center',
  },
  unlockBtn: {
    backgroundColor: T.accent,
    borderRadius: 14,
    paddingHorizontal: 40,
    paddingVertical: 16,
    marginBottom: 16,
    shadowColor: T.accent,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.4,
    shadowRadius: 12,
    elevation: 8,
  },
  unlockText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  hint: {
    color: T.muted,
    fontSize: 12,
  },
});

export default function RootLayout() {
  const [lockChecked, setLockChecked] = useState(false);
  const [unlocked, setUnlocked] = useState(false);
  const [lockMessage, setLockMessage] = useState('');
  const [onboardingComplete, setOnboardingComplete] = useState<boolean | null>(null);
  
  const T = useAppTheme();
  const { loadSettings, loadStats, showToast } = useClipStore();
  const settings = useClipStore(state => state.settings);
  const lang: AppLanguage = (settings.appLanguage as AppLanguage) ?? 'ur';

  const segments = useSegments();
  const isRedirectRoute = segments[0] === 'oauth2redirect';

  // Global Google login states
  const [googleAuthStatus, setGoogleAuthStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
  const [googleAuthError, setGoogleAuthError] = useState('');
  const processingRef = useRef(false);

  // Listen to deep links at the root layout level to prevent missing redirect events
  useEffect(() => {
    const handleUrl = async (url: string | null) => {
      if (!url || !url.includes('oauth2redirect')) return;
      if (processingRef.current) return;
      processingRef.current = true;

      setGoogleAuthStatus('loading');
      setGoogleAuthError('');

      try {
        const result = await GoogleDriveBackupService.completeSignInFromUrl(url);
        if (result.handled) {
          setGoogleAuthStatus('success');
          await loadSettings();
          const restored = await GoogleDriveBackupService.restoreOnceAfterSignIn();
          await loadSettings();
          await loadStats();

          showToast(
            restored > 0
              ? (lang === 'en' ? `✅ Restored ${restored} clips from Drive` : `✅ Drive سے ${restored} کلپس بحال ہو گئیں`)
              : (lang === 'en' ? '✅ Google Drive connected' : '✅ Google Drive کنیکٹ ہو گئی'),
          );

          setTimeout(() => {
            setGoogleAuthStatus('idle');
          }, 1500);
        } else {
          setGoogleAuthStatus('idle');
        }
      } catch (err) {
        // Double check if already authenticated (succeeded via another call)
        const email = await GoogleDriveBackupService.getAccountEmail();
        if (email) {
          setGoogleAuthStatus('success');
          await loadSettings();
          await loadStats();
          setTimeout(() => {
            setGoogleAuthStatus('idle');
          }, 1500);
          return;
        }

        setGoogleAuthStatus('error');
        setGoogleAuthError(err instanceof Error ? err.message : String(err));
        setTimeout(() => {
          setGoogleAuthStatus('idle');
        }, 3000);
      } finally {
        processingRef.current = false;
      }
    };

    // Capture initial URL on boot
    void Linking.getInitialURL().then(handleUrl);

    // Capture incoming URLs while active/background
    const subscription = Linking.addEventListener('url', (event) => {
      void handleUrl(event.url);
    });

    return () => {
      subscription.remove();
    };
  }, [lang, loadSettings, loadStats, showToast]);

  // ── Biometric/Device lock auth ────────────────────────
  const authenticate = useCallback(async () => {
    setLockMessage('');
    try {
      const shouldLock = await isBiometricEnabled();

      if (!shouldLock) {
        setUnlocked(true);
        setLockChecked(true);
        return;
      }

      const hasHardware = await LocalAuthentication.hasHardwareAsync();
      const isEnrolled = await LocalAuthentication.isEnrolledAsync();

      if (hasHardware && isEnrolled) {
        const result = await LocalAuthentication.authenticateAsync({
          promptMessage: t('authUnlockTitle', lang),
          cancelLabel: t('authLater', lang),
          disableDeviceFallback: false,
        });

        if (result.success) {
          setUnlocked(true);
          setLockChecked(true);
          return;
        } else {
          setLockMessage(t('lockError', lang));
        }
      } else {
        // If the device has no security credentials set up, do not lock the app
        setUnlocked(true);
        setLockChecked(true);
        return;
      }
      setUnlocked(false);
    } catch (err) {
      console.warn('Authentication error:', err);
      // Fallback: unlock if auth hardware check fails to prevent permanent freeze
      setUnlocked(true);
    } finally {
      setLockChecked(true);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lang]);

  // ── Initial auth on cold start only ───────────────────
  useEffect(() => {
    AsyncStorage.getItem(ONBOARDING_VERSION_KEY)
      .then(val => {
        setOnboardingComplete(val === 'true');
      })
      .catch(err => {
        console.warn('AsyncStorage ONBOARDING error:', err);
        setOnboardingComplete(false);
      });

    loadSettings()
      .then(() => {
        return authenticate();
      })
      .catch(err => {
        console.warn('loadSettings/authenticate error:', err);
        setLockChecked(true);
      });
  }, [authenticate, loadSettings]);

  // ── AppState: track to detect complete app kill/restart ──
  const appState = useRef(AppState.currentState);
  useEffect(() => {
    const sub = AppState.addEventListener('change', (nextState: AppStateStatus) => {
      appState.current = nextState;
    });
    return () => sub.remove();
  }, []);

  const styles = React.useMemo(() => StyleSheet.create({
    root: { flex: 1, backgroundColor: T.bg },
    loading: {
      flex: 1,
      backgroundColor: T.bg,
      alignItems: 'center',
      justifyContent: 'center',
    },
    loadingText: {
      color: T.text,
      fontSize: 28,
      fontWeight: '800',
      letterSpacing: 1,
    },
    loadingBrand: {
      color: T.accent,
      fontSize: 13,
      fontWeight: '700',
      marginTop: 6,
    },
  }), [T]);

  const [fontsLoaded, fontError] = useFonts({
    'jameel_noori_nastaleeq': require('../assets/fonts/jameel_noori_nastaleeq.ttf'),
  });

  const [fontTimeout, setFontTimeout] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => {
      setFontTimeout(true);
    }, 1500); // 1.5 seconds timeout
    return () => clearTimeout(timer);
  }, []);

  if (!lockChecked || (!fontsLoaded && !fontTimeout && !fontError) || onboardingComplete === null) {
    return (
      <View style={styles.loading}>
        <Text style={styles.loadingText}>ClipVault</Text>
        <Text style={styles.loadingBrand}>MindCrafts_AI</Text>
      </View>
    );
  }

  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;
  const ff = nasta ? { fontFamily: nasta } : {};

  const renderContent = () => {
    if (!onboardingComplete && !isRedirectRoute) {
      return <OnboardingWizard onComplete={() => setOnboardingComplete(true)} />;
    }

    if (!unlocked && !isRedirectRoute) {
      return (
        <LockScreen
          onUnlock={authenticate}
          message={lockMessage}
          lang={lang}
          T={T}
        />
      );
    }

    return (
      <View style={styles.root}>
        <Slot />
      </View>
    );
  };

  return (
    <SafeAreaProvider>
      <View style={styles.root}>
        {renderContent()}

        {/* Global Google OAuth Overlay */}
        {googleAuthStatus !== 'idle' && (
          <View style={[StyleSheet.absoluteFill, { backgroundColor: T.bg, alignItems: 'center', justifyContent: 'center', padding: 24, zIndex: 99999 }]}>
            {googleAuthStatus === 'loading' && (
              <>
                <ActivityIndicator size="large" color={T.accent} style={{ marginBottom: 16 }} />
                <Text style={[{ fontSize: 20, fontWeight: '800', color: T.text, textAlign: 'center', marginBottom: 8 }, ff]}>
                  {lang === 'en' ? 'Connecting to Google...' : 'گوگل سے کنیکٹ کیا جا رہا ہے...'}
                </Text>
                <Text style={[{ fontSize: 14, color: T.muted, textAlign: 'center' }, ff]}>
                  {lang === 'en' ? 'Please wait a moment.' : 'براہِ کرم تھوڑا انتظار کریں۔'}
                </Text>
              </>
            )}
            {googleAuthStatus === 'success' && (
              <>
                <Text style={{ fontSize: 64, marginBottom: 16 }}>✅</Text>
                <Text style={[{ fontSize: 20, fontWeight: '800', color: T.green, textAlign: 'center', marginBottom: 8 }, ff]}>
                  {lang === 'en' ? 'Connected Successfully!' : 'کامیابی سے کنیکٹ ہو گیا!'}
                </Text>
                <Text style={[{ fontSize: 14, color: T.muted, textAlign: 'center' }, ff]}>
                  {lang === 'en' ? 'Returning to ClipVault...' : 'ایپ میں واپس جا رہے ہیں...'}
                </Text>
              </>
            )}
            {googleAuthStatus === 'error' && (
              <>
                <Text style={{ fontSize: 64, marginBottom: 16 }}>❌</Text>
                <Text style={[{ fontSize: 20, fontWeight: '800', color: T.red, textAlign: 'center', marginBottom: 8 }, ff]}>
                  {lang === 'en' ? 'Connection Failed' : 'کنکشن ناکام ہو گیا'}
                </Text>
                <Text style={[{ fontSize: 13, color: T.muted, textAlign: 'center', marginTop: 8 }, ff]}>
                  {googleAuthError}
                </Text>
              </>
            )}
          </View>
        )}
      </View>
    </SafeAreaProvider>
  );
}
