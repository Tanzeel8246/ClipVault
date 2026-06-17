/**
 * ClipboardMonitor.ts - ClipVault foreground monitor.
 *
 * Saves foreground clipboard changes, masks sensitive notification previews,
 * and queues Google Drive backup after successful local storage.
 */

import * as Clipboard from 'expo-clipboard';
import * as Notifications from 'expo-notifications';
import { AppState, Platform } from 'react-native';
import { Categorizer, DatabaseService, SensitiveDetector } from './CoreServices';
import { GoogleDriveBackupService } from './GoogleDriveBackup';
import { KeyboardMirror } from './KeyboardMirror';
import { ClipItem } from './types';

type MonitorOptions = {
  intervalMs?: number;
  showNotifications?: boolean;
};
type CaptureOptions = {
  force?: boolean;
};
type NewClipHandler = (clip: ClipItem) => void | Promise<void>;

let monitorInterval: ReturnType<typeof setInterval> | null = null;
let appStateSubscription: { remove: () => void } | null = null;
let lastClipContent = '';
let notificationPermissionGranted = false;
let notificationsEnabled = true;
let isChecking = false;
let lastReadBlocked = false;

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

const countWords = (value: string) => {
  const text = value.trim();
  return text ? text.split(/\s+/).length : 0;
};

async function readClipboard(): Promise<string> {
  try {
    lastReadBlocked = false;
    return (await Clipboard.getStringAsync()) ?? '';
  } catch {
    lastReadBlocked = true;
    return '';
  }
}

async function checkClipboard(onNewClip: NewClipHandler, options: CaptureOptions = {}): Promise<ClipItem | null> {
  if (isChecking) return null;
  isChecking = true;

  try {
    const current = await readClipboard();
    const trimmed = current.trim();

    if (!trimmed || (!options.force && current === lastClipContent)) return null;

    lastClipContent = current;
    const clip = await ClipboardMonitor.processNewClip(current);
    if (clip) await onNewClip(clip);
    return clip;
  } finally {
    isChecking = false;
  }
}

export class ClipboardMonitor {
  static getLastReadBlocked(): boolean {
    return lastReadBlocked;
  }

  static async requestPermissions(showNotifications = true): Promise<boolean> {
    if (!showNotifications) return false;

    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('clipboard-saves', {
        name: 'Clipboard saves',
        importance: Notifications.AndroidImportance.DEFAULT,
      });
    }

    const current = await Notifications.getPermissionsAsync();
    if (current.granted) return true;

    const requested = await Notifications.requestPermissionsAsync();
    return requested.granted;
  }

  static async startMonitoring(onNewClip: NewClipHandler, options: MonitorOptions = {}) {
    ClipboardMonitor.stopMonitoring();
    await DatabaseService.init();

    notificationsEnabled = options.showNotifications ?? true;
    notificationPermissionGranted = await ClipboardMonitor.requestPermissions(notificationsEnabled);

    const intervalMs = Math.max(500, options.intervalMs ?? 1000);
    await checkClipboard(onNewClip);

    monitorInterval = setInterval(() => {
      void checkClipboard(onNewClip);
    }, intervalMs);

    appStateSubscription = AppState.addEventListener('change', state => {
      if (state === 'active') {
        void checkClipboard(onNewClip);
        setTimeout(() => {
          void checkClipboard(onNewClip);
        }, 400);
      }
    });
  }

  static async captureCurrent(onNewClip: NewClipHandler, options: CaptureOptions = { force: true }): Promise<ClipItem | null> {
    await DatabaseService.init();
    return checkClipboard(onNewClip, options);
  }

  static stopMonitoring() {
    if (monitorInterval) {
      clearInterval(monitorInterval);
      monitorInterval = null;
    }

    appStateSubscription?.remove();
    appStateSubscription = null;
    isChecking = false;
  }

  static async processNewClip(value: string): Promise<ClipItem | null> {
    const text = value.trim();
    if (!text) return null;

    const sensitive = SensitiveDetector.detect(text);

    const clip: ClipItem = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      text,
      category: Categorizer.detect(text),
      timestamp: new Date().toISOString(),
      isFavorite: false,
      isPinned: false,
      wordCount: countWords(text),
      charCount: text.length,
      isSensitive: sensitive.isSensitive,
      sensitiveType: sensitive.sensitiveType,
    };

    const existed = await DatabaseService.hasClipText(text);
    const saved = await DatabaseService.saveClip(clip);
    if (!saved) return null;
    clip.saveStatus = existed ? 'updated' : 'saved';

    if (!clip.isSensitive) {
      await KeyboardMirror.addClip(text);
    }
    GoogleDriveBackupService.queueAutoBackup();

    if (notificationsEnabled && notificationPermissionGranted) {
      await ClipboardMonitor.showQuickNotification(clip);
    }

    return clip;
  }

  static async showQuickNotification(clip: ClipItem) {
    let preview: string;
    let title: string;

    if (clip.isSensitive) {
      title = `ClipVault private - ${clip.sensitiveType ?? 'Sensitive'}`;
      preview = 'Sensitive clip saved. Open ClipVault to view it.';
    } else {
      title = `ClipVault - ${clip.category}`;
      preview = clip.text.substring(0, 60) + (clip.text.length > 60 ? '...' : '');
    }

    await Notifications.scheduleNotificationAsync({
      content: {
        title,
        body: preview,
        data: { clipId: clip.id, isSensitive: clip.isSensitive },
      },
      trigger: null,
    });
  }
}
