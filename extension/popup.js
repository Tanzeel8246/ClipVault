import { GoogleDriveSyncService } from './google-drive.js';

// ─────────────────────────────────────────────────────────────
// DOM Selectors
// ─────────────────────────────────────────────────────────────
const tabs = document.querySelectorAll('.nav-btn');
const tabPanes = document.querySelectorAll('.tab-pane');

// Customizer elements
const customizerToggle = document.getElementById('customizerToggle');
const customizerSettingsPanel = document.getElementById('customizerSettingsPanel');
const fontSelect = document.getElementById('fontSelect');
const fontSizeSlider = document.getElementById('fontSizeSlider');
const fontSizeVal = document.getElementById('fontSizeVal');
const lineHeightSlider = document.getElementById('lineHeightSlider');
const lineHeightVal = document.getElementById('lineHeightVal');
const urduPreviewBox = document.getElementById('urduPreviewBox');

// Clips elements
const searchClipsInput = document.getElementById('searchClipsInput');
const btnClearSearch = document.getElementById('btnClearSearch');
const clipsCountText = document.getElementById('clipsCountText');
const btnClearAllClips = document.getElementById('btnClearAllClips');
const clipsListContainer = document.getElementById('clipsListContainer');
const categoryFilters = document.getElementById('categoryFilters');

// Sync elements
const authCard = document.getElementById('authCard');
const cloudIcon = document.getElementById('cloudIcon');
const authTitle = document.getElementById('authTitle');
const authSubtitle = document.getElementById('authSubtitle');
const userDetails = document.getElementById('userDetails');
const userEmailText = document.getElementById('userEmailText');
const lastSyncText = document.getElementById('lastSyncText');
const loggedOutActions = document.getElementById('loggedOutActions');
const loggedInActions = document.getElementById('loggedInActions');
const btnGoogleLogin = document.getElementById('btnGoogleLogin');
const btnGoogleLogout = document.getElementById('btnGoogleLogout');
const expiredActions = document.getElementById('expiredActions');
const btnGoogleReconnect = document.getElementById('btnGoogleReconnect');
const btnGoogleLogoutExpired = document.getElementById('btnGoogleLogoutExpired');
const btnBackupNow = document.getElementById('btnBackupNow');
const btnRestoreNow = document.getElementById('btnRestoreNow');
const syncStatusIndicator = document.getElementById('syncStatusIndicator');
const syncStatusText = document.getElementById('syncStatusText');

// Client ID settings
const btnToggleClientId = document.getElementById('btnToggleClientId');
const clientIdContainer = document.getElementById('clientIdContainer');
const redirectUriInput = document.getElementById('redirectUriInput');
const btnCopyRedirectUri = document.getElementById('btnCopyRedirectUri');

// Screen Capture elements
const btnTakeScreenshot = document.getElementById('btnTakeScreenshot');
const btnStartRecording = document.getElementById('btnStartRecording');
const btnStopRecording = document.getElementById('btnStopRecording');
const recordingStatusContainer = document.getElementById('recordingStatusContainer');
const recordingTimer = document.getElementById('recordingTimer');

// Toast Container
const toastContainer = document.getElementById('toastContainer');

// ─────────────────────────────────────────────────────────────
// State Variables
// ─────────────────────────────────────────────────────────────
let allClips = [];
let currentLang = 'ur';

// ─────────────────────────────────────────────────────────────
// Translations
// ─────────────────────────────────────────────────────────────
const TRANSLATIONS = {
  en: {
    sub_text: "Companion Extension",
    local_mode: "Local Mode",
    tab_customizer: "Font Customizer",
    tab_clips: "Clip History",
    tab_sync: "Google Drive Sync",
    tab_capture: "Screen Capture",
    customizer_title: "Urdu Web Customizer",
    customizer_desc: "Change the style and size of Urdu text on websites",
    select_font: "Select Urdu Font",
    font_size: "Font Size",
    line_height: "Line Height",
    preview_label: "Preview",
    preview_text: "See a live preview of the selected font, size, and line height here. ClipVault applies your preferred fonts to Urdu text across all websites.",
    search_clips: "Search clips...",
    filter_all: "All",
    filter_text: "Text",
    filter_url: "Links",
    filter_contact: "Contact",
    filter_favorite: "Starred ⭐",
    no_clips: "No clips found",
    clear_all: "Clear All",
    empty_clips_1: "No clips saved yet.",
    empty_clips_2: "Copy anything on a website and it will automatically be saved here!",
    secure_sync: "Secure Cloud Sync",
    sync_desc: "Sync clips with your mobile app",
    adv_settings: "Advanced Settings (Redirect URI)",
    auth_redirect: "Authorized Redirect URI (Copy for Google Cloud Console):",
    copy: "Copy",
    connect_google: "Connect with Google Account",
    upload_backup: "Upload Backup 📤",
    download_backup: "Download Backup 📥",
    disconnect: "Disconnect (Sign Out)",
    how_sync_works: "How does mobile app sync work?",
    sync_info: "This extension uses Google Drive's hidden App Data Folder. Any clip you copy on your computer is uploaded to this folder, and your mobile ClipVault app automatically downloads it.",
    screenshot_title: "Take Screenshot",
    screenshot_desc: "Capture and download a quick screenshot of your active browser tab",
    btn_screenshot: "Take Screenshot 📸",
    recording_title: "Video Recording",
    recording_desc: "Record your screen with system audio and microphone",
    recording_active: "Recording in progress...",
    start_recording: "Start Recording ⏺️",
    stop_recording: "Stop Recording ⏹️"
  },
  ur: {
    sub_text: "ساتھی ایکسٹینشن",
    local_mode: "لوکل موڈ",
    tab_customizer: "اردو کسٹمائزر",
    tab_clips: "کلپ ہسٹری",
    tab_sync: "گوگل ڈرائیو سنک",
    tab_capture: "اسکرین کیپچر",
    customizer_title: "اردو ویب کسٹمائزر",
    customizer_desc: "ویب سائٹوں پر اردو تحریر کا انداز اور سائز تبدیل کریں",
    select_font: "اردو فونٹ منتخب کریں",
    font_size: "فونٹ کا سائز (Font Size)",
    line_height: "لائنوں کا درمیانی فاصلہ (Line Height)",
    preview_label: "پیش نظارہ (Preview)",
    preview_text: "یہاں منتخب کردہ فونٹ، سائز اور لائن ہائٹ کا لائیو مظاہرہ دیکھیں۔ کلپ والٹ آپ کی پسند کے مطابق تمام ویب سائٹس پر اردو کے بہترین فونٹس لاگو کرتا ہے۔",
    search_clips: "کلپ تلاش کریں...",
    filter_all: "تمام",
    filter_text: "ٹیکسٹ",
    filter_url: "لنکس",
    filter_contact: "رابطہ",
    filter_favorite: "ستارہ ⭐",
    no_clips: "کوئی کلپ موجود نہیں ہے",
    clear_all: "تمام صاف کریں",
    empty_clips_1: "ابھی تک کوئی کلپ محفوظ نہیں کیا گیا۔",
    empty_clips_2: "ویب سائٹ پر کچھ بھی کاپی کریں، وہ خود بخود یہاں محفوظ ہو جائے گا!",
    secure_sync: "محفوظ کلاؤڈ سنک",
    sync_desc: "اپنے موبائل ایپ کے ساتھ کلپس کو ہم آہنگ (Sync) کریں",
    adv_settings: "ترقی یافتہ ترتیبات (Redirect URI)",
    auth_redirect: "Authorized Redirect URI (گوگل کلاؤڈ کنسول کے لیے کاپی کریں):",
    copy: "کاپی",
    connect_google: "گوگل اکاؤنٹ سے منسلک کریں",
    upload_backup: "بیک اپ اپلوڈ کریں 📤",
    download_backup: "بیک اپ ڈاؤن لوڈ کریں 📥",
    disconnect: "منقطع کریں (Sign Out)",
    how_sync_works: "موبائل ایپ سنک کیسے کام کرتا ہے؟",
    sync_info: "یہ ایکسٹینشن گوگل ڈرائیو کے پوشیدہ فولڈر (App Data Folder) کو استعمال کرتی ہے۔ جو بھی کلپ آپ کمپیوٹر پر کاپی کریں گے، وہ اس فولڈر میں اپلوڈ ہو جائے گا اور آپ کے موبائل کی کلپ والٹ ایپ اسے خود بخود ڈاؤن لوڈ کر لے گی۔",
    screenshot_title: "سکرین شاٹ (Take Screenshot)",
    screenshot_desc: "اپنے فعال براؤزر ٹیب کا فوری اسکرین شاٹ لے کر ڈاؤن لوڈ کریں",
    btn_screenshot: "فوری اسکرین شاٹ لیں 📸",
    recording_title: "سکرین ریکارڈنگ (Video Recording)",
    recording_desc: "سسٹم کی آواز اور مائیکروفون کے ساتھ سکرین ویڈیو ریکارڈ کریں",
    recording_active: "ریکارڈنگ جاری ہے...",
    start_recording: "ریکارڈنگ شروع کریں ⏺️",
    stop_recording: "ریکارڈنگ روکیں ⏹️"
  }
};

function applyTranslations(lang) {
  const dict = TRANSLATIONS[lang];
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const key = el.getAttribute('data-i18n');
    if (dict[key]) el.textContent = dict[key];
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
    const key = el.getAttribute('data-i18n-placeholder');
    if (dict[key]) el.setAttribute('placeholder', dict[key]);
  });
  
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ur' ? 'rtl' : 'ltr';
  document.getElementById('langToggleBtn').textContent = lang === 'ur' ? 'EN' : 'اردو';
}

const langToggleBtn = document.getElementById('langToggleBtn');
langToggleBtn.addEventListener('click', () => {
  currentLang = currentLang === 'ur' ? 'en' : 'ur';
  chrome.storage.local.set({ appLang: currentLang });
  applyTranslations(currentLang);
});

// ─────────────────────────────────────────────────────────────
// Toast Notification Helper (Urdu language supported)
// ─────────────────────────────────────────────────────────────
function showToast(message, type = 'info') {
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.innerHTML = `
    <span>${message}</span>
    <button style="background:none;border:none;color:inherit;cursor:pointer;font-size:14px;margin-left:8px;" class="toast-close">&times;</button>
  `;
  
  toastContainer.appendChild(toast);
  
  const closeBtn = toast.querySelector('.toast-close');
  closeBtn.addEventListener('click', () => {
    toast.classList.add('fade-out');
    setTimeout(() => toast.remove(), 200);
  });

  setTimeout(() => {
    toast.classList.add('fade-out');
    setTimeout(() => {
      if (toast.parentNode) {
        toast.remove();
      }
    }, 200);
  }, 3000);
}

// ─────────────────────────────────────────────────────────────
// Tab Navigation Controller
// ─────────────────────────────────────────────────────────────
tabs.forEach(tab => {
  tab.addEventListener('click', () => {
    tabs.forEach(t => t.classList.remove('active'));
    tabPanes.forEach(p => p.classList.remove('active'));
    
    tab.classList.add('active');
    const targetTab = tab.getAttribute('data-tab');
    document.getElementById(targetTab).classList.add('active');
  });
});

// ─────────────────────────────────────────────────────────────
// Urdu Customizer Logic
// ─────────────────────────────────────────────────────────────
const DEFAULT_GOOGLE_FONT_URLS = {
  'Amiri': 'https://fonts.gstatic.com/s/amiri/v30/J7aRnpd8CGxBHpUrtLMA7w.woff2',
  'Aref Ruqaa': 'https://fonts.gstatic.com/s/arefruqaa/v26/WwkbxPW1E165rajQKDulIIIoVeo5.woff2',
  'Gulzar': 'https://fonts.gstatic.com/s/gulzar/v14/Wnz6HAc9eB3HB2I7YzYYmg8.woff2',
  'Lalezar': 'https://fonts.gstatic.com/s/lalezar/v16/zrfl0HLVx-HwTP82YaL4IxL0.woff2',
  'Noto Nastaliq Urdu': 'https://fonts.gstatic.com/s/notonastaliqurdu/v23/LhWNMUPbN-oZdNFcBy1-DJYsEoTq5pudQ9L940pGPkB3Qt_-PK-V2t_8.woff2',
  'Noto Sans Arabic': 'https://fonts.gstatic.com/s/notosansarabic/v33/nwpxtLGrOAZMl5nJ_wfgRg3DrWFZWsnVBJ_sS6tlqHHFlhQ5l3sQWIHPqzCfyGyfuXqAJQI.woff2',
  'Reem Kufi': 'https://fonts.gstatic.com/s/reemkufi/v28/2sDPZGJLip7W2J7v7wQZZE1I0yCmYzzQtuZnIGOV35Gu.woff2'
};

function injectFontsInPopup(storedUrls) {
  let styleEl = document.getElementById('clipvault-popup-font-styles');
  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = 'clipvault-popup-font-styles';
    document.head.appendChild(styleEl);
  }

  const urls = { ...DEFAULT_GOOGLE_FONT_URLS, ...storedUrls };
  let css = '';
  const fontFamilies = [
    { key: 'jameel_noori', name: 'Jameel Noori Nastaleeq', fallback: 'Noto Nastaliq Urdu' },
    { key: 'jameel_kasheeda', name: 'Jameel Noori Kasheeda', fallback: 'Noto Nastaliq Urdu' },
    { key: 'noto_nastaliq', name: 'Noto Nastaliq Urdu', fallback: 'Noto Nastaliq Urdu' },
    { key: 'amiri', name: 'Amiri', fallback: 'Amiri' },
    { key: 'gulzar', name: 'Gulzar', fallback: 'Gulzar' },
    { key: 'noto_sans_arabic', name: 'Noto Sans Arabic', fallback: 'Noto Sans Arabic' },
    { key: 'aref_ruqaa', name: 'Aref Ruqaa', fallback: 'Aref Ruqaa' },
    { key: 'reem_kufi', name: 'Reem Kufi', fallback: 'Reem Kufi' },
    { key: 'lalezar', name: 'Lalezar', fallback: 'Lalezar' },
    { key: 'nafees', name: 'Nafees Web Naskh', fallback: 'Amiri' }
  ];

  fontFamilies.forEach(f => {
    const fallbackUrl = urls[f.fallback] || DEFAULT_GOOGLE_FONT_URLS[f.fallback];
    css += `
      @font-face {
        font-family: '${f.name}';
        src: local('${f.name}'), url('${fallbackUrl}') format('woff2');
        font-weight: 400;
        font-style: normal;
        font-display: swap;
      }
    `;
  });

  styleEl.textContent = css;
}

function getFontFamilyFallback(fontKey) {
  switch (fontKey) {
    case 'jameel_noori':
      return "'Jameel Noori Nastaleeq', 'Noto Nastaliq Urdu', serif";
    case 'jameel_kasheeda':
      return "'Jameel Noori Kasheeda', 'Noto Nastaliq Urdu', serif";
    case 'noto_nastaliq':
      return "'Noto Nastaliq Urdu', serif";
    case 'amiri':
      return "'Amiri', serif";
    case 'gulzar':
      return "'Gulzar', serif";
    case 'noto_sans_arabic':
      return "'Noto Sans Arabic', sans-serif";
    case 'aref_ruqaa':
      return "'Aref Ruqaa', serif";
    case 'reem_kufi':
      return "'Reem Kufi', sans-serif";
    case 'lalezar':
      return "'Lalezar', sans-serif";
    case 'nafees':
      return "'Nafees Web Naskh', 'Amiri', serif";
    default:
      return "'Noto Nastaliq Urdu', serif";
  }
}

function updateCustomizerUI(settings) {
  customizerToggle.checked = settings.enabled;
  fontSelect.value = settings.fontFamily;
  fontSizeSlider.value = settings.fontSize;
  fontSizeVal.textContent = `${settings.fontSize}px`;
  lineHeightSlider.value = settings.lineHeight;
  lineHeightVal.textContent = settings.lineHeight;
  
  if (settings.enabled) {
    customizerSettingsPanel.classList.remove('disabled');
    fontSelect.removeAttribute('disabled');
    fontSizeSlider.removeAttribute('disabled');
    lineHeightSlider.removeAttribute('disabled');
  } else {
    customizerSettingsPanel.classList.add('disabled');
    fontSelect.setAttribute('disabled', 'true');
    fontSizeSlider.setAttribute('disabled', 'true');
    lineHeightSlider.setAttribute('disabled', 'true');
  }

  // Update popup's Urdu preview box styles dynamically
  urduPreviewBox.style.fontFamily = getFontFamilyFallback(settings.fontFamily);
  urduPreviewBox.style.fontSize = `${settings.fontSize}px`;
  urduPreviewBox.style.lineHeight = settings.lineHeight;
}

function saveCustomizerSettings() {
  const settings = {
    enabled: customizerToggle.checked,
    fontFamily: fontSelect.value,
    fontSize: parseInt(fontSizeSlider.value),
    lineHeight: parseFloat(lineHeightSlider.value)
  };
  chrome.storage.local.set({ customizerSettings: settings }, () => {
    updateCustomizerUI(settings);
  });
}

// Bind customizer events
customizerToggle.addEventListener('change', saveCustomizerSettings);
fontSelect.addEventListener('change', saveCustomizerSettings);
fontSizeSlider.addEventListener('input', (e) => {
  fontSizeVal.textContent = `${e.target.value}px`;
  saveCustomizerSettings();
});
lineHeightSlider.addEventListener('input', (e) => {
  lineHeightVal.textContent = e.target.value;
  saveCustomizerSettings();
});

// ─────────────────────────────────────────────────────────────
// Clips (Clipboard History) Management
// ─────────────────────────────────────────────────────────────
const URDU_REGEX = /[\u0600-\u06FF]/;

let activeCategoryFilter = 'all';

function loadClips() {
  chrome.storage.local.get(['clips'], (res) => {
    allClips = res.clips || [];
    filterAndRenderClips();
  });
}

function filterAndRenderClips() {
  const query = searchClipsInput.value.toLowerCase().trim();
  let filtered = allClips;
  
  // Category filter
  if (activeCategoryFilter !== 'all') {
    if (activeCategoryFilter === 'favorite') {
      filtered = filtered.filter(c => c.favorite);
    } else if (activeCategoryFilter === 'contact') {
      filtered = filtered.filter(c => c.category === 'Email' || c.category === 'Phone');
    } else if (activeCategoryFilter === 'url') {
      filtered = filtered.filter(c => c.category === 'URL');
    } else if (activeCategoryFilter === 'text') {
      filtered = filtered.filter(c => c.category === 'Text');
    }
  }
  
  // Search query filter
  if (query) {
    filtered = filtered.filter(c => c.text.toLowerCase().includes(query) || c.source.toLowerCase().includes(query));
  }
  
  renderClips(filtered);
}

function renderClips(clips) {
  clipsListContainer.innerHTML = '';
  
  const count = clips.length;
  clipsCountText.textContent = count > 0 ? `${count} کلپ محفوظ ہیں` : 'کوئی کلپ موجود نہیں ہے';
  
  if (count === 0) {
    clipsListContainer.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">📋</div>
        <p>ابھی تک کوئی کلپ محفوظ نہیں کیا گیا۔</p>
        <p class="sub">ویب سائٹ پر کچھ بھی کاپی کریں، وہ خود بخود یہاں محفوظ ہو جائے گا!</p>
      </div>
    `;
    return;
  }
  
  clips.forEach(clip => {
    const isUrdu = URDU_REGEX.test(clip.text);
    const dateStr = new Date(clip.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    
    // Smart action buttons based on category
    let actionButtonsHtml = '';
    if (clip.category === 'URL') {
      actionButtonsHtml = `<button class="btn btn-secondary btn-sm smart-action-btn open-url" data-url="${encodeURIComponent(clip.text)}">لنک کھولیں 🌐</button>`;
    } else if (clip.category === 'Phone') {
      const cleanPhone = clip.text.replace(/[^0-9+]/g, '');
      actionButtonsHtml = `
        <button class="btn btn-secondary btn-sm smart-action-btn whatsapp-btn" data-phone="${cleanPhone}">واٹس ایپ 💬</button>
        <button class="btn btn-secondary btn-sm smart-action-btn call-btn" data-phone="${cleanPhone}">کال 📞</button>
      `;
    } else if (clip.category === 'Email') {
      actionButtonsHtml = `<button class="btn btn-secondary btn-sm smart-action-btn email-btn" data-email="${encodeURIComponent(clip.text)}">ای میل ✉️</button>`;
    } else {
      actionButtonsHtml = `<button class="btn btn-secondary btn-sm smart-action-btn search-btn" data-query="${encodeURIComponent(clip.text)}">گوگل سرچ 🔍</button>`;
    }

    const footerHtml = actionButtonsHtml 
      ? `<div class="clip-card-footer" style="display:flex;gap:6px;margin-top:6px;border-top:1px solid rgba(255,255,255,0.04);padding-top:6px;">${actionButtonsHtml}</div>` 
      : '';

    const clipCard = document.createElement('div');
    clipCard.className = 'clip-card';
    clipCard.innerHTML = `
      <div class="clip-card-header">
        <div class="clip-metadata">
          <button class="btn-icon fav-btn" title="پسندیدہ" data-id="${clip.id}">${clip.favorite ? '★' : '☆'}</button>
          <span class="clip-badge ${clip.category.toLowerCase()}">${clip.category}</span>
          <span class="clip-source" title="${clip.source}">${clip.source}</span>
          <span style="font-size:9.5px;color:var(--text-muted);">${dateStr}</span>
        </div>
        <div class="clip-actions">
          <button class="btn-icon copy-btn" title="کاپی کریں" data-text="${encodeURIComponent(clip.text)}">📋</button>
          <button class="btn-icon delete delete-btn" title="حذف کریں" data-id="${clip.id}">🗑️</button>
        </div>
      </div>
      <div class="clip-text-content ${isUrdu ? 'rtl' : ''}">${escapeHTML(clip.text)}</div>
      ${footerHtml}
    `;
    clipsListContainer.appendChild(clipCard);
  });

  // Attach event listeners to clip actions
  clipsListContainer.querySelectorAll('.copy-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const textToCopy = decodeURIComponent(e.currentTarget.getAttribute('data-text'));
      navigator.clipboard.writeText(textToCopy).then(() => {
        showToast('کلپ بورڈ پر کاپی ہو گیا!', 'success');
      }).catch(() => {
        showToast('کاپی کرنے میں ناکامی۔', 'error');
      });
    });
  });

  clipsListContainer.querySelectorAll('.delete-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const id = e.currentTarget.getAttribute('data-id');
      deleteClip(id);
    });
  });

  clipsListContainer.querySelectorAll('.fav-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const id = e.currentTarget.getAttribute('data-id');
      toggleFavorite(id);
    });
  });

  // Smart action event listeners
  clipsListContainer.querySelectorAll('.open-url').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const url = decodeURIComponent(e.currentTarget.getAttribute('data-url'));
      const absoluteUrl = url.match(/^https?:\/\//i) ? url : `https://${url}`;
      chrome.tabs.create({ url: absoluteUrl });
    });
  });

  clipsListContainer.querySelectorAll('.whatsapp-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const phone = e.currentTarget.getAttribute('data-phone');
      chrome.tabs.create({ url: `https://wa.me/${phone}` });
    });
  });

  clipsListContainer.querySelectorAll('.call-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const phone = e.currentTarget.getAttribute('data-phone');
      chrome.tabs.create({ url: `tel:${phone}` });
    });
  });

  clipsListContainer.querySelectorAll('.email-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const email = decodeURIComponent(e.currentTarget.getAttribute('data-email'));
      chrome.tabs.create({ url: `mailto:${email}` });
    });
  });

  clipsListContainer.querySelectorAll('.search-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const query = decodeURIComponent(e.currentTarget.getAttribute('data-query'));
      chrome.tabs.create({ url: `https://www.google.com/search?q=${encodeURIComponent(query)}` });
    });
  });
}

function toggleFavorite(id) {
  chrome.storage.local.get(['clips', 'syncEnabled'], (res) => {
    const clips = res.clips || [];
    const updated = clips.map(c => {
      if (c.id === id) {
        c.favorite = !c.favorite;
      }
      return c;
    });
    chrome.storage.local.set({ clips: updated }, () => {
      loadClips();
      if (res.syncEnabled) {
        chrome.runtime.sendMessage({ type: 'ADD_CLIP', forceSyncOnly: true });
      }
    });
  });
}

function deleteClip(id) {
  chrome.storage.local.get(['clips', 'syncEnabled'], (res) => {
    const clips = res.clips || [];
    const filtered = clips.filter(c => c.id !== id);
    chrome.storage.local.set({ clips: filtered }, () => {
      loadClips();
      showToast('کلپ حذف کر دیا گیا', 'info');
      if (res.syncEnabled) {
        // Trigger background sync
        chrome.runtime.sendMessage({ type: 'ADD_CLIP', forceSyncOnly: true });
      }
    });
  });
}

// Search filter
searchClipsInput.addEventListener('input', (e) => {
  const query = e.target.value.toLowerCase().trim();
  if (query) {
    btnClearSearch.style.display = 'block';
  } else {
    btnClearSearch.style.display = 'none';
  }
  filterAndRenderClips();
});

btnClearSearch.addEventListener('click', () => {
  searchClipsInput.value = '';
  btnClearSearch.style.display = 'none';
  filterAndRenderClips();
});

// Category filter chips click binding
if (categoryFilters) {
  categoryFilters.querySelectorAll('.filter-chip').forEach(btn => {
    btn.addEventListener('click', (e) => {
      categoryFilters.querySelectorAll('.filter-chip').forEach(b => b.classList.remove('active'));
      e.currentTarget.classList.add('active');
      activeCategoryFilter = e.currentTarget.getAttribute('data-category');
      filterAndRenderClips();
    });
  });
}

btnClearAllClips.addEventListener('click', () => {
  if (confirm('کیا آپ واقعی تمام محفوظ شدہ کلپس کو حذف کرنا چاہتے ہیں؟')) {
    chrome.storage.local.set({ clips: [] }, () => {
      loadClips();
      showToast('تمام کلپس صاف کر دیئے گئے ہیں', 'info');
      chrome.storage.local.get(['syncEnabled'], (res) => {
        if (res.syncEnabled) {
          chrome.runtime.sendMessage({ type: 'ADD_CLIP', forceSyncOnly: true });
        }
      });
    });
  }
});

function escapeHTML(str) {
  return str.replace(/[&<>'"]/g, 
    tag => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;'
    }[tag] || tag)
  );
}

// ─────────────────────────────────────────────────────────────
// Google Drive Sync Logic
// ─────────────────────────────────────────────────────────────
async function checkAuthStatus() {
  try {
    const token = await GoogleDriveSyncService.getAccessToken(false);
    updateAuthState(token);
  } catch (err) {
    console.error('Auth check error:', err);
    updateAuthState(null);
  }
}

function updateAuthState(token) {
  if (token) {
    // Authenticated
    cloudIcon.className = 'cloud-icon connected';
    cloudIcon.textContent = '☁️';
    authTitle.textContent = 'گوگل ڈرائیو سنک فعال ہے';
    authSubtitle.textContent = 'آپ کے کلپس محفوظ طریقے سے کلاؤڈ پر مطابقت پذیر ہو رہے ہیں';
    
    // Get user details
    chrome.storage.local.get(['googleEmail', 'lastBackupAt'], (res) => {
      userEmailText.textContent = res.googleEmail || 'منسلک گوگل اکاؤنٹ';
      if (res.lastBackupAt) {
        const d = new Date(res.lastBackupAt);
        lastSyncText.textContent = `آخری بار بیک اپ: ${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
      } else {
        lastSyncText.textContent = 'آخری بار بیک اپ: ابھی تک نہیں';
      }
      userDetails.style.display = 'flex';
    });

    loggedOutActions.style.display = 'none';
    if (expiredActions) expiredActions.style.display = 'none';
    loggedInActions.style.display = 'block';
    
    syncStatusIndicator.className = 'status-indicator online';
    syncStatusText.textContent = 'سنک فعال ہے';
  } else {
    // Not authenticated or Expired
    chrome.storage.local.get(['googleEmail'], (res) => {
      if (res.googleEmail) {
        // Session expired (Email exists but token couldn't be silently refreshed)
        cloudIcon.className = 'cloud-icon disconnected';
        cloudIcon.textContent = '☁️';
        authTitle.textContent = 'سیشن ختم ہو گیا';
        authSubtitle.textContent = 'آپ کا گوگل ڈرائیو سیشن ایکسپائر ہو چکا ہے۔';
        userDetails.style.display = 'none';
        
        loggedOutActions.style.display = 'none';
        loggedInActions.style.display = 'none';
        if (expiredActions) expiredActions.style.display = 'block';

        syncStatusIndicator.className = 'status-indicator offline';
        syncStatusText.textContent = 'سیشن ایکسپائرڈ';
      } else {
        // Truly logged out
        cloudIcon.className = 'cloud-icon disconnected';
        cloudIcon.textContent = '☁️';
        authTitle.textContent = 'محفوظ کلاؤڈ سنک';
        authSubtitle.textContent = 'اپنے موبائل ایپ کے ساتھ کلپس کو ہم آہنگ (Sync) کریں';
        userDetails.style.display = 'none';
        
        loggedOutActions.style.display = 'block';
        loggedInActions.style.display = 'none';
        if (expiredActions) expiredActions.style.display = 'none';

        syncStatusIndicator.className = 'status-indicator offline';
        syncStatusText.textContent = 'لوکل موڈ';
      }
    });
  }
}

// Login triggers
btnGoogleLogin.addEventListener('click', async () => {
  btnGoogleLogin.setAttribute('disabled', 'true');
  btnGoogleLogin.textContent = 'منسلک کیا جا رہا ہے...';
  try {
    const token = await GoogleDriveSyncService.getAccessToken(true);
    updateAuthState(token);
    showToast('گوگل سنک کامیابی سے فعال ہو گیا!', 'success');
  } catch (err) {
    showToast(`کنکشن میں ناکامی: ${err.message}`, 'error');
    updateAuthState(null);
  } finally {
    btnGoogleLogin.removeAttribute('disabled');
    btnGoogleLogin.textContent = 'گوگل اکاؤنٹ سے منسلک کریں';
  }
});

// Logout triggers
btnGoogleLogout.addEventListener('click', async () => {
  if (confirm('کیا آپ گوگل ڈرائیو سنک کو غیر فعال اور سائن آؤٹ کرنا چاہتے ہیں؟')) {
    await GoogleDriveSyncService.signOut();
    updateAuthState(null);
    showToast('سائن آؤٹ کر دیا گیا ہے', 'info');
  }
});

if (btnGoogleReconnect) {
  btnGoogleReconnect.addEventListener('click', async () => {
    btnGoogleReconnect.setAttribute('disabled', 'true');
    btnGoogleReconnect.textContent = 'منسلک کیا جا رہا ہے...';
    try {
      const token = await GoogleDriveSyncService.getAccessToken(true);
      updateAuthState(token);
      showToast('سیشن کامیابی سے بحال ہو گیا!', 'success');
    } catch (err) {
      showToast(`کنکشن میں ناکامی: ${err.message}`, 'error');
      updateAuthState(null);
    } finally {
      btnGoogleReconnect.removeAttribute('disabled');
      btnGoogleReconnect.textContent = 'دوبارہ منسلک کریں (Reconnect)';
    }
  });
}

if (btnGoogleLogoutExpired) {
  btnGoogleLogoutExpired.addEventListener('click', async () => {
    if (confirm('کیا آپ گوگل ڈرائیو سنک کو غیر فعال اور سائن آؤٹ کرنا چاہتے ہیں؟')) {
      await GoogleDriveSyncService.signOut();
      updateAuthState(null);
      showToast('سائن آؤٹ کر دیا گیا ہے', 'info');
    }
  });
}

// Manual backup
btnBackupNow.addEventListener('click', async () => {
  btnBackupNow.setAttribute('disabled', 'true');
  btnBackupNow.textContent = 'اپلوڈ کیا جا رہا ہے...';
  syncStatusIndicator.className = 'status-indicator syncing';
  
  try {
    const res = await new Promise(r => chrome.storage.local.get(['clips'], r));
    const clips = res.clips || [];
    const timestamp = await GoogleDriveSyncService.backupNow(clips);
    
    if (timestamp) {
      updateAuthState(true);
      showToast('بیک اپ کامیابی سے ڈرائیو پر اپلوڈ ہو گیا!', 'success');
    }
  } catch (err) {
    showToast(`بیک اپ میں خرابی: ${err.message}`, 'error');
  } finally {
    btnBackupNow.removeAttribute('disabled');
    btnBackupNow.textContent = 'بیک اپ اپلوڈ کریں 📤';
  }
});

// Manual restore
btnRestoreNow.addEventListener('click', async () => {
  btnRestoreNow.setAttribute('disabled', 'true');
  btnRestoreNow.textContent = 'ڈاؤن لوڈ کیا جا رہا...';
  syncStatusIndicator.className = 'status-indicator syncing';
  
  try {
    const newCount = await GoogleDriveSyncService.restoreNow();
    loadClips();
    updateAuthState(true);
    if (newCount > 0) {
      showToast(`کامیابی! ڈرائیو سے ${newCount} نئے کلپس حاصل کیے گئے۔`, 'success');
    } else {
      showToast('آپ کی ہسٹری پہلے ہی مکمل اپ ٹو ڈیٹ ہے!', 'success');
    }
  } catch (err) {
    showToast(`ڈاؤن لوڈ میں خرابی: ${err.message}`, 'error');
    updateAuthState(true);
  } finally {
    btnRestoreNow.removeAttribute('disabled');
    btnRestoreNow.textContent = 'بیک اپ ڈاؤن لوڈ کریں 📥';
  }
});

// Advanced Custom Google Client ID Logic
btnToggleClientId.addEventListener('click', () => {
  if (clientIdContainer.style.display === 'none') {
    clientIdContainer.style.display = 'flex';
    btnToggleClientId.textContent = 'ترقی یافتہ ترتیبات چھپائیں';
  } else {
    clientIdContainer.style.display = 'none';
    btnToggleClientId.textContent = 'ترقی یافتہ ترتیبات (Redirect URI)';
  }
});

if (btnCopyRedirectUri) {
  btnCopyRedirectUri.addEventListener('click', () => {
    navigator.clipboard.writeText(redirectUriInput.value).then(() => {
      showToast('لنک کاپی ہو گیا!', 'success');
    }).catch(() => {
      showToast('لنک کاپی کرنے میں ناکامی۔', 'error');
    });
  });
}

// Screen Capture Actions
if (btnTakeScreenshot) {
  btnTakeScreenshot.addEventListener('click', () => {
    btnTakeScreenshot.setAttribute('disabled', 'true');
    btnTakeScreenshot.textContent = 'سکرین شاٹ لیا جا رہا ہے...';
    
    // Capture the visible tab as a PNG data URL
    chrome.tabs.captureVisibleTab(null, { format: 'png' }, (dataUrl) => {
      if (chrome.runtime.lastError || !dataUrl) {
        showToast('سکرین شاٹ لینے میں ناکامی۔ پلے سٹور یا کروم پیجز پر کام نہیں کرتا۔', 'error');
        btnTakeScreenshot.removeAttribute('disabled');
        btnTakeScreenshot.textContent = 'فوری اسکرین شاٹ لیں 📸';
        return;
      }
      
      const filename = `clipvault_screenshot_${Math.floor(Date.now() / 1000)}.png`;
      chrome.downloads.download({
        url: dataUrl,
        filename: filename,
        saveAs: true
      }, () => {
        showToast('سکرین شاٹ کامیابی سے محفوظ ہو گیا!', 'success');
        btnTakeScreenshot.removeAttribute('disabled');
        btnTakeScreenshot.textContent = 'فوری اسکرین شاٹ لیں 📸';
      });
    });
  });
}

function formatDuration(seconds) {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}

function setRecordingUIActive(elapsedSeconds) {
  btnStartRecording.setAttribute('disabled', 'true');
  btnStopRecording.removeAttribute('disabled');
  recordingStatusContainer.style.display = 'flex';
  recordingTimer.textContent = formatDuration(elapsedSeconds);
}

function setRecordingUIInactive() {
  btnStartRecording.removeAttribute('disabled');
  btnStopRecording.setAttribute('disabled', 'true');
  recordingStatusContainer.style.display = 'none';
  recordingTimer.textContent = '00:00';
}

if (btnStartRecording) {
  btnStartRecording.addEventListener('click', () => {
    btnStartRecording.setAttribute('disabled', 'true');
    btnStartRecording.textContent = 'ریکارڈر پیج کھولا جا رہا ہے...';

    // Ask background to open the desktop capture picker via recorder tab
    chrome.runtime.sendMessage({ type: 'REQUEST_SCREEN_PICKER' }, (res) => {
      if (chrome.runtime.lastError || !res || !res.success) {
        showToast('ریکارڈنگ شروع کرنے میں ناکامی۔', 'error');
        btnStartRecording.removeAttribute('disabled');
        btnStartRecording.textContent = 'اسکرین ریکارڈنگ شروع کریں 🔴';
      } else {
        showToast('ریکارڈر ٹیب کھل گیا ہے۔ ریکارڈنگ شروع کرنے کے لیے وہاں بٹن پر کلک کریں۔', 'success');
        setTimeout(() => {
          btnStartRecording.removeAttribute('disabled');
          btnStartRecording.textContent = 'اسکرین ریکارڈنگ شروع کریں 🔴';
        }, 1500);
      }
    });
  });
}


if (btnStopRecording) {
  btnStopRecording.addEventListener('click', () => {
    btnStopRecording.setAttribute('disabled', 'true');
    btnStopRecording.textContent = 'بیک اپ محفوظ ہو رہا ہے...';
    
    chrome.runtime.sendMessage({ type: 'STOP_RECORDING' }, (res) => {
      btnStopRecording.textContent = 'ریکارڈنگ روکیں ⏹️';
      if (res && res.success) {
        showToast('ریکارڈنگ بند ہو گئی ہے۔ فائل محفوظ کی جا رہی ہے...', 'success');
      } else {
        showToast('ریکارڈنگ روکنے میں ناکامی۔', 'error');
        btnStopRecording.removeAttribute('disabled');
      }
    });
  });
}

// ─────────────────────────────────────────────────────────────
// Initialisation on Load
// ─────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', async () => {
  // Load Language Preference
  const langRes = await chrome.storage.local.get(['appLang']);
  if (langRes.appLang) {
    currentLang = langRes.appLang;
  }
  applyTranslations(currentLang);

  chrome.storage.local.get(['clips', 'customizerSettings', 'syncEnabled', 'googleFontUrls'], (res) => {
    const fontUrls = res.googleFontUrls || {};
    injectFontsInPopup(fontUrls);

    if (res.customizerSettings) {
      updateCustomizerUI(res.customizerSettings);
    } else {
      // If not stored, use default UI configuration
      const defaultSettings = {
        enabled: true,
        fontFamily: 'noto_nastaliq',
        fontSize: 22,
        lineHeight: 1.8
      };
      updateCustomizerUI(defaultSettings);
    }
    
    // Set Authorized Redirect URI value
    const redirectUri = `https://${chrome.runtime.id}.chromiumapp.org/`;
    if (redirectUriInput) {
      redirectUriInput.value = redirectUri;
    }
  });

  // Load clips list
  loadClips();
  
  // Verify auth connection
  checkAuthStatus();

  // Verify active recording status in case popup was closed/reopened
  chrome.runtime.sendMessage({ type: 'GET_RECORDING_STATUS' }, (res) => {
    if (res && res.isRecording) {
      setRecordingUIActive(res.elapsed);
    } else {
      setRecordingUIInactive();
    }
  });

  // Start aggressive polling for Google Drive Sync
  setInterval(() => {
    chrome.storage.local.get(['syncEnabled'], (state) => {
      if (state.syncEnabled) {
        chrome.runtime.sendMessage({ type: 'POLL_SYNC_NOW' }).catch(() => {});
      }
    });
  }, 3000);
});

// Listen to message updates from background script
chrome.runtime.onMessage.addListener((message) => {
  if (message.type === 'DRIVE_SYNC_SUCCESS') {
    if (message.lastBackupAt) {
      const d = new Date(message.lastBackupAt);
      lastSyncText.textContent = `آخری بار بیک اپ: ${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    }
  } else if (message.type === 'DRIVE_SYNC_ERROR') {
    showToast(`آٹو بیک اپ میں خرابی: ${message.error}`, 'error');
  } else if (message.type === 'ADD_CLIP') {
    loadClips();
  } else if (message.type === 'RECORDING_STARTED_UI') {
    setRecordingUIActive(0);
    showToast('سکرین ریکارڈنگ شروع ہو گئی ہے', 'success');
  } else if (message.type === 'RECORDING_DURATION_UPDATE') {
    if (recordingTimer) recordingTimer.textContent = formatDuration(message.elapsed);
  } else if (message.type === 'RECORDING_COMPLETED') {
    setRecordingUIInactive();
    showToast('ریکارڈنگ کامیابی سے ڈاؤن لوڈ ہو گئی!', 'success');
  } else if (message.type === 'RECORDING_FAILED_UI') {
    setRecordingUIInactive();
    showToast(`ریکارڈنگ میں خرابی: ${message.error || 'نا معلوم وجہ'}`, 'error');
  }
});
