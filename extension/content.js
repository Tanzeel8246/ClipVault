/**
 * ClipVault — Chrome Extension Content Script
 *
 * - Webpage copy listener (auto-saves selected text to clipboard history)
 * - Urdu Font Customizer: injects fonts + applies styles to all Urdu text on page
 *   Uses dual-layer approach: @font-face declaration + inline !important overrides
 */

// ─────────────────────────────────────────────────────────────
// 1. Copy Event Listener
// ─────────────────────────────────────────────────────────────
// Intercept standard copy events (e.g. Ctrl+C on selected text)
document.addEventListener('copy', () => {
  const selectedText = window.getSelection().toString().trim();
  if (selectedText) {
    chrome.runtime.sendMessage({
      type: 'ADD_CLIP',
      text: selectedText,
      source: window.location.hostname
    });
  }
});

// Intercept navigator.clipboard.writeText (e.g. WhatsApp Web "Copy" buttons)
const injectInterceptor = () => {
  try {
    const script = document.createElement('script');
    script.src = chrome.runtime.getURL('inject.js');
    script.onload = function() {
      this.remove();
    };
    (document.head || document.documentElement).appendChild(script);
  } catch (e) {
    console.warn('[ClipVault] Failed to inject interceptor:', e);
  }
};

injectInterceptor();

// Listen for intercepted copies from the MAIN world
window.addEventListener('message', (e) => {
  if (e.source !== window || !e.data) return;
  if (e.data.type === 'CLIPVAULT_INTERCEPT' && e.data.text) {
    const text = e.data.text.trim();
    if (text) {
      chrome.runtime.sendMessage({
        type: 'ADD_CLIP',
        text: text,
        source: window.location.hostname
      }).catch(() => {});
    }
  }
});

// ─────────────────────────────────────────────────────────────
// 2. Font URLs & @font-face Stylesheet
// ─────────────────────────────────────────────────────────────
const STYLE_ELEMENT_ID = 'clipvault-urdu-web-styles';

// Hardcoded stable Google Fonts CDN fallbacks (woff2)
const DEFAULT_GOOGLE_FONT_URLS = {
  'Amiri': 'https://fonts.gstatic.com/s/amiri/v30/J7aRnpd8CGxBHpUrtLMA7w.woff2',
  'Aref Ruqaa': 'https://fonts.gstatic.com/s/arefruqaa/v26/WwkbxPW1E165rajQKDulIIIoVeo5.woff2',
  'Gulzar': 'https://fonts.gstatic.com/s/gulzar/v14/Wnz6HAc9eB3HB2I7YzYYmg8.woff2',
  'Lalezar': 'https://fonts.gstatic.com/s/lalezar/v16/zrfl0HLVx-HwTP82YaL4IxL0.woff2',
  'Noto Nastaliq Urdu': 'https://fonts.gstatic.com/s/notonastaliqurdu/v23/LhWNMUPbN-oZdNFcBy1-DJYsEoTq5pudQ9L940pGPkB3Qt_-PK-V2t_8.woff2',
  'Noto Sans Arabic': 'https://fonts.gstatic.com/s/notosansarabic/v33/nwpxtLGrOAZMl5nJ_wfgRg3DrWFZWsnVBJ_sS6tlqHHFlhQ5l3sQWIHPqzCfyGyfuXqAJQI.woff2',
  'Reem Kufi': 'https://fonts.gstatic.com/s/reemkufi/v28/2sDPZGJLip7W2J7v7wQZZE1I0yCmYzzQtuZnIGOV35Gu.woff2'
};

let googleFontUrls = { ...DEFAULT_GOOGLE_FONT_URLS };

function getFontFaceSrc(fontKey) {
  const notoUrl = googleFontUrls['Noto Nastaliq Urdu'] || DEFAULT_GOOGLE_FONT_URLS['Noto Nastaliq Urdu'];
  const amiriUrl = googleFontUrls['Amiri'] || DEFAULT_GOOGLE_FONT_URLS['Amiri'];
  switch (fontKey) {
    case 'jameel_noori':    return `local('Jameel Noori Nastaleeq'), url('${notoUrl}') format('woff2')`;
    case 'jameel_kasheeda': return `local('Jameel Noori Kasheeda'), url('${notoUrl}') format('woff2')`;
    case 'noto_nastaliq':   return `local('Noto Nastaliq Urdu'), url('${notoUrl}') format('woff2')`;
    case 'amiri':           return `local('Amiri'), url('${amiriUrl}') format('woff2')`;
    case 'gulzar':          return `local('Gulzar'), url('${googleFontUrls['Gulzar'] || DEFAULT_GOOGLE_FONT_URLS['Gulzar']}') format('woff2')`;
    case 'noto_sans_arabic':return `local('Noto Sans Arabic'), url('${googleFontUrls['Noto Sans Arabic'] || DEFAULT_GOOGLE_FONT_URLS['Noto Sans Arabic']}') format('woff2')`;
    case 'aref_ruqaa':      return `local('Aref Ruqaa'), url('${googleFontUrls['Aref Ruqaa'] || DEFAULT_GOOGLE_FONT_URLS['Aref Ruqaa']}') format('woff2')`;
    case 'reem_kufi':       return `local('Reem Kufi'), url('${googleFontUrls['Reem Kufi'] || DEFAULT_GOOGLE_FONT_URLS['Reem Kufi']}') format('woff2')`;
    case 'lalezar':         return `local('Lalezar'), url('${googleFontUrls['Lalezar'] || DEFAULT_GOOGLE_FONT_URLS['Lalezar']}') format('woff2')`;
    case 'nafees':          return `local('Nafees Web Naskh'), url('${amiriUrl}') format('woff2')`;
    default:                return `local('Noto Nastaliq Urdu'), url('${notoUrl}') format('woff2')`;
  }
}

function injectFontsAndStyles(settings) {
  let styleEl = document.getElementById(STYLE_ELEMENT_ID);
  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = STYLE_ELEMENT_ID;
  }
  // Append to head so it is at the bottom (highest stylesheet priority)
  if (document.head && styleEl.parentElement !== document.head) {
    document.head.appendChild(styleEl);
  }

  const src = getFontFaceSrc(settings.fontFamily);
  const fs = settings.fontSize || 22;
  const lh = settings.lineHeight || 1.8;

  // Inject @font-face + broad CSS selectors with !important for maximum coverage
  styleEl.textContent = `
    @font-face {
      font-family: 'ClipVaultUrduCustom';
      src: ${src};
      font-weight: 400;
      font-style: normal;
      font-display: swap;
    }

    /* Broad selector override — catches most Urdu text elements */
    [data-clipvault-processed="true"] {
      font-family: 'ClipVaultUrduCustom' !important;
      font-size: ${fs}px !important;
      line-height: ${lh} !important;
    }
  `;
}

// ─────────────────────────────────────────────────────────────
// 3. Urdu Element Scanner — applies inline styles
// ─────────────────────────────────────────────────────────────
const URDU_REGEX = /[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]/;
const PROCESSED_ATTR = 'data-clipvault-processed';

function applyStylesToUrduElements(rootNode, settings) {
  if (!settings.enabled || !rootNode) return;

  const fs = settings.fontSize || 22;
  const lh = settings.lineHeight || 1.8;

  try {
    const walker = document.createTreeWalker(
      rootNode,
      NodeFilter.SHOW_TEXT,
      null,
      false
    );

    const toProcess = [];
    let node;
    while ((node = walker.nextNode())) {
      if (URDU_REGEX.test(node.nodeValue)) {
        const parent = node.parentElement;
        if (parent && !parent.hasAttribute(PROCESSED_ATTR)) {
          const tag = parent.tagName.toLowerCase();
          if (tag !== 'script' && tag !== 'style' && tag !== 'noscript' && tag !== 'head') {
            toProcess.push(parent);
          }
        }
      }
    }

    toProcess.forEach(el => {
      el.setAttribute(PROCESSED_ATTR, 'true');
      el.style.setProperty('font-family', 'ClipVaultUrduCustom', 'important');
      el.style.setProperty('font-size', `${fs}px`, 'important');
      el.style.setProperty('line-height', `${lh}`, 'important');
    });
  } catch (e) {
    console.warn('[ClipVault] Font scan error:', e);
  }
}

function clearUrduStyling() {
  const styleEl = document.getElementById(STYLE_ELEMENT_ID);
  if (styleEl) styleEl.remove();

  document.querySelectorAll(`[${PROCESSED_ATTR}]`).forEach(el => {
    el.removeAttribute(PROCESSED_ATTR);
    el.style.removeProperty('font-family');
    el.style.removeProperty('font-size');
    el.style.removeProperty('line-height');
  });
}

// ─────────────────────────────────────────────────────────────
// 4. Apply Styles Fully
// ─────────────────────────────────────────────────────────────
function applyFullStyle(settings) {
  if (!settings.enabled) {
    clearUrduStyling();
    return;
  }
  injectFontsAndStyles(settings);
  if (document.body) {
    applyStylesToUrduElements(document.body, settings);
  }
}

// ─────────────────────────────────────────────────────────────
// 5. State & Initialisation
// ─────────────────────────────────────────────────────────────
let activeSettings = {
  enabled: true,
  fontFamily: 'noto_nastaliq',
  fontSize: 22,
  lineHeight: 1.8
};

// Load on page start
chrome.storage.local.get(['customizerSettings', 'googleFontUrls'], (result) => {
  if (result.customizerSettings) {
    activeSettings = { ...activeSettings, ...result.customizerSettings };
  }
  if (result.googleFontUrls) {
    googleFontUrls = { ...googleFontUrls, ...result.googleFontUrls };
  }

  // Apply when DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => applyFullStyle(activeSettings));
  } else {
    applyFullStyle(activeSettings);
  }
});

// Live settings sync — fires when popup changes settings
chrome.storage.onChanged.addListener((changes, namespace) => {
  if (namespace !== 'local') return;

  let needsRefresh = false;

  if (changes.customizerSettings) {
    const newVal = changes.customizerSettings.newValue || {};
    activeSettings = { ...activeSettings, ...newVal };
    needsRefresh = true;
  }

  if (changes.googleFontUrls) {
    googleFontUrls = { ...googleFontUrls, ...changes.googleFontUrls.newValue };
    needsRefresh = true;
  }

  if (needsRefresh) {
    // Short delay so settings are fully applied before re-scan
    setTimeout(() => {
      clearUrduStyling();
      applyFullStyle(activeSettings);
    }, 50);
  }
});

// ─────────────────────────────────────────────────────────────
// 6. MutationObserver — for dynamic pages (Facebook, Twitter etc.)
// ─────────────────────────────────────────────────────────────
const observer = new MutationObserver((mutations) => {
  if (!activeSettings.enabled) return;

  mutations.forEach(mutation => {
    mutation.addedNodes.forEach(node => {
      if (node.nodeType === Node.ELEMENT_NODE) {
        applyStylesToUrduElements(node, activeSettings);
      }
    });
  });
});

if (document.body) {
  observer.observe(document.body, { childList: true, subtree: true });
} else {
  document.addEventListener('DOMContentLoaded', () => {
    observer.observe(document.body, { childList: true, subtree: true });
  });
}
