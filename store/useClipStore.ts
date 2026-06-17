/**
 * useClipStore — ClipVault Global State (Zustand)
 *
 * تمام اسکرینز یہاں سے ڈیٹا لیتی ہیں۔
 * HomeScreen اور SettingsScreen دونوں ایک ہی اسٹیٹ شیئر کرتی ہیں۔
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import * as FileSystem from 'expo-file-system';
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
  error: string;

  // Toast
  toastMessage: string;
  toastVisible: boolean;

  // Actions — Settings
  loadSettings: () => Promise<void>;
  updateSetting: <K extends keyof ClipSettings>(key: K, value: ClipSettings[K]) => Promise<void>;

  // Actions — Clips
  loadClips: () => Promise<void>;
  refreshClips: () => Promise<void>;
  setSearch: (search: string) => void;
  setCategory: (category: string) => void;
  favoriteClip: (id: string, value: boolean) => Promise<void>;
  pinClip: (id: string, value: boolean) => Promise<void>;
  deleteClip: (id: string) => Promise<void>;
  clearAll: () => Promise<void>;
  clearSensitive: () => Promise<number>;
  addClip: (text: string) => Promise<void>;
  updateClipText: (id: string, text: string) => Promise<void>;
  toggleSensitive: (id: string, value: boolean) => Promise<void>;

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
  error: '',
  toastMessage: '',
  toastVisible: false,

  // ── Settings ──────────────────────────────────────────────
  loadSettings: async () => {
    try {
      const saved = await AsyncStorage.getItem(SETTINGS_KEY);
      if (saved) {
        set({ settings: { ...DEFAULT_SETTINGS, ...JSON.parse(saved) } });
      }
    } catch {
      set({ settings: DEFAULT_SETTINGS });
    }
  },

  updateSetting: async (key, value) => {
    const updated = { ...get().settings, [key]: value };
    set({ settings: updated });
    await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify(updated));
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
    set({ isLoading: true, error: '' });
    try {
      const [data, stats] = await Promise.all([
        DatabaseService.getClips({ search, category: activeCategory }),
        DatabaseService.getStats(),
      ]);
      const enrichedData = data.map(clip => LocalAIProcessor.enrichClip(clip));
      set({ clips: enrichedData, stats, isLoading: false });
    } catch (err) {
      set({ error: err instanceof Error ? err.message : 'Load failed', isLoading: false });
    }
  },

  refreshClips: async () => {
    const { search, activeCategory } = get();
    set({ isRefreshing: true, error: '' });
    try {
      const [data, stats] = await Promise.all([
        DatabaseService.getClips({ search, category: activeCategory }),
        DatabaseService.getStats(),
      ]);
      const enrichedData = data.map(clip => LocalAIProcessor.enrichClip(clip));
      set({ clips: enrichedData, stats, isRefreshing: false });
    } catch (err) {
      set({ error: err instanceof Error ? err.message : 'Refresh failed', isRefreshing: false });
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
    set({ clips: [] });
    await get().loadStats();
    GoogleDriveBackupService.queueAutoBackup();
  },

  clearSensitive: async () => {
    const count = await DatabaseService.clearSensitive();
    // Reload to reflect deletions
    await get().loadClips();
    return count;
  },

  addClip: async (text: string) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const category = Categorizer.detect(text);
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

  updateClipText: async (id: string, text: string) => {
    await DatabaseService.updateClipText(id, text);
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
