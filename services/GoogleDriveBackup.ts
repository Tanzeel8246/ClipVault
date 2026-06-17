import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';
import { Linking } from 'react-native';
import { DatabaseService } from './CoreServices';
import { DEFAULT_SETTINGS, SETTINGS_KEY } from './types';

const GOOGLE_CLIENT_ID = '591284919475-m2nalmiep2ubg7dd4rrjusjgoo7rcmce.apps.googleusercontent.com';
const getRedirectUri = () => {
  const prefix = GOOGLE_CLIENT_ID.split('.')[0];
  return `com.googleusercontent.apps.${prefix}:/oauth2redirect`;
};
const GOOGLE_REDIRECT_URI = getRedirectUri();
const DRIVE_SCOPE = 'openid email profile https://www.googleapis.com/auth/drive.appdata';
const AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const DRIVE_FILES_URL = 'https://www.googleapis.com/drive/v3/files';
const DRIVE_UPLOAD_URL = 'https://www.googleapis.com/upload/drive/v3/files';
const BACKUP_FILE_NAME = 'clipvault_backup.json';
const TOKEN_KEY = 'clipvault_google_drive_tokens';
const AUTH_STATE_KEY = 'clipvault_google_drive_auth_state';

type TokenSet = {
  accessToken: string;
  refreshToken?: string;
  expiresAt: number;
  email?: string;
};

type AuthState = {
  state: string;
  verifier: string;
};

let backupTimer: ReturnType<typeof setTimeout> | null = null;

const readSettings = async () => {
  try {
    const saved = await AsyncStorage.getItem(SETTINGS_KEY);
    return saved ? { ...DEFAULT_SETTINGS, ...JSON.parse(saved) } : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
};

const patchSettings = async (patch: Partial<typeof DEFAULT_SETTINGS>) => {
  const current = await readSettings();
  await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify({ ...current, ...patch }));
};

const bytesToHex = (bytes: Uint8Array) =>
  Array.from(bytes).map(byte => byte.toString(16).padStart(2, '0')).join('');

const base64Url = (value: string) => value.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');

const encodeForm = (values: Record<string, string>) =>
  Object.entries(values)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&');

const parseQuery = (url: string): Record<string, string> => {
  const queryStart = url.indexOf('?');
  const hashStart = url.indexOf('#');
  const start = queryStart >= 0 ? queryStart + 1 : hashStart >= 0 ? hashStart + 1 : -1;
  if (start < 0) return {};
  const raw = url.slice(start);
  return raw.split('&').reduce<Record<string, string>>((acc, part) => {
    const [key, value = ''] = part.split('=');
    if (key) acc[decodeURIComponent(key)] = decodeURIComponent(value.replace(/\+/g, ' '));
    return acc;
  }, {});
};

const saveTokens = async (tokens: TokenSet) => {
  await SecureStore.setItemAsync(TOKEN_KEY, JSON.stringify(tokens));
};

const readTokens = async (): Promise<TokenSet | null> => {
  try {
    const raw = await SecureStore.getItemAsync(TOKEN_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
};

const requestJson = async <T>(url: string, options: RequestInit): Promise<T> => {
  const response = await fetch(url, options);
  const body = await response.text();
  if (!response.ok) {
    throw new Error(body || `Request failed: ${response.status}`);
  }
  return body ? JSON.parse(body) as T : {} as T;
};

const getAccessToken = async (): Promise<string> => {
  const tokens = await readTokens();
  if (!tokens?.accessToken) throw new Error('Google Drive is not connected.');

  if (tokens.expiresAt - 60_000 > Date.now()) return tokens.accessToken;
  if (!tokens.refreshToken) throw new Error('Google session expired. Please sign in again.');

  const refreshed = await requestJson<any>(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: encodeForm({
      client_id: GOOGLE_CLIENT_ID,
      refresh_token: tokens.refreshToken,
      grant_type: 'refresh_token',
    }),
  });

  const next: TokenSet = {
    accessToken: String(refreshed.access_token),
    refreshToken: tokens.refreshToken,
    expiresAt: Date.now() + Number(refreshed.expires_in ?? 3600) * 1000,
    email: tokens.email,
  };
  await saveTokens(next);
  return next.accessToken;
};

const findBackupFile = async (accessToken: string): Promise<string | null> => {
  const q = encodeURIComponent(`name='${BACKUP_FILE_NAME}' and trashed=false`);
  const result = await requestJson<{ files?: Array<{ id: string }> }>(
    `${DRIVE_FILES_URL}?spaces=appDataFolder&q=${q}&fields=files(id,name,modifiedTime)`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  return result.files?.[0]?.id ?? null;
};

const updateBackupFile = async (accessToken: string, fileId: string, payload: string) => {
  await requestJson(`${DRIVE_UPLOAD_URL}/${fileId}?uploadType=media`, {
    method: 'PATCH',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: payload,
  });
};

const createBackupFile = async (accessToken: string, payload: string) => {
  const boundary = `clipvault_${Date.now()}`;
  const metadata = {
    name: BACKUP_FILE_NAME,
    parents: ['appDataFolder'],
    mimeType: 'application/json',
    appProperties: { app: 'ClipVault', schema: '2' },
  };
  const body = [
    `--${boundary}`,
    'Content-Type: application/json; charset=UTF-8',
    '',
    JSON.stringify(metadata),
    `--${boundary}`,
    'Content-Type: application/json',
    '',
    payload,
    `--${boundary}--`,
    '',
  ].join('\r\n');

  await requestJson(`${DRIVE_UPLOAD_URL}?uploadType=multipart&fields=id`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': `multipart/related; boundary=${boundary}`,
    },
    body,
  });
};

export class GoogleDriveBackupService {
  static pendingRedirectUrl: string | null = null;

  static isConfigured() {
    return GOOGLE_CLIENT_ID.trim().length > 0;
  }

  static async getAccountEmail(): Promise<string> {
    const tokens = await readTokens();
    return tokens?.email ?? '';
  }

  static async startSignIn(): Promise<void> {
    if (!GoogleDriveBackupService.isConfigured()) {
      throw new Error('Google OAuth client ID is not configured yet.');
    }

    const verifier = bytesToHex(await Crypto.getRandomBytesAsync(32));
    const state = Crypto.randomUUID();
    const challenge = base64Url(await Crypto.digestStringAsync(
      Crypto.CryptoDigestAlgorithm.SHA256,
      verifier,
      { encoding: Crypto.CryptoEncoding.BASE64 },
    ));

    const authState: AuthState = { state, verifier };
    await SecureStore.setItemAsync(AUTH_STATE_KEY, JSON.stringify(authState));

    const url = `${AUTH_URL}?${encodeForm({
      client_id: GOOGLE_CLIENT_ID,
      redirect_uri: GOOGLE_REDIRECT_URI,
      response_type: 'code',
      scope: DRIVE_SCOPE,
      access_type: 'offline',
      prompt: 'consent',
      state,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    })}`;

    await Linking.openURL(url);
  }

  static async completeSignInFromUrl(url: string): Promise<{ handled: boolean; email?: string }> {
    if (!url.includes('oauth2redirect')) return { handled: false };

    const query = parseQuery(url);
    if (query.error) throw new Error(query.error);
    if (!query.code) throw new Error('Google sign-in did not return a code.');

    const rawState = await SecureStore.getItemAsync(AUTH_STATE_KEY);
    const authState = rawState ? JSON.parse(rawState) as AuthState : null;
    if (!authState || authState.state !== query.state) {
      throw new Error('Google sign-in state check failed.');
    }

    const tokenResponse = await requestJson<any>(TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: encodeForm({
        client_id: GOOGLE_CLIENT_ID,
        code: query.code,
        code_verifier: authState.verifier,
        redirect_uri: GOOGLE_REDIRECT_URI,
        grant_type: 'authorization_code',
      }),
    });

    const accessToken = String(tokenResponse.access_token);
    const profile = await requestJson<{ email?: string }>('https://www.googleapis.com/oauth2/v3/userinfo', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const email = profile.email ?? '';

    await saveTokens({
      accessToken,
      refreshToken: tokenResponse.refresh_token,
      expiresAt: Date.now() + Number(tokenResponse.expires_in ?? 3600) * 1000,
      email,
    });
    await SecureStore.deleteItemAsync(AUTH_STATE_KEY);
    await patchSettings({
      googleDriveBackupEnabled: true,
      googleDriveAccountEmail: email,
      googleDriveAutoRestoreDone: false,
    });

    return { handled: true, email };
  }

  static async signOut() {
    await SecureStore.deleteItemAsync(TOKEN_KEY);
    await SecureStore.deleteItemAsync(AUTH_STATE_KEY);
    await patchSettings({
      googleDriveBackupEnabled: false,
      googleDriveAccountEmail: '',
      googleDriveLastBackupAt: '',
      googleDriveAutoRestoreDone: false,
    });
  }

  static async backupNow(): Promise<string> {
    const accessToken = await getAccessToken();
    const payload = await DatabaseService.createBackupPayload();
    const existingId = await findBackupFile(accessToken);

    if (existingId) await updateBackupFile(accessToken, existingId, payload);
    else await createBackupFile(accessToken, payload);

    const when = new Date().toISOString();
    await patchSettings({ googleDriveLastBackupAt: when });
    return when;
  }

  static async restoreLatestBackup(): Promise<number> {
    const accessToken = await getAccessToken();
    const fileId = await findBackupFile(accessToken);
    if (!fileId) return 0;

    const response = await fetch(`${DRIVE_FILES_URL}/${fileId}?alt=media`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const payload = await response.text();
    if (!response.ok) throw new Error(payload || `Restore failed: ${response.status}`);

    const restored = await DatabaseService.restoreFromBackupText(payload);
    await patchSettings({ googleDriveAutoRestoreDone: true });
    return restored;
  }

  static async restoreOnceAfterSignIn(): Promise<number> {
    const settings = await readSettings();
    if (!settings.googleDriveBackupEnabled || settings.googleDriveAutoRestoreDone) return 0;
    return GoogleDriveBackupService.restoreLatestBackup();
  }

  static queueAutoBackup() {
    if (backupTimer) clearTimeout(backupTimer);
    backupTimer = setTimeout(() => {
      void (async () => {
        const settings = await readSettings();
        if (!settings.googleDriveBackupEnabled) return;
        const tokens = await readTokens();
        if (!tokens?.refreshToken && !tokens?.accessToken) return;
        await GoogleDriveBackupService.backupNow();
      })().catch(() => undefined);
    }, 1000); // 1 second debounce for aggressive real-time sync
  }
}
