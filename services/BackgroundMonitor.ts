import { NativeModules, Platform } from 'react-native';

type AutoClipboardMonitorModule = {
  start: (durationMinutes: number) => Promise<boolean>;
  stop: () => Promise<boolean>;
  openKeyboardSettings: () => Promise<boolean>;
  showKeyboardPicker: () => Promise<boolean>;
  isKeyboardEnabled?: () => Promise<boolean>;
  isKeyboardSelected?: () => Promise<boolean>;
};

const nativeMonitor = NativeModules.AutoClipboardMonitor as AutoClipboardMonitorModule | undefined;

export async function startBackgroundMonitor(durationMinutes: number): Promise<boolean> {
  if (Platform.OS !== 'android' || !nativeMonitor?.start) return false;
  return nativeMonitor.start(durationMinutes);
}

export async function stopBackgroundMonitor(): Promise<boolean> {
  if (Platform.OS !== 'android' || !nativeMonitor?.stop) return false;
  return nativeMonitor.stop();
}

export async function openKeyboardSettings(): Promise<boolean> {
  if (Platform.OS !== 'android' || !nativeMonitor?.openKeyboardSettings) return false;
  return nativeMonitor.openKeyboardSettings();
}

export async function showKeyboardPicker(): Promise<boolean> {
  if (Platform.OS !== 'android' || !nativeMonitor?.showKeyboardPicker) return false;
  return nativeMonitor.showKeyboardPicker();
}

export async function isKeyboardEnabled(): Promise<boolean> {
  if (Platform.OS !== 'android' || !nativeMonitor?.isKeyboardEnabled) return false;
  return nativeMonitor.isKeyboardEnabled();
}

export async function isKeyboardSelected(): Promise<boolean> {
  if (Platform.OS !== 'android' || !nativeMonitor?.isKeyboardSelected) return false;
  return nativeMonitor.isKeyboardSelected();
}
