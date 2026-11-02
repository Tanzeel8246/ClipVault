/**
 * useClipStore — ClipVault Global State (Zustand)
 *
 * تمام اسکرینز یہاں سے ڈیٹا لیتی ہیں۔
 * HomeScreen اور SettingsScreen دونوں ایک ہی اسٹیٹ شیئر کرتی ہیں۔
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import * as FileSystem from 'expo-file-system';
import * as SecureStore from 'expo-secure-store';
import { create } from 'zustand';
import { NativeModules } from 'react-native';
import { DatabaseService, SensitiveDetector, LocalAIProcessor, Categorizer } from '../services/CoreServices';
import { GoogleDriveBackupService } from '../services/GoogleDriveBackup';
import { ClipItem, ClipSettings, ClipStats, DEFAULT_SETTINGS, SETTINGS_KEY } from '../services/types';

// ─────────────────────────────────────────────────────────────
// State Types
// ─────────────────────────────────────────────────────────────
interface ClipStoreState {
  // Data
  clips: ClipItem[];
  stats: ClipStats;
  settings: ClipSettings;

  // UI Filters
  search: string;
  activeCategory: string;

  // Loading
  isLoading: boolean;
  isRefreshing: boolean;
  isLoadingMore: boolean;
  hasMore: boolean;
  error: string;

  // Toast
  toastMessage: string;
  toastVisible: boolean;

  // Actions — Settings
  loadSettings: () => Promise<void>;
  updateSetting: <K extends keyof ClipSettings>(key: K, value: ClipSettings[K]) => Promise<void>;

  // Actions — Clips
  loadClips: () => Promise<void>;
  loadMoreClips: () => Promise<void>;
  refreshClips: () => Promise<void>;
  mergeSelectedClips: (ids: string[]) => Promise<void>;
  setSearch: (search: string) => void;
  setCategory: (category: string) => void;
  favoriteClip: (id: string, value: boolean) => Promise<void>;
  pinClip: (id: string, value: boolean) => Promise<void>;
  deleteClip: (id: string) => Promise<void>;
  clearAll: () => Promise<void>;
  clearSensitive: () => Promise<number>;
  addClip: (text: string, forceCategory?: string) => Promise<void>;
  updateClipText: (id: string, text: string, forceCategory?: string) => Promise<void>;
  toggleSensitive: (id: string, value: boolean) => Promise<void>;
  bulkDelete: (ids: string[]) => Promise<void>;
  bulkPin: (ids: string[], value: boolean) => Promise<void>;
  bulkFavorite: (ids: string[], value: boolean) => Promise<void>;

  // Actions — Toast
  showToast: (message: string) => void;

  // Actions — Stats
  loadStats: () => Promise<void>;
}

// ─────────────────────────────────────────────────────────────
// Default Stats
// ─────────────────────────────────────────────────────────────
const emptyStats: ClipStats = {
  total: 0,
  today: 0,
  favorites: 0,
  sensitive: 0,
  categories: {},
};

// ─────────────────────────────────────────────────────────────
// Store
// ─────────────────────────────────────────────────────────────
export const useClipStore = create<ClipStoreState>((set, get) => ({
  clips: [],
  stats: emptyStats,
  settings: DEFAULT_SETTINGS,
  search: '',
  activeCategory: 'All',
  isLoading: false,
  isRefreshing: false,
  isLoadingMore: false,
  hasMore: true,
  error: '',
  toastMessage: '',
  toastVisible: false,

  // ── Settings ──────────────────────────────────────────────
  loadSettings: async () => {
    try {
      const saved = await AsyncStorage.getItem(SETTINGS_KEY);
      let parsed = saved ? JSON.parse(saved) : {};
      
      let needsMigration = false;
      let secureApiKey = '';
      let securePinHash = '';
      let secureSyncPassword = '';

      // SecureStore migration for geminiApiKey
      if (parsed.geminiApiKey) {
        secureApiKey = parsed.geminiApiKey;
        await SecureStore.setItemAsync('secure_geminiApiKey', secureApiKey);
        delete parsed.geminiApiKey;
        needsMigration = true;
      } else {
        secureApiKey = (await SecureStore.getItemAsync('secure_geminiApiKey')) || '';
      }

      // SecureStore migration for pinHash
      if (parsed.pinHash) {
        securePinHash = parsed.pinHash;
        await SecureStore.setItemAsync('secure_pinHash', securePinHash);
        delete parsed.pinHash;
        needsMigration = true;
      } else {
        securePinHash = (await SecureStore.getItemAsync('secure_pinHash')) || '';
      }

      // SecureStore migration for syncPassword
      if (parsed.syncPassword) {
        secureSyncPassword = parsed.syncPassword;
        await SecureStore.setItemAsync('secure_syncPassword', secureSyncPassword);
        delete parsed.syncPassword;
        needsMigration = true;
      } else {
        secureSyncPassword = (await SecureStore.getItemAsync('secure_syncPassword')) || '';
      }

      if (needsMigration) {
        await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify(parsed));
      }

      const settingsState = { 
        ...DEFAULT_SETTINGS, 
        ...parsed, 
        geminiApiKey: secureApiKey,
        pinHash: securePinHash,
        syncPassword: secureSyncPassword
      };

      set({ settings: settingsState });
    } catch {
      set({ settings: DEFAULT_SETTINGS });
    }
  },

  updateSetting: async (key, value) => {
    const updated = { ...get().settings, [key]: value };
    set({ settings: updated });
    
    if (key === 'geminiApiKey' || key === 'pinHash' || key === 'syncPassword') {
      if (value) {
        await SecureStore.setItemAsync(`secure_${key}`, value as string);
      } else {
        await SecureStore.deleteItemAsync(`secure_${key}`);
      }
    }

    // Save non-secure settings to AsyncStorage
    const { geminiApiKey, pinHash, syncPassword, ...safeSettings } = updated;
    await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify(safeSettings));
    
    if (key === 'theme' && FileSystem.documentDirectory) {
      await FileSystem.writeAsStringAsync(FileSystem.documentDirectory + 'theme.txt', value as string);
    }
    if (key === 'keyboardHeight' || key === 'keyboardFontSize') {
      try {
        NativeModules.AutoClipboardMonitor.setKeyboardPrefs(updated.keyboardHeight, updated.keyboardFontSize);
      } catch (e) { /* ignore */ }
    }
    await DatabaseService.applyMaintenance(updated);
    await get().loadStats();
  },

  // ── Clips ─────────────────────────────────────────────────
  loadClips: async () => {
    const { search, activeCategory } = get();
    set({ isLoading: true, error: '', hasMore: true });
    try {
      const [data, stats] = await Promise.all([
        DatabaseService.getClips({ search, category: activeCategory, limit: 60, offset: 0 }),
        DatabaseService.getStats(),
      ]);
      const enrichedData = data.map(clip => LocalAIProcessor.enrichClip(clip));
      set({ clips: enrichedData, stats, isLoading: false, hasMore: data.length >= 60 });
    } catch (err) {
      set({ error: err instanceof Error ? err.message : 'Load failed', isLoading: false });
    }
  },

  loadMoreClips: async () => {
    const { search, activeCategory, clips, isLoadingMore, hasMore, isLoading } = get();
    if (isLoadingMore || !hasMore || isLoading) return;

    set({ isLoadingMore: true });
    try {
      const moreData = await DatabaseService.getClips({
        search,
        category: activeCategory,
        limit: 60,
        offset: clips.length,
      });

      if (moreData.length === 0) {
        set({ isLoadingMore: false, hasMore: false });
        return;
      }

      const existingIds = new Set(clips.map(c => c.id));
      const newItems = moreData
        .filter(c => !existingIds.has(c.id))
        .map(clip => LocalAIProcessor.enrichClip(clip));

      set({
        clips: [...clips, ...newItems],
        isLoadingMore: false,
        hasMore: moreData.length >= 60,
      });
    } catch {
      set({ isLoadingMore: false });
    }
  },

  refreshClips: async () => {
    const { search, activeCategory } = get();
    set({ isRefreshing: true, error: '', hasMore: true });
    try {
      const [data, stats] = await Promise.all([
        DatabaseService.getClips({ search, category: activeCategory, limit: 60, offset: 0 }),
        DatabaseService.getStats(),
      ]);
      const enrichedData = data.map(clip => LocalAIProcessor.enrichClip(clip));
      set({ clips: enrichedData, stats, isRefreshing: false, hasMore: data.length >= 60 });
    } catch (err) {
      set({ error: err instanceof Error ? err.message : 'Refresh failed', isRefreshing: false });
    }
  },

  mergeSelectedClips: async (ids: string[]) => {
    if (!ids || ids.length < 2) return;
    const { clips, addClip } = get();
    const selectedClips = clips.filter(c => ids.includes(c.id));
    if (selectedClips.length === 0) return;
    const combinedText = selectedClips.map(c => c.text.trim()).filter(Boolean).join('\n\n───\n\n');
    if (combinedText.trim()) {
      await addClip(combinedText);
    }
  },

  setSearch: (search) => {
    set({ search });
    // Debounced auto reload is handled in the component via useEffect
  },

  setCategory: (category) => {
    set({ activeCategory: category });
  },

  favoriteClip: async (id, value) => {
    // Optimistic update
    set(state => ({
      clips: state.clips.map(c => (c.id === id ? { ...c, isFavorite: value } : c)),
    }));
    await DatabaseService.toggleFavorite(id, value);
    await get().loadStats();
  },

  pinClip: async (id, value) => {
    set(state => ({
      clips: state.clips.map(c => (c.id === id ? { ...c, isPinned: value } : c)),
    }));
    await DatabaseService.togglePin(id, value);
    // Re-sort since pinned items go to top
    await get().loadClips();
  },

  deleteClip: async (id) => {
    set(state => ({
      clips: state.clips.filter(c => c.id !== id),
    }));
    await DatabaseService.deleteClip(id);
    await get().loadStats();
    GoogleDriveBackupService.queueAutoBackup();
  },

  clearAll: async () => {
    await DatabaseService.clearAll();
    await get().loadClips();
    GoogleDriveBackupService.queueAutoBackup();
  },

  clearSensitive: async () => {
    const deletedCount = await DatabaseService.clearSensitive();
    await get().loadClips();
    GoogleDriveBackupService.queueAutoBackup();
    return deletedCount;
  },

  bulkDelete: async (ids: string[]) => {
    await DatabaseService.bulkDelete(ids);
    await get().loadClips();
    GoogleDriveBackupService.queueAutoBackup();
  },
  
  bulkPin: async (ids: string[], value: boolean) => {
    await DatabaseService.bulkPin(ids, value);
    await get().loadClips();
  },
  
  bulkFavorite: async (ids: string[], value: boolean) => {
    await DatabaseService.bulkFavorite(ids, value);
    await get().loadClips();
  },

  addClip: async (text: string, forceCategory?: string) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const category = forceCategory || Categorizer.detect(text);
    const detection = SensitiveDetector.detect(text);
    const clip: ClipItem = {
      id,
      text,
      category,
      timestamp: new Date().toISOString(),
      isFavorite: false,
      isPinned: false,
      wordCount: text.split(/\s+/).filter(Boolean).length,
      charCount: text.length,
      isSensitive: detection.isSensitive,
      sensitiveType: detection.sensitiveType,
    };
    await DatabaseService.saveClip(clip);
    await get().loadClips();
    GoogleDriveBackupService.queueAutoBackup();
  },

  updateClipText: async (id: string, newText: string, forceCategory?: string) => {
    const category = forceCategory || Categorizer.detect(newText);
    const detection = SensitiveDetector.detect(newText);
    
    await DatabaseService.updateClipText(id, newText, category, detection.isSensitive);
    await get().loadClips();
    GoogleDriveBackupService.queueAutoBackup();
  },

  toggleSensitive: async (id: string, value: boolean) => {
    set(state => ({
      clips: state.clips.map(c =>
        c.id === id
          ? { ...c, isSensitive: value, sensitiveType: value ? c.sensitiveType : undefined }
          : c
      ),
    }));
    await DatabaseService.toggleSensitive(id, value);
    await get().loadClips();
    GoogleDriveBackupService.queueAutoBackup();
  },

  // ── Toast ─────────────────────────────────────────────────
  showToast: (message) => {
    set({ toastMessage: message, toastVisible: true });
    setTimeout(() => set({ toastVisible: false }), 2200);
  },

  // ── Stats ─────────────────────────────────────────────────
  loadStats: async () => {
    try {
      const stats = await DatabaseService.getStats();
      set({ stats });
    } catch { /* silent */ }
  },
}));
