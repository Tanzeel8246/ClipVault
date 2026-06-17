export type SensitiveType = 'otp' | 'password' | 'card' | 'phone' | 'cnic' | 'token';

export interface SensitiveDetection {
  isSensitive: boolean;
  sensitiveType?: SensitiveType;
}

export interface ClipItem {
  id: string;
  text: string;
  category: string;
  timestamp: string;
  isFavorite: boolean;
  isPinned: boolean;
  wordCount: number;
  charCount: number;
  tags?: string;
  isDeleted?: boolean;
  isSensitive?: boolean;
  sensitiveType?: SensitiveType;
  saveStatus?: 'saved' | 'updated';
  extractedEntities?: string[];
  detectedLanguage?: string;
  summary?: string;
}

export interface ClipStats {
  total: number;
  today: number;
  favorites: number;
  sensitive: number;
  categories: Record<string, number>;
}

export type AutoDeleteSetting = 'never' | '7d' | '30d' | '90d';
export type ThemeSetting = 'CLASSIC_DARK' | 'SOFT_IVORY' | 'OCEAN_BLUE' | 'FOREST_GREEN' | 'NEON_GLASS' | 'MIDNIGHT_GOLD' | 'GRAPHITE' | 'ROYAL_PURPLE' | 'SUNSET_ORANGE' | 'ROSE_PETAL';
export type MaxClipsSetting = '500' | '1000' | 'unlimited';
export type AutoMonitorMinutes = 0 | 15 | 30 | 60 | 120;  // 0 = unlimited
export type SensitiveExpiry = 'never' | '30s' | '5m' | 'session';
export type AppLanguage = 'ur' | 'en';

export interface ClipSettings {
  monitoringEnabled: boolean;
  backgroundMonitorEnabled: boolean;
  autoMonitorMinutes: AutoMonitorMinutes;
  showNotifications: boolean;
  biometricLock: boolean;
  pinLockEnabled: boolean;
  autoDelete: AutoDeleteSetting;
  theme: ThemeSetting;
  maxClips: MaxClipsSetting;
  maskSensitive: boolean;
  sensitiveExpiry: SensitiveExpiry;
  appLanguage: AppLanguage;
  privacyDisclosureAccepted: boolean;
  googleDriveBackupEnabled: boolean;
  googleDriveAccountEmail: string;
  googleDriveLastBackupAt: string;
  googleDriveAutoRestoreDone: boolean;
  keyboardHeight: number;
  keyboardFontSize: number;
  geminiApiKey: string;
}

export const SETTINGS_KEY = 'clipvault_settings';

export const DEFAULT_SETTINGS: ClipSettings = {
  monitoringEnabled: true,
  backgroundMonitorEnabled: true,
  autoMonitorMinutes: 30,
  showNotifications: true,
  biometricLock: false,
  pinLockEnabled: false,
  autoDelete: 'never',
  theme: 'CLASSIC_DARK',
  maxClips: '1000',
  maskSensitive: true,
  sensitiveExpiry: 'never',
  appLanguage: 'ur',
  privacyDisclosureAccepted: false,
  googleDriveBackupEnabled: false,
  googleDriveAccountEmail: '',
  googleDriveLastBackupAt: '',
  googleDriveAutoRestoreDone: false,
  keyboardHeight: 1.0,
  keyboardFontSize: 1.0,
  geminiApiKey: '',
};
