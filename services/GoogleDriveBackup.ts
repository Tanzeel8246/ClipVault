import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Crypto from 'expo-crypto';
import * as Linking from 'expo-linking';
import * as SecureStore from 'expo-secure-store';
import { DatabaseService, readSettings } from './CoreServices';
import { CryptoService } from './CryptoService';
import { useClipStore } from '../store/useClipStore';
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
const SAFETY_BACKUP_FILE_NAME = 'clipvault_backup_safety_copy.json';
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

type DriveRevision = {
  id: string;
  modifiedTime?: string;
  size?: string;
};

let backupTimer: ReturnType<typeof setTimeout> | null = null;

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

const findFileByName = async (accessToken: string, fileName: string): Promise<string | null> => {
  const q = encodeURIComponent(`name='${fileName}' and trashed=false`);
  const result = await requestJson<{ files?: Array<{ id: string }> }>(
    `${DRIVE_FILES_URL}?spaces=appDataFolder&q=${q}&fields=files(id,name,modifiedTime)`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  return result.files?.[0]?.id ?? null;
};

const findBackupFile = (accessToken: string) => findFileByName(accessToken, BACKUP_FILE_NAME);
const findSafetyBackupFile = (accessToken: string) => findFileByName(accessToken, SAFETY_BACKUP_FILE_NAME);

const downloadFilePayload = async (accessToken: string, fileId: string): Promise<string> => {
  const response = await fetch(`${DRIVE_FILES_URL}/${fileId}?alt=media`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(text || `Download failed: ${response.status}`);
  return text;
};

const listFileRevisions = async (accessToken: string, fileId: string): Promise<DriveRevision[]> => {
  try {
    const result = await requestJson<{ revisions?: DriveRevision[] }>(
      `${DRIVE_FILES_URL}/${fileId}/revisions?fields=revisions(id,modifiedTime,size)`,
      { headers: { Authorization: `Bearer ${accessToken}` } },
    );
    return result.revisions ?? [];
  } catch (e) {
    console.warn('Could not list drive revisions:', e);
    return [];
  }
};

const downloadRevision = async (accessToken: string, fileId: string, revisionId: string): Promise<string> => {
  const response = await fetch(`${DRIVE_FILES_URL}/${fileId}/revisions/${revisionId}?alt=media`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Download revision failed: ${response.status}`);
  return text;
};

const decryptPayloadIfNeeded = (raw: string): string => {
  if (raw.startsWith('U2FsdGVkX1')) {
    const syncPassword = useClipStore.getState().settings.syncPassword;
    if (!syncPassword) {
      throw new Error('This backup is encrypted but no Sync Password is set.');
    }
    return CryptoService.decrypt(raw, syncPassword);
  }
  return raw;
};

const parseBackupClips = (rawPayload: string): any[] => {
  try {
    const decrypted = decryptPayloadIfNeeded(rawPayload);
    const parsed = JSON.parse(decrypted);
    if (Array.isArray(parsed?.clips)) {
      return parsed.clips;
    }
    return [];
  } catch {
    return [];
  }
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

const createDriveFile = async (accessToken: string, payload: string, fileName: string = BACKUP_FILE_NAME) => {
  const boundary = `clipvault_${Date.now()}`;
  const metadata = {
    name: fileName,
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

  /**
   * Safe backup with:
   * 1. Protection against accidental empty overwrites.
   * 2. Secondary safety copy created before modifying cloud file.
   * 3. Non-destructive merge of existing cloud clips with local clips.
   */
  static async backupNow(options?: { allowEmptyOverwrite?: boolean }): Promise<{ when: string; clipCount: number }> {
    const accessToken = await getAccessToken();
    const localClips = await DatabaseService.getAllForExport();
    const existingId = await findBackupFile(accessToken);

    let cloudClips: any[] = [];
    let cloudRaw: string | null = null;

    if (existingId) {
      try {
        cloudRaw = await downloadFilePayload(accessToken, existingId);
        cloudClips = parseBackupClips(cloudRaw);
      } catch (err) {
        console.warn('Could not inspect existing cloud backup:', err);
      }
    }

    // Guardrail: Prevent wiping an existing cloud backup when the phone is empty
    if (localClips.length === 0 && cloudClips.length > 0 && !options?.allowEmptyOverwrite) {
      throw new Error(`EMPTY_LOCAL_BACKUP_BLOCKED:${cloudClips.length}`);
    }

    // Dual Backup Safety Copy: Preserve previous cloud backup before any update
    if (cloudRaw && cloudClips.length > 0) {
      try {
        const safetyId = await findSafetyBackupFile(accessToken);
        if (safetyId) {
          await updateBackupFile(accessToken, safetyId, cloudRaw);
        } else {
          await createDriveFile(accessToken, cloudRaw, SAFETY_BACKUP_FILE_NAME);
        }
      } catch (err) {
        console.warn('Could not save safety copy:', err);
      }
    }

    // Smart Non-Destructive Merge:
    // Union of clips by ID so existing cloud clips are never wiped out on backup
    const clipMap = new Map<string, any>();
    for (const c of cloudClips) {
      if (c?.id && c?.text) clipMap.set(c.id, c);
    }
    for (const l of localClips) {
      if (l?.id && l?.text) clipMap.set(l.id, l);
    }
    const finalClips = Array.from(clipMap.values()).sort(
      (a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
    );

    // If local was missing any cloud clips, populate local storage in background
    if (cloudClips.length > 0 && localClips.length < finalClips.length) {
      void (async () => {
        for (const c of finalClips) {
          await DatabaseService.saveClip({
            ...c,
            isDeleted: false,
          }).catch(() => undefined);
        }
        await useClipStore.getState().loadClips();
      })();
    }

    const backupObj = {
      app: 'ClipVault',
      schemaVersion: 2,
      createdAt: new Date().toISOString(),
      clipCount: finalClips.length,
      clips: finalClips,
    };
    let payload = JSON.stringify(backupObj, null, 2);

    // Encrypt payload if sync password is set
    const syncPassword = useClipStore.getState().settings.syncPassword;
    if (syncPassword) {
      payload = CryptoService.encrypt(payload, syncPassword);
    }

    if (existingId) {
      await updateBackupFile(accessToken, existingId, payload);
    } else {
      await createDriveFile(accessToken, payload, BACKUP_FILE_NAME);
    }

    const when = new Date().toISOString();
    await patchSettings({ googleDriveLastBackupAt: when });
    return { when, clipCount: finalClips.length };
  }

  /**
   * Resilient restore that:
   * 1. Restores from primary backup.
   * 2. If primary is empty or missing, falls back to the safety copy.
   * 3. If safety copy is empty, inspects Google Drive file revisions history.
   * 4. Auto-heals the primary backup file once data is recovered.
   */
  static async restoreLatestBackup(): Promise<number> {
    const accessToken = await getAccessToken();
    const fileId = await findBackupFile(accessToken);

    // 1. Try primary backup file first
    if (fileId) {
      try {
        const payload = await downloadFilePayload(accessToken, fileId);
        const clips = parseBackupClips(payload);
        if (clips.length > 0) {
          const decrypted = decryptPayloadIfNeeded(payload);
          const restored = await DatabaseService.restoreFromBackupText(decrypted);
          await patchSettings({ googleDriveAutoRestoreDone: true });
          return restored;
        }
      } catch (err) {
        console.warn('Primary backup restore attempt failed:', err);
      }
    }

    // 2. If primary file had 0 clips or failed, check Safety Copy!
    const safetyId = await findSafetyBackupFile(accessToken);
    if (safetyId) {
      try {
        const safetyPayload = await downloadFilePayload(accessToken, safetyId);
        const safetyClips = parseBackupClips(safetyPayload);
        if (safetyClips.length > 0) {
          const decrypted = decryptPayloadIfNeeded(safetyPayload);
          const restored = await DatabaseService.restoreFromBackupText(decrypted);
          if (fileId) {
            // Auto-heal primary file
            await updateBackupFile(accessToken, fileId, safetyPayload).catch(() => undefined);
          }
          await patchSettings({ googleDriveAutoRestoreDone: true });
          return restored;
        }
      } catch (err) {
        console.warn('Safety copy restore attempt failed:', err);
      }
    }

    // 3. Fall back to Google Drive Revisions history!
    if (fileId) {
      try {
        const revisions = await listFileRevisions(accessToken, fileId);
        const reversed = [...revisions].reverse();
        for (const rev of reversed) {
          try {
            const revPayload = await downloadRevision(accessToken, fileId, rev.id);
            const revClips = parseBackupClips(revPayload);
            if (revClips.length > 0) {
              const decrypted = decryptPayloadIfNeeded(revPayload);
              const restored = await DatabaseService.restoreFromBackupText(decrypted);
              // Auto-heal primary file with recovered revision
              await updateBackupFile(accessToken, fileId, revPayload).catch(() => undefined);
              await patchSettings({ googleDriveAutoRestoreDone: true });
              return restored;
            }
          } catch {}
        }
      } catch (err) {
        console.warn('Drive revisions restore attempt failed:', err);
      }
    }

    return 0;
  }

  /**
   * Deep recovery scanner: scans all available safety copies and Drive revisions,
   * merges all clips, restores them to local database, and heals cloud backup.
   */
  static async recoverFromHistory(): Promise<{ restored: number; totalVersionsFound: number }> {
    const accessToken = await getAccessToken();
    const fileId = await findBackupFile(accessToken);
    const safetyId = await findSafetyBackupFile(accessToken);

    const allDiscoveredClips = new Map<string, any>();
    let totalVersionsFound = 0;

    // Inspect Safety Copy
    if (safetyId) {
      try {
        const sPayload = await downloadFilePayload(accessToken, safetyId);
        const sClips = parseBackupClips(sPayload);
        if (sClips.length > 0) {
          totalVersionsFound++;
          for (const c of sClips) {
            if (c?.id && c?.text) allDiscoveredClips.set(c.id, c);
          }
        }
      } catch {}
    }

    // Inspect Revisions
    if (fileId) {
      try {
        const revisions = await listFileRevisions(accessToken, fileId);
        for (const rev of revisions) {
          try {
            const revPayload = await downloadRevision(accessToken, fileId, rev.id);
            const revClips = parseBackupClips(revPayload);
            if (revClips.length > 0) {
              totalVersionsFound++;
              for (const c of revClips) {
                if (c?.id && c?.text) allDiscoveredClips.set(c.id, c);
              }
            }
          } catch {}
        }
      } catch {}
    }

    if (allDiscoveredClips.size === 0) {
      return { restored: 0, totalVersionsFound: 0 };
    }

    let restored = 0;
    for (const item of allDiscoveredClips.values()) {
      const ok = await DatabaseService.saveClip({
        id: String(item.id || `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
        text: String(item.text ?? '').trim(),
        category: String(item.category || 'Text'),
        timestamp: String(item.timestamp || new Date().toISOString()),
        isFavorite: !!item.isFavorite,
        isPinned: !!item.isPinned,
        wordCount: Number(item.wordCount ?? 0),
        charCount: Number(item.charCount ?? 0),
        tags: typeof item.tags === 'string' ? item.tags : '[]',
        isDeleted: false,
        isSensitive: !!item.isSensitive,
        sensitiveType: item.sensitiveType ?? null,
      });
      if (ok) restored++;
    }

    if (restored > 0) {
      await useClipStore.getState().loadClips();
      // Auto-heal primary cloud backup file
      const backupObj = {
        app: 'ClipVault',
        schemaVersion: 2,
        createdAt: new Date().toISOString(),
        clipCount: allDiscoveredClips.size,
        clips: Array.from(allDiscoveredClips.values()),
      };
      let healPayload = JSON.stringify(backupObj, null, 2);
      const syncPassword = useClipStore.getState().settings.syncPassword;
      if (syncPassword) {
        healPayload = CryptoService.encrypt(healPayload, syncPassword);
      }
      if (fileId) {
        await updateBackupFile(accessToken, fileId, healPayload).catch(() => undefined);
      } else {
        await createDriveFile(accessToken, healPayload, BACKUP_FILE_NAME).catch(() => undefined);
      }
      await patchSettings({ googleDriveAutoRestoreDone: true });
    }

    return { restored, totalVersionsFound };
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
        // Never auto-backup if local storage has 0 clips
        const localClips = await DatabaseService.getAllForExport();
        if (localClips.length === 0) return;
        await GoogleDriveBackupService.backupNow();
      })().catch(() => undefined);
    }, 1000); // 1 second debounce
  }
}
