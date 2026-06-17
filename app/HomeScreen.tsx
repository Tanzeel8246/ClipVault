/**
 * HomeScreen.tsx — ClipVault Premium Dashboard
 *
 * بہتریاں:
 * - Zustand سے گلوبل اسٹیٹ
 * - حساس مواد (OTP/پاس ورڈ/کارڈ) کا خودکار ماسک
 * - URL/فون/ای میل پر Smart Action بٹنز
 * - پریمیم کلپ کارڈ ڈیزائن
 * - بہتر خالی اسٹیٹ
 * - سوئپ ٹو ڈیلیٹ / سوئپ ٹو فیورٹ (PanResponder)
 * - ریئل ٹائم سرچ
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Animated,
  FlatList,
  Image,
  Modal,
  RefreshControl,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { FlashList } from '@shopify/flash-list';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from 'expo-haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { openKeyboardSettings, startBackgroundMonitor } from '../services/BackgroundMonitor';
import { ClipboardMonitor } from '../services/ClipboardMonitor';
import { DatabaseService, Exporter } from '../services/CoreServices';
import { GoogleDriveBackupService } from '../services/GoogleDriveBackup';
import { NASTALEEQ_FONT, t } from '../services/i18n';
import { useAppTheme } from '../theme/useAppTheme';
import { ThemeColors } from '../theme/colors';
import { AppLanguage, ClipItem, ClipSettings, DEFAULT_SETTINGS, SETTINGS_KEY } from '../services/types';
import { useClipStore } from '../store/useClipStore';

import { CATEGORIES, getCategoryColors } from '../components/CategoryUtils';
import { ClipCard } from '../components/ClipCard';
import { EmptyState } from '../components/EmptyState';
import { Toast } from '../components/Toast';

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────
async function readSettings(): Promise<ClipSettings> {
  try {
    const saved = await AsyncStorage.getItem(SETTINGS_KEY);
    return saved ? { ...DEFAULT_SETTINGS, ...JSON.parse(saved) } : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}



function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'کچھ غلط ہو گیا۔';
}

// ─────────────────────────────────────────────────────────────
// Main HomeScreen
// ─────────────────────────────────────────────────────────────
export default function HomeScreen() {
  const T = useAppTheme();
  const settings = useClipStore(state => state.settings);
  const lang = (settings.appLanguage ?? 'ur') as 'ur' | 'en';
  const styles = React.useMemo(() => getStyles(T, lang), [T, lang]);
  const catColors = React.useMemo(() => getCategoryColors(T), [T]);
  const insets = useSafeAreaInsets();
  const clips = useClipStore(state => state.clips);
  const stats = useClipStore(state => state.stats);
  const search = useClipStore(state => state.search);
  const activeCategory = useClipStore(state => state.activeCategory);
  const isRefreshing = useClipStore(state => state.isRefreshing);
  const error = useClipStore(state => state.error);
  const toastMessage = useClipStore(state => state.toastMessage);
  const toastVisible = useClipStore(state => state.toastVisible);
  const setSearch = useClipStore(state => state.setSearch);
  const setCategory = useClipStore(state => state.setCategory);
  const loadClips = useClipStore(state => state.loadClips);
  const refreshClips = useClipStore(state => state.refreshClips);
  const favoriteClip = useClipStore(state => state.favoriteClip);
  const pinClip = useClipStore(state => state.pinClip);
  const deleteClip = useClipStore(state => state.deleteClip);
  const showToast = useClipStore(state => state.showToast);
  const updateSetting = useClipStore(state => state.updateSetting);

  const [maskSensitive, setMaskSensitive] = useState(true);
  const [showExport, setShowExport] = useState(false);
  const showDisclosure = settings.privacyDisclosureAccepted === false;

  const loadClipsRef = useRef(loadClips);
  useEffect(() => {
    loadClipsRef.current = loadClips;
  }, [loadClips]);

  // ── Bootstrap ───────────────────────────────────────────
  useEffect(() => {
    let active = true;

    const bootstrap = async () => {
      try {
        const settings = await readSettings();
        setMaskSensitive(settings.maskSensitive ?? true);
        await DatabaseService.init();
        await DatabaseService.applyMaintenance(settings);
        if (settings.googleDriveBackupEnabled) {
          try {
            const restored = await GoogleDriveBackupService.restoreOnceAfterSignIn();
            if (restored > 0) showToast(lang === 'en' ? `Restored ${restored} clips from Drive` : `Drive سے ${restored} کلپس بحال ہو گئیں`);
          } catch {
            // Drive restore should never block local clipboard access.
          }
        }
        if (!active) return;

        await loadClipsRef.current();
        if (settings.googleDriveBackupEnabled) {
          GoogleDriveBackupService.queueAutoBackup();
        }

        if (settings.monitoringEnabled) {
          await ClipboardMonitor.startMonitoring(
            async (clip) => {
              if (!active) return;
              showToast(clip.saveStatus === 'updated' ? t('captureUpdated', lang) : t('captureSaved', lang));
              await loadClipsRef.current();
            },
            { showNotifications: settings.showNotifications, intervalMs: 1000 },
          );

          if (settings.backgroundMonitorEnabled) {
            try {
              await ClipboardMonitor.requestPermissions(true);
              await startBackgroundMonitor(settings.autoMonitorMinutes);
            } catch {
              // Silent — foreground monitor still works
            }
          }
        }
      } catch (err) {
        if (active) useClipStore.setState({ error: getErrorMessage(err) });
      }
    };

    void bootstrap();

    return () => {
      active = false;
      ClipboardMonitor.stopMonitoring();
    };
  }, [showToast]);

  // ── Reload on search/category change ────────────────────
  useEffect(() => {
    const timer = setTimeout(() => {
      void loadClips();
    }, 300);
    return () => clearTimeout(timer);
  }, [search, activeCategory, loadClips]);

  // ── Actions ─────────────────────────────────────────────
  const handleCopy = async (text: string) => {
    await Clipboard.setStringAsync(text);
    void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    showToast(t('copied', lang));
  };

  const handleCaptureCurrent = async () => {
    try {
      const clip = await ClipboardMonitor.captureCurrent(async (savedClip) => {
        showToast(savedClip.saveStatus === 'updated' ? t('captureUpdated', lang) : t('captureSaved', lang));
        await loadClipsRef.current();
      });

      if (clip) {
        void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      } else {
        showToast(ClipboardMonitor.getLastReadBlocked() ? t('captureBlocked', lang) : t('captureEmpty', lang));
        void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
      }

      await loadClips();
    } catch (err) {
      Alert.alert('کیپچر ناکام', getErrorMessage(err));
    }
  };

  const acceptDisclosure = async () => {
    await updateSetting('privacyDisclosureAccepted', true);
    showToast(lang === 'en' ? 'Privacy choices saved' : 'پرائیویسی انتخاب محفوظ ہو گیا');
  };

  const addClip = useClipStore(state => state.addClip);
  const updateClipText = useClipStore(state => state.updateClipText);
  const toggleSensitive = useClipStore(state => state.toggleSensitive);

  const [editorVisible, setEditorVisible] = useState(false);
  const [editingClip, setEditingClip] = useState<ClipItem | null>(null);
  const [editorText, setEditorText] = useState('');

  const handleOpenAdd = () => {
    setEditingClip(null);
    setEditorText('');
    setEditorVisible(true);
  };

  const handleOpenEdit = (clip: ClipItem) => {
    setEditingClip(clip);
    setEditorText(clip.text);
    setEditorVisible(true);
  };

  const handleSaveClip = async () => {
    const text = editorText.trim();
    if (!text) {
      Alert.alert(lang === 'en' ? 'Empty Text' : 'خالی متن', lang === 'en' ? 'Clip text cannot be empty.' : 'کلپ کا متن خالی نہیں ہو سکتا۔');
      return;
    }

    try {
      if (editingClip) {
        await updateClipText(editingClip.id, text);
        showToast(lang === 'en' ? '📝 Clip updated' : '📝 کلپ اپڈیٹ ہو گئی');
      } else {
        await addClip(text);
        showToast(lang === 'en' ? '✅ Clip added' : '✅ کلپ محفوظ ہو گئی');
      }
      setEditorVisible(false);
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Save Failed' : 'محفوظ کرنا ناکام', getErrorMessage(err));
    }
  };

  const handleMarkNormal = (id: string) => {
    Alert.alert(
      lang === 'en' ? 'Mark as Normal' : 'غیر حساس بنائیں',
      lang === 'en'
        ? 'Are you sure you want to declare this clip as non-sensitive? This will stop masking and allow it to display normally.'
        : 'کیا آپ واقعی اس کلپ کو غیر حساس قرار دینا چاہتے ہیں؟ اس سے ماسکنگ ختم ہو جائے گی اور یہ عام طور پر دکھائی دے گا۔',
      [
        { text: t('cancel', lang), style: 'cancel' },
        {
          text: lang === 'en' ? 'Yes' : 'جی ہاں',
          onPress: async () => {
            await toggleSensitive(id, false);
            showToast(lang === 'en' ? '🔓 Clip marked as normal' : '🔓 کلپ غیر حساس کر دی گئی');
          }
        }
      ]
    );
  };

  const handleDelete = (id: string) => {
    Alert.alert(t('delete', lang), lang === 'en' ? 'Are you sure you want to permanently delete this clip?' : 'کیا آپ اس کلپ کو ہمیشہ کے لیے حذف کرنا چاہتے ہیں؟', [
      { text: t('cancel', lang), style: 'cancel' },
      {
        text: t('delete', lang),
        style: 'destructive',
        onPress: async () => {
          await deleteClip(id);
          showToast(lang === 'en' ? '🗑️ Clip deleted' : '🗑️ کلپ حذف ہو گئی');
        },
      },
    ]);
  };

  const exportClips = async (format: 'txt' | 'csv' | 'pdf') => {
    try {
      const all = await DatabaseService.getAllForExport();
      if (!all.length) {
        Alert.alert(lang === 'en' ? 'Empty' : 'خالی', lang === 'en' ? 'No clips to export.' : 'ایکسپورٹ کے لیے کوئی کلپ نہیں ہے۔');
        return;
      }

      if (format === 'txt') await Exporter.exportTXT(all);
      if (format === 'csv') await Exporter.exportCSV(all);
      if (format === 'pdf') await Exporter.exportPDF(all);

      setShowExport(false);
    } catch (err) {
      Alert.alert(lang === 'en' ? 'Export Failed' : 'ایکسپورٹ ناکام', getErrorMessage(err));
    }
  };

  // ─────────────────────────────────────────────────────────
  // Render
  // ─────────────────────────────────────────────────────────
  return (
    <View style={[styles.container, { paddingTop: insets.top }]}>
      <StatusBar barStyle="light-content" backgroundColor={T.bg} />

      {/* Toast */}
      <Toast message={toastMessage} visible={toastVisible} T={T} />

      {/* First-run Play Store disclosure */}
      <Modal visible={showDisclosure} transparent animationType="fade">
        <View style={styles.disclosureBg}>
          <View style={styles.disclosureCard}>
            <Image source={require('../assets/splash-icon.png')} style={styles.disclosureLogo} />
            <Text style={[styles.disclosureTitle]}>
              {t('disclosureTitle', lang)}
            </Text>
            <Text style={[styles.disclosureIntro]}>
              {t('disclosureIntro', lang)}
            </Text>
            {[
              t('disclosureLocal', lang),
              t('disclosureKeyboard', lang),
              t('disclosureMic', lang),
              t('disclosureSensitive', lang),
            ].map(item => (
              <View key={item} style={styles.disclosurePoint}>
                <Text style={styles.disclosureDot}>•</Text>
                <Text style={[styles.disclosurePointText]}>{item}</Text>
              </View>
            ))}
            <TouchableOpacity style={styles.disclosurePrimary} onPress={() => void acceptDisclosure()}>
              <Text style={[styles.disclosurePrimaryText]}>
                {t('disclosureAccept', lang)}
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.disclosureSecondary}
              onPress={() => {
                void acceptDisclosure();
                void openKeyboardSettings();
              }}
            >
              <Text style={[styles.disclosureSecondaryText]}>
                {t('disclosureKeyboardBtn', lang)}
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      {/* Header */}
      <View style={styles.header}>
        <View style={styles.headerTop}>
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

          <View style={styles.brandRow}>
            <Image source={require('../assets/splash-icon.png')} style={styles.brandIcon} />
            <View style={{ flex: 1, alignItems: 'flex-end' }}>
              <Text style={styles.logo}>{t('appName', lang)}</Text>
              <Text style={styles.brandText}>{t('mindcrafts', lang)}</Text>
              <Text style={[styles.statsText]}>
                {`${stats.total} ${t('total', lang)} · ${stats.today} ${t('today', lang)}${stats.sensitive > 0 ? ` · 🔒${stats.sensitive} ${t('sensitive', lang)}` : ''}`}
              </Text>
            </View>
          </View>
        </View>
        <View style={styles.headerActions}>
          <TouchableOpacity style={styles.headerBtn} onPress={() => void handleCaptureCurrent()}>
            <Text style={[styles.headerBtnText]}>
              {t('capture', lang)}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.headerBtn} onPress={() => setShowExport(true)}>
            <Text style={[styles.headerBtnText]}>
              {t('export', lang)}
            </Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Search */}
      <View style={styles.searchRow}>
        <View style={styles.searchWrap}>
          <Text style={styles.searchIcon}>🔍</Text>
          <TextInput
            style={[styles.searchInput]}
            placeholder={t('searchPlaceholder', lang)}
            placeholderTextColor={T.muted}
            value={search}
            onChangeText={setSearch}
          />
          {search.length > 0 && (
            <TouchableOpacity onPress={() => setSearch('')}>
              <Text style={styles.clearBtn}>✕</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* Category Filter */}
      <FlatList
        horizontal
        showsHorizontalScrollIndicator={false}
        data={CATEGORIES.filter(item => item === 'All' || (stats.categories[item] ?? 0) > 0)}
        keyExtractor={item => item}
        style={styles.catList}
        contentContainerStyle={styles.catContent}
        renderItem={({ item }) => {
          const color = catColors[item] ?? T.accent;
          const active = activeCategory === item;
          const count = item === 'All' ? stats.total : (stats.categories[item] ?? 0);
          return (
            <TouchableOpacity
              style={[styles.catChip, active && { backgroundColor: color, borderColor: color }]}
              onPress={() => setCategory(item)}
              activeOpacity={0.8}
            >
              <Text style={[styles.catChipText, active && { color: '#fff' }]}>
                {item === 'All' ? t('all', lang) : t(('cat' + item.replace(' ', '')) as any, lang)}
              </Text>
              <Text style={[styles.catChipCount, active && { color: '#fff' }]}>{count}</Text>
            </TouchableOpacity>
          );
        }}
      />

      {/* Error */}
      {error ? <Text style={styles.errorText}>⚠️ {error}</Text> : null}

      {/* Clip List */}
      <View style={{ flex: 1, width: '100%' }}>
      <FlashList
        data={clips}
        keyExtractor={item => item.id}
        contentContainerStyle={styles.clipList}
        estimatedItemSize={150}
        refreshControl={
          <RefreshControl
            refreshing={isRefreshing}
            onRefresh={async () => {
              await ClipboardMonitor.captureCurrent(async (savedClip) => {
                showToast(savedClip.saveStatus === 'updated' ? t('captureUpdated', lang) : t('captureSaved', lang));
                await loadClipsRef.current();
              });
              await refreshClips();
            }}
            tintColor={T.accent}
            colors={[T.accent]}
          />
        }
        ListEmptyComponent={<EmptyState search={search} lang={lang} T={T} />}
        renderItem={({ item }) => (
          <ClipCard
            clip={item}
            maskSensitive={maskSensitive}
            onFavorite={favoriteClip}
            onDelete={handleDelete}
            onCopy={handleCopy}
            onPin={pinClip}
            onEdit={handleOpenEdit}
            onMarkNormal={handleMarkNormal}
            lang={lang}
            T={T}
          />
        )}
      />
      </View>

      {/* Export Modal */}
      <Modal visible={showExport} transparent animationType="slide" onRequestClose={() => setShowExport(false)}>
        <View style={styles.modalBg}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>{t('exportTitle', lang)}</Text>
            <Text style={styles.modalSub}>{clips.length} {t('exportSub', lang)}</Text>
            {[
              { format: 'txt' as const, label: t('exportTxt', lang), desc: t('exportTxtDesc', lang) },
              { format: 'csv' as const, label: t('exportCsv', lang), desc: t('exportCsvDesc', lang) },
              { format: 'pdf' as const, label: t('exportPdf', lang), desc: t('exportPdfDesc', lang) },
            ].map(e => (
              <TouchableOpacity key={e.format} style={styles.modalBtn} onPress={() => void exportClips(e.format)}>
                <Text style={styles.modalBtnText}>{e.label}</Text>
                <Text style={styles.modalBtnDesc}>{e.desc}</Text>
              </TouchableOpacity>
            ))}
            <TouchableOpacity style={styles.modalCancel} onPress={() => setShowExport(false)}>
              <Text style={styles.cancelText}>{t('cancel', lang)}</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      {/* Floating Action Button */}
      <TouchableOpacity
        style={[
          styles.fab,
          {
            backgroundColor: T.accent,
            shadowColor: T.accent,
            shadowOffset: { width: 0, height: 4 },
            shadowOpacity: 0.4,
            shadowRadius: 8,
            elevation: 6,
          }
        ]}
        onPress={handleOpenAdd}
        activeOpacity={0.8}
      >
        <Text style={styles.fabIcon}>＋</Text>
      </TouchableOpacity>

      {/* Add/Edit Modal */}
      <Modal visible={editorVisible} transparent animationType="slide" onRequestClose={() => setEditorVisible(false)}>
        <View style={styles.modalBg}>
          <View style={[styles.modalCard, { borderTopWidth: 1, borderColor: T.border }]}>
            <Text style={styles.modalTitle}>
              {editingClip
                ? (lang === 'en' ? 'Edit Clip' : 'کلپ ترمیم کریں')
                : (lang === 'en' ? 'New Clip' : 'نیا کلپ لکھیں')}
            </Text>
            
            <TextInput
              style={{
                backgroundColor: `${T.bg}bb`,
                color: T.text,
                borderRadius: 12,
                borderWidth: 1,
                borderColor: T.border,
                padding: 14,
                fontSize: 15,
                minHeight: 120,
                textAlignVertical: 'top',
                fontFamily: lang === 'ur' ? NASTALEEQ_FONT : undefined,
                marginBottom: 16,
              }}
              multiline
              placeholder={lang === 'en' ? 'Type or paste content here...' : 'یہاں کچھ لکھیں یا پیسٹ کریں...'}
              placeholderTextColor={T.muted}
              value={editorText}
              onChangeText={setEditorText}
              autoFocus
            />

            <View style={{ flexDirection: 'row', gap: 10 }}>
              <TouchableOpacity
                style={[styles.modalBtn, { flex: 1, backgroundColor: T.accent, borderColor: T.accent, marginBottom: 0 }]}
                onPress={() => void handleSaveClip()}
              >
                <Text style={[styles.modalBtnText, { color: '#fff', textAlign: 'center' }]}>
                  {lang === 'en' ? 'Save' : 'محفوظ کریں'}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalCancel, { flex: 1, borderWidth: 1, borderColor: T.border, borderRadius: 12, justifyContent: 'center', alignItems: 'center', padding: 14 }]}
                onPress={() => setEditorVisible(false)}
              >
                <Text style={[styles.cancelText, { color: T.text }]}>{t('cancel', lang)}</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
}

// ─────────────────────────────────────────────────────────────
// Styles
// ─────────────────────────────────────────────────────────────
const getStyles = (T: ThemeColors, lang: AppLanguage) => {
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;
  const ff = nasta ? { fontFamily: nasta } : {};
  return StyleSheet.create({
  container: { flex: 1, backgroundColor: T.bg },

  // Header
  header: { padding: 16, paddingTop: 10 },
  headerTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 },
  brandRow: { flexDirection: 'row', alignItems: 'center', flex: 1 },
  brandIcon: { width: 44, height: 44, borderRadius: 10, borderWidth: 1, borderColor: T.border, marginRight: 12 },
  logo: { fontSize: 20, fontWeight: '800', color: T.text, letterSpacing: 0.5, ...ff },
  brandText: { fontSize: 11, color: T.accent, fontWeight: '700', ...ff },
  statsText: { fontSize: 11, color: T.muted, marginTop: 2, ...ff },
  
  langPill: {
    flexDirection: 'row',
    backgroundColor: `${T.surface}cc`,
    borderRadius: 20,
    padding: 3,
    borderWidth: 1,
    borderColor: `${T.accent}66`,
  },
  langPillBtn: { paddingHorizontal: 16, paddingVertical: 8, borderRadius: 16 },
  langPillBtnActive: { backgroundColor: T.accent, borderRadius: 18 },
  langPillText: { color: T.muted, fontSize: 12, fontWeight: '700', ...ff },
  langPillTextActive: { color: '#fff' },

  headerActions: { flexDirection: 'row', gap: 6 },
  headerBtn: {
    borderWidth: 1,
    borderColor: `${T.accent}88`,
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
    backgroundColor: T.accentGlow,
  },
  headerBtnText: { color: '#38bdf8', fontSize: 12, fontWeight: '800', ...ff },

  // Search
  searchRow: { paddingHorizontal: 12, marginBottom: 8 },
  searchWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: T.surface,
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 12,
    paddingHorizontal: 12,
    gap: 8,
  },
  searchIcon: { fontSize: 15 },
  searchInput: { flex: 1, color: T.text, fontSize: 15, paddingVertical: 11, ...ff },
  clearBtn: { color: T.muted, fontSize: 16, paddingHorizontal: 4 },

  // Categories
  catList: { height: 46, flexGrow: 0, marginBottom: 8 },
  catContent: { paddingLeft: 12, paddingRight: 10, alignItems: 'center' },
  catChip: {
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 8,
    marginRight: 6,
    backgroundColor: T.surface,
    minWidth: 72,
    alignItems: 'center',
  },
  catChipText: { fontSize: 13, color: T.muted, fontWeight: '700', ...ff },
  catChipCount: { color: T.muted, fontSize: 10, marginTop: 2, fontWeight: '800', ...ff },

  // Error
  errorText: { color: T.red, paddingHorizontal: 16, paddingVertical: 6, fontSize: 12, ...ff },

  // Clip list
  clipList: { padding: 12, paddingBottom: 24 },



  // Modal
  modalBg: { flex: 1, backgroundColor: '#000000bb', justifyContent: 'flex-end' },
  modalCard: {
    backgroundColor: T.surface,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    padding: 24,
    borderTopWidth: 1,
    borderColor: T.border,
  },
  modalTitle: { fontSize: 18, fontWeight: '800', color: T.text, marginBottom: 4, textAlign: 'center', ...ff },
  modalSub: { fontSize: 12, color: T.muted, textAlign: 'center', marginBottom: 16, ...ff },
  modalBtn: {
    backgroundColor: T.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: T.border,
  },
  modalBtnText: { color: T.text, fontSize: 15, fontWeight: '700', ...ff },
  modalBtnDesc: { color: T.muted, fontSize: 12, marginTop: 2, ...ff },
  modalCancel: { alignItems: 'center', padding: 14 },
  cancelText: { color: T.muted, fontSize: 14, ...ff },

  // Disclosure
  disclosureBg: {
    flex: 1,
    backgroundColor: '#000000cc',
    justifyContent: 'center',
    padding: 18,
  },
  disclosureCard: {
    backgroundColor: T.surface,
    borderRadius: 18,
    padding: 20,
    borderWidth: 1,
    borderColor: `${T.accent}55`,
  },
  disclosureLogo: {
    width: 58,
    height: 58,
    borderRadius: 14,
    alignSelf: 'center',
    marginBottom: 12,
  },
  disclosureTitle: { color: T.text, fontSize: 20, fontWeight: '900', textAlign: 'center', ...ff },
  disclosureIntro: { color: T.muted, fontSize: 14, lineHeight: 21, textAlign: 'center', marginTop: 8, marginBottom: 14, ...ff },
  disclosurePoint: { flexDirection: 'row', gap: 8, marginBottom: 9 },
  disclosureDot: { color: T.accent, fontSize: 17, lineHeight: 20 },
  disclosurePointText: { flex: 1, color: T.text, fontSize: 13, lineHeight: 20, ...ff },
  disclosurePrimary: {
    backgroundColor: T.accent,
    borderRadius: 12,
    padding: 14,
    alignItems: 'center',
    marginTop: 12,
  },
  disclosurePrimaryText: { color: '#fff', fontSize: 15, fontWeight: '900', ...ff },
  disclosureSecondary: { padding: 13, alignItems: 'center' },
  disclosureSecondaryText: { color: T.accent, fontSize: 13, fontWeight: '800', ...ff },
  
  // Floating Action Button styles
  fab: {
    position: 'absolute',
    bottom: 24,
    right: 24,
    width: 56,
    height: 56,
    borderRadius: 28,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 999,
  },
  fabIcon: {
    color: '#fff',
    fontSize: 26,
    fontWeight: 'bold',
    marginTop: -2,
  },
});
};
