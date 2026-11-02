/**
 * ClipVault — Google Drive Sync Service for Chrome Extension
 * 
 * - Communicates with Google APIs via OAuth 2.0 Web Auth Flow
 * - Uploads and downloads clipvault_backup.json to isolated appDataFolder
 */

const DEFAULT_CLIENT_ID = '591284919475-dk84mh02u26dmahpma879rdv17mf8rmv.apps.googleusercontent.com';
const BACKUP_FILE_NAME = 'clipvault_backup.json';
const DRIVE_FILES_URL = 'https://www.googleapis.com/drive/v3/files';
const DRIVE_UPLOAD_URL = 'https://www.googleapis.com/upload/drive/v3/files';

export class GoogleDriveSyncService {
  
  static async getClientId() {
    return DEFAULT_CLIENT_ID;
  }

  static async setClientId(clientId) {
    return Promise.resolve();
  }

  static async getAccessToken(interactive = false) {
    return new Promise((resolve, reject) => {
      chrome.storage.local.get(['googleToken', 'googleTokenExpiry'], async (res) => {
        const now = Date.now();
        if (res.googleToken && res.googleTokenExpiry && now < res.googleTokenExpiry) {
          resolve(res.googleToken);
          return;
        }

        if (!interactive) {
          try {
            const token = await this.startWebAuthFlow(false);
            resolve(token);
          } catch(err) {
            resolve(null);
          }
          return;
        }

        try {
          const token = await this.startWebAuthFlow(true);
          resolve(token);
        } catch (err) {
          reject(err);
        }
      });
    });
  }

  static async startWebAuthFlow(interactive = true) {
    const clientId = await this.getClientId();
    const redirectUri = `https://${chrome.runtime.id}.chromiumapp.org/`;
    const scopes = 'https://www.googleapis.com/auth/drive.appdata https://www.googleapis.com/auth/userinfo.email';
    
    const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?client_id=${clientId}&redirect_uri=${encodeURIComponent(redirectUri)}&response_type=token&scope=${encodeURIComponent(scopes)}` + (!interactive ? '&prompt=none' : '');

    return new Promise((resolve, reject) => {
      chrome.identity.launchWebAuthFlow({ url: authUrl, interactive: interactive }, async (responseUrl) => {
        if (chrome.runtime.lastError || !responseUrl) {
          reject(new Error(chrome.runtime.lastError ? chrome.runtime.lastError.message : 'Login cancelled.'));
          return;
        }

        // Parse access_token and expires_in from redirect URL hash
        const urlParams = new URLSearchParams(responseUrl.split('#')[1]);
        const accessToken = urlParams.get('access_token');
        const expiresIn = urlParams.get('expires_in') || '3600';

        if (!accessToken) {
          reject(new Error('Failed to obtain Google auth token.'));
          return;
        }

        // Get email of account
        try {
          const profileRes = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
            headers: { Authorization: `Bearer ${accessToken}` }
          });
          const profile = await profileRes.json();
          const email = profile.email || 'Google Account';

          const expiryTime = Date.now() + parseInt(expiresIn) * 1000;
          
          chrome.storage.local.set({
            googleToken: accessToken,
            googleTokenExpiry: expiryTime,
            googleEmail: email,
            syncEnabled: true
          }, () => {
            resolve(accessToken);
          });
        } catch (err) {
          reject(new Error('Failed to fetch user profile details.'));
        }
      });
    });
  }

  static async signOut() {
    return new Promise((resolve) => {
      chrome.storage.local.remove(['googleToken', 'googleTokenExpiry', 'googleEmail'], () => {
        chrome.storage.local.set({ syncEnabled: false }, resolve);
      });
    });
  }

  static async findBackupFile(accessToken) {
    const query = encodeURIComponent(`name = '${BACKUP_FILE_NAME}' and parents in 'appDataFolder'`);
    const res = await fetch(`${DRIVE_FILES_URL}?q=${query}&spaces=appDataFolder&fields=files(id)`, {
      headers: { Authorization: `Bearer ${accessToken}` }
    });
    
    if (!res.ok) throw new Error('Failed to query Google Drive files.');
    
    const data = await res.json();
    return data.files && data.files.length > 0 ? data.files[0].id : null;
  }

  static async checkModifiedTime() {
    try {
      const token = await this.getAccessToken(false);
      if (!token) return null;
      const query = encodeURIComponent(`name = '${BACKUP_FILE_NAME}' and parents in 'appDataFolder'`);
      const res = await fetch(`${DRIVE_FILES_URL}?q=${query}&spaces=appDataFolder&fields=files(modifiedTime)`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (!res.ok) return null;
      const data = await res.json();
      return data.files && data.files.length > 0 ? data.files[0].modifiedTime : null;
    } catch (e) {
      return null;
    }
  }

  static async backupNow(clipsList) {
    try {
      const token = await this.getAccessToken(false);
      if (!token) return null;

      const payload = JSON.stringify({
        app: 'ClipVault',
        schemaVersion: 2,
        createdAt: new Date().toISOString(),
        clipCount: clipsList.length,
        clips: clipsList
      }, null, 2);

      const existingId = await this.findBackupFile(token);
      
      const metadata = {
        name: BACKUP_FILE_NAME,
        parents: ['appDataFolder']
      };

      const boundary = 'foo_bar_boundary';
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
        ''
      ].join('\r\n');

      let url = DRIVE_UPLOAD_URL;
      let method = 'POST';
      let headers = {
        Authorization: `Bearer ${token}`,
        'Content-Type': `multipart/related; boundary=${boundary}`
      };

      if (existingId) {
        url = `${DRIVE_UPLOAD_URL}/${existingId}`;
        method = 'PATCH';
      } else {
        url = `${DRIVE_UPLOAD_URL}?uploadType=multipart&fields=id`;
      }

      const res = await fetch(url, {
        method: method,
        headers: headers,
        body: body
      });

      if (!res.ok) throw new Error('Upload request failed.');

      const when = new Date().toISOString();
      chrome.storage.local.set({ lastBackupAt: when });
      return when;
    } catch (err) {
      console.error('Backup error:', err);
      throw err;
    }
  }

  static async restoreNow() {
    try {
      const token = await this.getAccessToken(true);
      const fileId = await this.findBackupFile(token);

      if (!fileId) {
        throw new Error('No existing backup file found in Google Drive.');
      }

      const res = await fetch(`${DRIVE_FILES_URL}/${fileId}?alt=media`, {
        headers: { Authorization: `Bearer ${token}` }
      });

      if (!res.ok) throw new Error('Failed to download backup file.');

      const data = await res.json();
      if (data.app !== 'ClipVault' || !Array.isArray(data.clips)) {
        throw new Error('Downloaded backup is not a valid ClipVault archive.');
      }

      // Merge clips list (preventing duplicate hashes)
      const currentRes = await new Promise(r => chrome.storage.local.get(['clips'], r));
      let localClips = currentRes.clips || [];

      let restoredCount = 0;
      data.clips.forEach(remoteClip => {
        const hasMatch = localClips.some(local => local.hash === remoteClip.hash);
        if (!hasMatch) {
          localClips.push(remoteClip);
          restoredCount++;
        }
      });

      // Sort clips by timestamp descending
      localClips.sort((a, b) => b.timestamp - a.timestamp);

      // Limit size to 200 items
      if (localClips.length > 200) {
        localClips = localClips.slice(0, 200);
      }

      await new Promise(r => chrome.storage.local.set({ clips: localClips }, r));
      return restoredCount;
    } catch (err) {
      console.error('Restore error:', err);
      throw err;
    }
  }
}
