/**
 * ClipVault — Chrome Extension Background Service Worker
 *
 * - Handles local clipboard storage with duplicate detection
 * - Manages Google Drive sync routines
 * - Fetches Google Font WOFF2 URLs to bypass page CSP
 * - Manages screen recording via a dedicated Recorder Tab
 *   (recorder.html calls getDisplayMedia() directly — no streamId expiry issues)
 */

import { GoogleDriveSyncService } from './google-drive.js';

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────
function hashText(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash).toString(16);
}

const DEFAULT_CUSTOMIZER_SETTINGS = {
  enabled: true,
  fontFamily: 'noto_nastaliq',
  fontSize: 22,
  lineHeight: 1.8
};

// ─────────────────────────────────────────────────────────────
// On Install & Startup
// ─────────────────────────────────────────────────────────────
chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get(['clips', 'customizerSettings', 'syncEnabled'], (res) => {
    if (!res.clips) chrome.storage.local.set({ clips: [] });
    if (!res.customizerSettings) chrome.storage.local.set({ customizerSettings: DEFAULT_CUSTOMIZER_SETTINGS });
    if (res.syncEnabled === undefined) chrome.storage.local.set({ syncEnabled: false });
  });
  updateFontUrls();

  // Inject content script into all existing HTTP/HTTPS tabs immediately
  // so Font Customizer works without needing a first-time page reload.
  chrome.tabs.query({ url: ['http://*/*', 'https://*/*'] }, (tabs) => {
    for (const tab of tabs) {
      if (tab.url && tab.url.startsWith('http')) {
        chrome.scripting.executeScript({
          target: { tabId: tab.id },
          files: ['content.js']
        }).catch(err => {
          // Ignore errors for restricted pages (e.g. chrome web store)
        });
      }
    }
  });
});

chrome.runtime.onStartup.addListener(updateFontUrls);

// ─────────────────────────────────────────────────────────────
// Google Font URL Fetcher
// ─────────────────────────────────────────────────────────────
async function updateFontUrls() {
  const families = [
    'Amiri:wght@400;700', 'Aref+Ruqaa:wght@400;700', 'Gulzar', 'Lalezar',
    'Noto+Nastaliq+Urdu:wght@400;700', 'Noto+Sans+Arabic:wght@400;700', 'Reem+Kufi:wght@400;700'
  ];
  const url = `https://fonts.googleapis.com/css2?family=${families.join('&family=')}&display=swap`;
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' }
    });
    if (!res.ok) return;
    const cssText = await res.text();
    const fontUrls = {};
    const fontFaceRegex = /@font-face\s*\{([^}]+)\}/g;
    let match;
    while ((match = fontFaceRegex.exec(cssText)) !== null) {
      const block = match[1];
      if (block.includes('U+0600') || block.includes('u+0600')) {
        const familyMatch = /font-family:\s*['"]([^'"]+)['"]/i.exec(block);
        const urlMatch = /url\(([^)]+)\)/i.exec(block);
        if (familyMatch && urlMatch) {
          fontUrls[familyMatch[1]] = urlMatch[1].replace(/['"]/g, '');
        }
      }
    }
    if (Object.keys(fontUrls).length > 0) {
      chrome.storage.local.set({ googleFontUrls: fontUrls });
      console.log('[ClipVault] Google Font URLs updated.');
    }
  } catch (err) {
    console.error('[ClipVault] Font URL update failed:', err);
  }
}

// ─────────────────────────────────────────────────────────────
// Recorder Tab Manager
// Opens recorder.html as an active extension tab.
// recorder.js calls getDisplayMedia() — no streamId, no expiry.
// ─────────────────────────────────────────────────────────────
let recorderTabId = null;
let recordingSourceTabId = null;
let recordingSourceWindowId = null;

async function closeRecorderTab() {
  if (recorderTabId) {
    try { await chrome.tabs.remove(recorderTabId); } catch (e) {}
    recorderTabId = null;
  }
}

// ─────────────────────────────────────────────────────────────
// Message Listener
// ─────────────────────────────────────────────────────────────
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {

  // ── Clipboard ──────────────────────────────────────────────
  if (message.type === 'ADD_CLIP') {
    const { text, source, forceSyncOnly } = message;
    chrome.storage.local.get(['clips', 'syncEnabled'], async (res) => {
      let clipsList = res.clips || [];
      if (forceSyncOnly) {
        if (res.syncEnabled) triggerDriveBackup(clipsList);
        return;
      }
      const hash = hashText(text);
      const existingIdx = clipsList.findIndex(c => c.hash === hash);
      const timestamp = Date.now();
      if (existingIdx !== -1) {
        const clip = clipsList[existingIdx];
        clip.timestamp = timestamp;
        clipsList.splice(existingIdx, 1);
        clipsList.unshift(clip);
      } else {
        clipsList.unshift({
          id: 'clip_' + timestamp + '_' + Math.random().toString(36).substr(2, 5),
          text, hash, timestamp,
          source: source || 'Webpage',
          category: detectCategory(text)
        });
      }
      if (clipsList.length > 200) clipsList = clipsList.slice(0, 200);
      chrome.storage.local.set({ clips: clipsList }, () => {
        if (res.syncEnabled) triggerDriveBackup(clipsList);
      });
      chrome.action.setBadgeText({ text: 'New' });
      chrome.action.setBadgeBackgroundColor({ color: '#6366f1' });
      setTimeout(() => chrome.action.setBadgeText({ text: '' }), 2000);
    });
    sendResponse({ success: true });
    return false;
  }

  // ── Open Recorder Tab ─────────────────────────────────────
  // recorder.html calls getDisplayMedia() directly on load
  else if (message.type === 'REQUEST_SCREEN_PICKER') {
    (async () => {
      try {
        await closeRecorderTab();

        // Save source tab info so we can refocus it after recording starts
        const queryTabs = (q) => new Promise(r => chrome.tabs.query(q, r));
        let tabs = await queryTabs({ active: true, lastFocusedWindow: true });
        if (!tabs || tabs.length === 0) tabs = await queryTabs({ active: true });
        if (tabs && tabs.length > 0) {
          recordingSourceTabId = tabs[0].id;
          recordingSourceWindowId = tabs[0].windowId;
        }

        // Open recorder tab (must be active for getDisplayMedia to work)
        const recorderTab = await chrome.tabs.create({
          url: chrome.runtime.getURL('recorder.html'),
          active: true
        });
        recorderTabId = recorderTab.id;
        console.log('[ClipVault] Recorder tab opened:', recorderTabId);
      } catch (err) {
        console.error('[ClipVault] Failed to open recorder tab:', err);
      }
    })();
    sendResponse({ success: true });
    return true;
  }

  // ── Recording started: refocus source tab ─────────────────
  else if (message.type === 'RECORDING_STARTED') {
    console.log('[ClipVault] Recording started. Refocusing source tab...');
    if (recordingSourceTabId) {
      chrome.tabs.update(recordingSourceTabId, { active: true }).catch(() => {});
    }
    if (recordingSourceWindowId) {
      chrome.windows.update(recordingSourceWindowId, { focused: true }).catch(() => {});
    }
    chrome.runtime.sendMessage({ type: 'RECORDING_STARTED_UI' }).catch(() => {});
    sendResponse({ success: true });
    return false;
  }

  // ── Recording cancelled by user (no error, just closed picker) ─
  else if (message.type === 'RECORDING_CANCELLED') {
    closeRecorderTab();
    recordingSourceTabId = null;
    recordingSourceWindowId = null;
    sendResponse({ success: true });
    return false;
  }

  // ── Recording failed ───────────────────────────────────────
  else if (message.type === 'RECORDING_FAILED') {
    console.error('[ClipVault] Recording failed:', message.error);
    closeRecorderTab();
    recordingSourceTabId = null;
    recordingSourceWindowId = null;
    chrome.runtime.sendMessage({ type: 'RECORDING_FAILED_UI', error: message.error }).catch(() => {});
    sendResponse({ success: true });
    return false;
  }

  // ── Stop recording ─────────────────────────────────────────
  else if (message.type === 'STOP_RECORDING') {
    if (recorderTabId) {
      chrome.tabs.sendMessage(recorderTabId, { type: 'STOP_OFFSCREEN_RECORDING' }, (res) => {
        if (chrome.runtime.lastError) {
          closeRecorderTab();
          sendResponse({ success: false, error: chrome.runtime.lastError.message });
        } else {
          sendResponse(res || { success: true });
        }
      });
    } else {
      sendResponse({ success: false, error: 'No active recorder tab.' });
    }
    return true;
  }

  // ── Get recording status ───────────────────────────────────
  else if (message.type === 'GET_RECORDING_STATUS') {
    if (recorderTabId) {
      chrome.tabs.sendMessage(recorderTabId, { type: 'GET_RECORDING_STATUS' }, (res) => {
        sendResponse(chrome.runtime.lastError ? { isRecording: false, elapsed: 0 } : (res || { isRecording: false, elapsed: 0 }));
      });
    } else {
      sendResponse({ isRecording: false, elapsed: 0 });
    }
    return true;
  }

  // ── Recording saved ────────────────────────────────────────
  else if (message.type === 'RECORDING_SAVED') {
    closeRecorderTab();
    recordingSourceTabId = null;
    recordingSourceWindowId = null;
    chrome.runtime.sendMessage({ type: 'RECORDING_COMPLETED' }).catch(() => {});
    sendResponse({ success: true });
    return false;
  }

  // ── Duration update relay ──────────────────────────────────
  else if (message.type === 'RECORDING_DURATION_UPDATE') {
    chrome.runtime.sendMessage(message).catch(() => {});
    sendResponse({ success: true });
    return false;
  }

  // ── Aggressive Polling ─────────────────────────────────────
  else if (message.type === 'POLL_SYNC_NOW') {
    checkAndRestoreDriveSync();
    sendResponse({ success: true });
    return false;
  }

  return true;
});

// ─────────────────────────────────────────────────────────────
// Category Classifier
// ─────────────────────────────────────────────────────────────
function detectCategory(text) {
  const clean = text.trim();
  if (/^(https?:\/\/)?([\da-z.-]+)\.([a-z.]{2,6})([\/\w .-]*)*\/?$/i.test(clean)) return 'URL';
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(clean)) return 'Email';
  if (/^[\+]?[(]?[0-9]{3}[)]?[-\s\.]?[0-9]{3}[-\s\.]?[0-9]{4,6}$/im.test(clean)) return 'Phone';
  if (/^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{1,30}$/.test(clean) || /^\d{9,18}$/.test(clean)) return 'Bank Account';
  if (/^\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}$/.test(clean) || /^\d{4}[\/\-]\d{1,2}[\/\-]\d{1,2}$/.test(clean)) return 'Date';
  if (clean.startsWith('{') && clean.endsWith('}') || clean.startsWith('<') && clean.endsWith('>')) return 'Code';
  return 'Text';
}

// ─────────────────────────────────────────────────────────────
// Google Drive Backup & Polling
// ─────────────────────────────────────────────────────────────
let lastKnownModifiedTime = null;
let isRestoring = false;

async function checkAndRestoreDriveSync() {
  if (isRestoring) return;
  try {
    chrome.storage.local.get(['syncEnabled'], async (res) => {
      if (!res.syncEnabled) return;
      const modifiedTime = await GoogleDriveSyncService.checkModifiedTime();
      if (modifiedTime && modifiedTime !== lastKnownModifiedTime) {
        lastKnownModifiedTime = modifiedTime;
        isRestoring = true;
        const newCount = await GoogleDriveSyncService.restoreNow();
        isRestoring = false;
        if (newCount > 0) {
          chrome.runtime.sendMessage({ type: 'ADD_CLIP' }).catch(() => {});
        }
      }
    });
  } catch (err) {
    isRestoring = false;
  }
}

async function triggerDriveBackup(clipsList) {
  try {
    const backupTime = await GoogleDriveSyncService.backupNow(clipsList);
    if (backupTime) {
      chrome.runtime.sendMessage({ type: 'DRIVE_SYNC_SUCCESS', lastBackupAt: backupTime }).catch(() => {});
      // Update our known modified time so we don't immediately download our own upload
      const latestModified = await GoogleDriveSyncService.checkModifiedTime();
      if (latestModified) lastKnownModifiedTime = latestModified;
    }
  } catch (err) {
    console.error('[ClipVault] Drive sync failed:', err);
    chrome.runtime.sendMessage({ type: 'DRIVE_SYNC_ERROR', error: err.message }).catch(() => {});
  }
}

// Background polling every 1 minute
chrome.alarms.create('driveSyncPoll', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'driveSyncPoll') {
    checkAndRestoreDriveSync();
  }
});

// ─────────────────────────────────────────────────────────────
// Keyboard Command Listener (Stop Recording shortcut Ctrl+Shift+S)
// ─────────────────────────────────────────────────────────────
chrome.commands.onCommand.addListener((command) => {
  if (command === 'stop-recording') {
    console.log('[ClipVault] stop-recording command received.');
    if (recorderTabId) {
      chrome.tabs.sendMessage(recorderTabId, { type: 'STOP_OFFSCREEN_RECORDING' }, (res) => {
        if (chrome.runtime.lastError) {
          closeRecorderTab();
        }
      });
    }
  }
});
