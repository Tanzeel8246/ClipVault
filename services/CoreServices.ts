import AsyncStorage from '@react-native-async-storage/async-storage';
import * as FileSystem from 'expo-file-system';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { NativeModules, Platform } from 'react-native';
import { ClipItem, ClipSettings, ClipStats, DEFAULT_SETTINGS, SensitiveDetection, SensitiveType, SETTINGS_KEY } from './types';

let initPromise: Promise<void> | null = null;
let initialized = false;

// ─────────────────────────────────────────────────────────────
// Security & Encryption & Utilities
// ─────────────────────────────────────────────────────────────
export const countWords = (value: string) => {
  const text = value.trim();
  return text ? text.split(/\s+/).length : 0;
};

export function getErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'کچھ غلط ہو گیا۔';
}

export async function readSettings(): Promise<ClipSettings> {
  try {
    const saved = await AsyncStorage.getItem(SETTINGS_KEY);
    return saved ? { ...DEFAULT_SETTINGS, ...JSON.parse(saved) } : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}

const escapeHtml = (value: string) =>
  value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');

const quoteCsv = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`;

type ClipVaultBackup = {
  app: 'ClipVault';
  schemaVersion: 2;
  createdAt: string;
  clipCount: number;
  clips: ClipItem[];
};

type NativeClipDatabaseModule = {
  saveClipDetailed?: (
    id: string,
    text: string,
    category: string,
    timestamp: string,
    isFavorite: boolean,
    isPinned: boolean,
  ) => Promise<boolean>;
  hasClipText?: (text: string) => Promise<boolean>;
  getClips?: (
    search: string,
    category: string,
    favoritesOnly: boolean,
    sensitiveOnly: boolean,
    limit: number,
    offset: number,
  ) => Promise<string>;
  getStats?: () => Promise<string>;
  toggleFavorite?: (id: string, value: boolean) => Promise<boolean>;
  togglePin?: (id: string, value: boolean) => Promise<boolean>;
  toggleSensitive?: (id: string, value: boolean) => Promise<boolean>;
  updateClipText?: (id: string, text: string, category: string, sensitive: boolean) => Promise<boolean>;
  deleteClip?: (id: string) => Promise<boolean>;
  bulkDeleteClips?: (ids: string[]) => Promise<boolean>;
  bulkPinClips?: (ids: string[], value: boolean) => Promise<boolean>;
  bulkFavoriteClips?: (ids: string[], value: boolean) => Promise<boolean>;
  clearAllClips?: () => Promise<boolean>;
  clearSensitiveClips?: () => Promise<number>;
  applyClipMaintenance?: (autoDelete: string, maxClips: string) => Promise<boolean>;
};

const nativeClipDb = NativeModules.AutoClipboardMonitor as NativeClipDatabaseModule | undefined;

const ensureNativeClipDb = () => {
  if (Platform.OS !== 'android' || !nativeClipDb?.getClips) {
    throw new Error('Native ClipVault storage is not available on this device.');
  }
  return nativeClipDb;
};

// ─────────────────────────────────────────────────────────────
// SensitiveDetector
// ─────────────────────────────────────────────────────────────
export class SensitiveDetector {
  private static readonly OTP_RE = /^\d{4,8}$|(?:otp|code|verify|pin|verification)[:\s]*(\d{4,8})/i;
  private static readonly PASS_RE = /(?:password|passwd|pwd|secret|api[_\s-]?key|token|bearer|auth)[:\s=]+\S+/i;
  private static readonly CARD_RE = /\b(?:\d[\s-]?){13,19}\b/;
  private static readonly CNIC_RE = /\b\d{5}[-\s]?\d{7}[-\s]?\d{1}\b/;
  private static readonly TOKEN_RE = /\b[A-Za-z0-9+/=_-]{32,}\b|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/;

  static detect(text: string): SensitiveDetection {
    const t = text.trim();

    if (this.OTP_RE.test(t)) return { isSensitive: true, sensitiveType: 'otp' };
    if (this.PASS_RE.test(t)) return { isSensitive: true, sensitiveType: 'password' };
    if (this.CNIC_RE.test(t)) return { isSensitive: true, sensitiveType: 'cnic' };
    if (this.CARD_RE.test(t) && /\d{4}/.test(t.replace(/\s/g, ''))) {
      const digits = t.replace(/\D/g, '');
      if (digits.length >= 13 && digits.length <= 19) {
        return { isSensitive: true, sensitiveType: 'card' };
      }
    }
    if (this.TOKEN_RE.test(t)) return { isSensitive: true, sensitiveType: 'token' };

    return { isSensitive: false };
  }

  static mask(text: string, sensitiveType?: SensitiveType): string {
    switch (sensitiveType) {
      case 'otp':
      case 'card':
        return text.replace(/\d/g, '•');
      case 'cnic':
        return text.replace(/\d(?=.{4})/g, '•');
      case 'password':
      case 'token':
        return '••••••••••••';
      default:
        return text.substring(0, 4) + ' ••••••••••• ' + text.slice(-4);
    }
  }
}

// ─────────────────────────────────────────────────────────────
// DatabaseService
// ─────────────────────────────────────────────────────────────
export class DatabaseService {
  static init(): Promise<void> {
    if (initialized) return Promise.resolve();
    if (initPromise) return initPromise;

    initPromise = (async () => {
      ensureNativeClipDb();
      initialized = true;
    })().catch(error => {
      initPromise = null;
      initialized = false;
      throw error;
    });

    return initPromise;
  }

  private static normalizeNativeClip(row: any): ClipItem {
    const text = String(row?.text ?? '');
    return {
      id: String(row?.id ?? `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
      text,
      category: String(row?.category || Categorizer.detect(text)),
      timestamp: String(row?.timestamp || new Date().toISOString()),
      isFavorite: row?.isFavorite === true || row?.isFavorite === 1,
      isPinned: row?.isPinned === true || row?.isPinned === 1,
      wordCount: Number(row?.wordCount ?? countWords(text)),
      charCount: Number(row?.charCount ?? text.length),
      tags: typeof row?.tags === 'string' ? row.tags : '[]',
      isDeleted: row?.isDeleted === true || row?.isDeleted === 1,
      isSensitive: row?.isSensitive === true || row?.isSensitive === 1,
      sensitiveType: row?.sensitiveType || undefined,
    };
  }

  static async saveClip(clip: ClipItem): Promise<boolean> {
    await DatabaseService.init();

    const text = clip.text.trim();
    if (!text) return false;
    const db = ensureNativeClipDb();
    return !!(await db.saveClipDetailed?.(
      clip.id,
      text,
      clip.category || Categorizer.detect(text),
      clip.timestamp || new Date().toISOString(),
      !!clip.isFavorite,
      !!clip.isPinned,
    ));
  }

  static async hasClipText(value: string): Promise<boolean> {
    await DatabaseService.init();
    const text = value.trim();
    if (!text) return false;
    const db = ensureNativeClipDb();
    return !!(await db.hasClipText?.(text));
  }

  static async getClips(options: {
    search?: string;
    category?: string;
    favoritesOnly?: boolean;
    sensitiveOnly?: boolean;
    limit?: number;
    offset?: number;
  } = {}): Promise<ClipItem[]> {
    await DatabaseService.init();

    const db = ensureNativeClipDb();
    const limit = Math.max(1, Math.min(Number(options.limit) || 100, 10000));
    const offset = Math.max(0, Number(options.offset) || 0);
    const raw = await db.getClips?.(
      options.search?.trim() ?? '',
      options.category ?? '',
      !!options.favoritesOnly,
      !!options.sensitiveOnly,
      limit,
      offset,
    );
    const rows = JSON.parse(raw || '[]');
    return Array.isArray(rows) ? rows.map(DatabaseService.normalizeNativeClip) : [];
  }

  static async toggleFavorite(id: string, value: boolean): Promise<void> {
    await DatabaseService.init();
    await ensureNativeClipDb().toggleFavorite?.(id, value);
  }

  static async togglePin(id: string, value: boolean): Promise<void> {
    await DatabaseService.init();
    await ensureNativeClipDb().togglePin?.(id, value);
  }

  static async toggleSensitive(id: string, value: boolean): Promise<void> {
    await DatabaseService.init();
    await ensureNativeClipDb().toggleSensitive?.(id, value);
  }

  static async updateClipText(id: string, text: string, category: string = '', sensitive: boolean = false): Promise<boolean> {
    await DatabaseService.init();
    return !!(await ensureNativeClipDb().updateClipText?.(id, text, category, sensitive));
  }

  static async deleteClip(id: string): Promise<void> {
    await DatabaseService.init();
    await ensureNativeClipDb().deleteClip?.(id);
  }

  static async bulkDelete(ids: string[]): Promise<void> {
    await DatabaseService.init();
    if (!ids.length) return;
    await ensureNativeClipDb().bulkDeleteClips?.(ids);
  }

  static async bulkPin(ids: string[], value: boolean): Promise<void> {
    await DatabaseService.init();
    if (!ids.length) return;
    await ensureNativeClipDb().bulkPinClips?.(ids, value);
  }

  static async bulkFavorite(ids: string[], value: boolean): Promise<void> {
    await DatabaseService.init();
    if (!ids.length) return;
    await ensureNativeClipDb().bulkFavoriteClips?.(ids, value);
  }

  static async clearAll(): Promise<void> {
    await DatabaseService.init();
    await ensureNativeClipDb().clearAllClips?.();
  }

  static async clearSensitive(): Promise<number> {
    await DatabaseService.init();
    return (await ensureNativeClipDb().clearSensitiveClips?.()) ?? 0;
  }

  static async applyMaintenance(settings: Pick<ClipSettings, 'autoDelete' | 'maxClips'>): Promise<void> {
    await DatabaseService.init();
    await ensureNativeClipDb().applyClipMaintenance?.(settings.autoDelete, settings.maxClips);
  }

  static async getStats(): Promise<ClipStats> {
    await DatabaseService.init();

    const raw = await ensureNativeClipDb().getStats?.();
    const parsed = JSON.parse(raw || '{}');
    const categories = parsed.categories && typeof parsed.categories === 'object' ? parsed.categories : {};

    return {
      total: Number(parsed.total ?? 0),
      today: Number(parsed.today ?? 0),
      favorites: Number(parsed.favorites ?? 0),
      sensitive: Number(parsed.sensitive ?? 0),
      categories,
    };
  }

  static async getAllForExport(): Promise<ClipItem[]> {
    return DatabaseService.getClips({ limit: 10000 });
  }

  static async createBackupPayload(): Promise<string> {
    const clips = await DatabaseService.getAllForExport();
    const backup: ClipVaultBackup = {
      app: 'ClipVault',
      schemaVersion: 2,
      createdAt: new Date().toISOString(),
      clipCount: clips.length,
      clips,
    };

    return JSON.stringify(backup, null, 2);
  }

  static async restoreFromBackupText(value: string): Promise<number> {
    await DatabaseService.init();

    const parsed = JSON.parse(value) as Partial<ClipVaultBackup>;
    if (parsed.app !== 'ClipVault' || !Array.isArray(parsed.clips)) {
      throw new Error('This is not a valid ClipVault backup file.');
    }

    let restored = 0;

    for (const item of parsed.clips) {
      const text = String(item.text ?? '').trim();
      if (!text) continue;

      const category = String(item.category || Categorizer.detect(text));
      const timestamp = String(item.timestamp || new Date().toISOString());
      const wordCount = Number(item.wordCount ?? countWords(text));
      const charCount = Number(item.charCount ?? text.length);
      const sensitive = SensitiveDetector.detect(text);
      const ok = await DatabaseService.saveClip({
        id: String(item.id || `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
        text,
        category,
        timestamp,
        isFavorite: !!item.isFavorite,
        isPinned: !!item.isPinned,
        wordCount,
        charCount,
        tags: typeof item.tags === 'string' ? item.tags : '[]',
        isDeleted: false,
        isSensitive: sensitive.isSensitive,
        sensitiveType: sensitive.sensitiveType,
      });
      if (ok) restored += 1;
    }

    return restored;
  }
}

// ─────────────────────────────────────────────────────────────
// Categorizer
// ─────────────────────────────────────────────────────────────
export class Categorizer {
  static detect(value: string): string {
    const text = value.trim();

    if (/^(https?:\/\/|www\.)\S+$/i.test(text)) return 'URL';
    if (/^[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}$/.test(text)) return 'Email';
    if (/^(\+?\d[\d\s().-]{8,}\d)$/.test(text)) return 'Phone';
    if (/^\d+(\.\d+)?$/.test(text)) return 'Number';
    if (/^\d{1,2}[/-]\d{1,2}[/-]\d{2,4}/.test(text)) return 'Date';
    if (/(^|\n)\s*(import|export|function|const|let|var|class|def)\b|=>|<\/?[A-Za-z][\s\S]*>/m.test(text)) {
      return 'Code';
    }
    if (text.split('\n').length > 5 || text.length > 500) return 'Long Text';
    if (text.split(/\s+/).length <= 3) return 'Short';

    return 'Text';
  }
}

// ─────────────────────────────────────────────────────────────
// Exporter
// ─────────────────────────────────────────────────────────────
export class Exporter {
  static async exportTXT(clips: ClipItem[]): Promise<string> {
    let content = `ClipVault Export — MindCrafts_AI\n`;
    content += `Generated: ${new Date().toLocaleString()}\n`;
    content += `Total Clips: ${clips.length}\n`;
    content += `${'═'.repeat(50)}\n\n`;

    clips.forEach((clip, index) => {
      const sensitive = clip.isSensitive ? ' [SENSITIVE]' : '';
      content += `[${index + 1}] ${clip.category}${sensitive} — ${new Date(clip.timestamp).toLocaleString()}\n`;
      content += `${clip.isSensitive ? '(Content masked for privacy)' : clip.text}\n`;
      content += `${'─'.repeat(40)}\n\n`;
    });

    const path = await Exporter.writeFile(`clipvault_${Date.now()}.txt`, content);
    await Exporter.share(path);
    return path;
  }

  static async exportCSV(clips: ClipItem[]): Promise<string> {
    const header = ['ID', 'Text', 'Category', 'Timestamp', 'Favorite', 'Pinned', 'Words', 'Characters', 'Sensitive']
      .map(quoteCsv)
      .join(',');
    const rows = clips.map(clip =>
      [
        clip.id,
        clip.isSensitive ? '(masked)' : clip.text,
        clip.category,
        clip.timestamp,
        clip.isFavorite ? 'yes' : 'no',
        clip.isPinned ? 'yes' : 'no',
        clip.wordCount,
        clip.charCount,
        clip.isSensitive ? (clip.sensitiveType ?? 'yes') : 'no',
      ]
        .map(quoteCsv)
        .join(','),
    );

    const path = await Exporter.writeFile(`clipvault_${Date.now()}.csv`, [header, ...rows].join('\n'));
    await Exporter.share(path);
    return path;
  }

  static async exportPDF(clips: ClipItem[]): Promise<string> {
    const rows = clips
      .map((clip, index) => {
        const displayText = clip.isSensitive
          ? '<em style="color:#888">(Content masked for privacy)</em>'
          : escapeHtml(clip.text).replace(/\n/g, '<br>');
        const timestamp = escapeHtml(new Date(clip.timestamp).toLocaleString());
        const category = escapeHtml(clip.category);
        const sensitive = clip.isSensitive ? ' <span class="sensitive">🔒 Sensitive</span>' : '';

        return `
          <section class="clip">
            <div class="meta">#${index + 1} ${category}${sensitive} — ${timestamp}</div>
            <p>${displayText}</p>
          </section>
        `;
      })
      .join('');

    const html = `
      <!doctype html>
      <html>
        <head>
          <meta charset="utf-8">
          <style>
            body { font-family: Arial, sans-serif; padding: 24px; color: #15151f; }
            h1 { margin-bottom: 4px; color: #6366f1; }
            .brand { color: #6366f1; font-size: 13px; margin-bottom: 4px; }
            .summary { color: #666; margin-bottom: 24px; }
            .clip { border-left: 4px solid #6366f1; background: #f7f7fb; margin: 14px 0; padding: 12px 14px; border-radius: 4px; }
            .meta { color: #555; font-size: 12px; margin-bottom: 8px; }
            p { white-space: normal; line-height: 1.45; }
            .sensitive { background: #fef3c7; color: #92400e; border-radius: 4px; padding: 2px 6px; font-size: 11px; }
          </style>
        </head>
        <body>
          <div class="brand">MindCrafts_AI</div>
          <h1>ClipVault Export</h1>
          <div class="summary">${escapeHtml(new Date().toLocaleString())} — ${clips.length} items</div>
          ${rows}
        </body>
      </html>
    `;

    const { uri } = await Print.printToFileAsync({ html });
    await Exporter.share(uri);
    return uri;
  }

  static async exportWord(clips: ClipItem[]): Promise<string> {
    const rows = clips
      .map((clip, index) => {
        const displayText = clip.isSensitive
          ? '<em style="color:#888">(Content masked for privacy)</em>'
          : escapeHtml(clip.text).replace(/\n/g, '<br>');
        const timestamp = escapeHtml(new Date(clip.timestamp).toLocaleString());
        const category = escapeHtml(clip.category);
        const sensitive = clip.isSensitive ? ' [Sensitive]' : '';

        return `
          <div style="border-bottom: 1px solid #d1d5db; padding: 12px 0; margin-bottom: 12px;">
            <p style="color: #4338ca; font-weight: bold; margin-bottom: 4px;">#${index + 1} [${category}]${sensitive} — ${timestamp}</p>
            <p style="white-space: pre-wrap; font-size: 11pt; line-height: 1.5; color: #111827;">${displayText}</p>
          </div>
        `;
      })
      .join('');

    const html = `
      <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:word' xmlns='http://www.w3.org/TR/REC-html40'>
        <head>
          <meta charset="utf-8">
          <title>ClipVault Word Export</title>
          <style>
            body { font-family: 'Segoe UI', Calibri, Arial, sans-serif; padding: 24px; color: #111827; }
            h1 { color: #4338ca; margin-bottom: 4px; }
            .meta { color: #6b7280; font-size: 12px; margin-bottom: 20px; }
          </style>
        </head>
        <body>
          <h1>ClipVault Export</h1>
          <div class="meta">${escapeHtml(new Date().toLocaleString())} — ${clips.length} clips</div>
          <hr style="border: none; border-top: 2px solid #6366f1; margin-bottom: 20px;" />
          ${rows}
        </body>
      </html>
    `;

    const path = await Exporter.writeFile(`clipvault_export_${Date.now()}.doc`, html);
    await Exporter.share(path);
    return path;
  }

  static async exportBackupJSON(): Promise<string> {
    const content = await DatabaseService.createBackupPayload();
    const path = await Exporter.writeFile(`clipvault_backup_${Date.now()}.json`, content);
    await Exporter.share(path);
    return path;
  }

  private static async writeFile(fileName: string, content: string): Promise<string> {
    const directory = FileSystem.documentDirectory ?? FileSystem.cacheDirectory;
    if (!directory) throw new Error('No writable export directory is available.');

    const path = `${directory}${fileName}`;
    await FileSystem.writeAsStringAsync(path, content);
    return path;
  }

  private static async share(path: string): Promise<void> {
    if (await Sharing.isAvailableAsync()) {
      await Sharing.shareAsync(path);
    }
  }
}

// ─────────────────────────────────────────────────────────────
// LocalAIProcessor
// ─────────────────────────────────────────────────────────────
export class LocalAIProcessor {
  // 1. Language Detection
  static detectLanguage(text: string): string {
    if (!text || text.length < 3) return 'en';
    // If text contains more than 10% Urdu/Arabic characters, classify as ur
    const urduMatch = text.match(/[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]/g);
    if (urduMatch && urduMatch.length > text.length * 0.1) {
      return 'ur';
    }
    return 'en';
  }

  // 2. Entity Extraction (Custom Regex-based Local NLP)
  static extractEntities(text: string): string[] {
    if (!text || text.length < 5) return [];
    
    const entities = new Set<string>();

    // Extract potential names (Capitalized words)
    const nameMatch = text.match(/\b[A-Z][a-z]+ [A-Z][a-z]+\b/g);
    if (nameMatch) {
      nameMatch.slice(0, 2).forEach(p => entities.add(`👤 ${p}`));
    }

    // Extract hashtags as topics
    const topicMatch = text.match(/#\w+/g);
    if (topicMatch) {
      topicMatch.slice(0, 3).forEach(t => entities.add(`🏷️ ${t.replace('#', '')}`));
    }

    // Extract potential currency/money
    const moneyMatch = text.match(/\$[0-9,]+(\.[0-9]{2})?|\b[0-9,]+ (USD|EUR|PKR)\b/g);
    if (moneyMatch) {
      moneyMatch.slice(0, 2).forEach(m => entities.add(`💰 ${m}`));
    }
    
    return Array.from(entities);
  }

  // 3. Extractive Summarization (Offline)
  static summarize(text: string): string {
    const minLength = 300;
    if (!text || text.length < minLength) return text;
    
    // Simple naive extractive summarization:
    const sentences = text.match(/[^.!?]+[.!?]+/g) || text.split('\n').filter(Boolean);
    if (sentences.length <= 2) return text;
    
    // Keep first sentence (often intro)
    const summary = [sentences[0].trim()];
    
    // Find longest sentence as key point
    let longest = '';
    let maxWords = 0;
    for (let i = 1; i < sentences.length - 1; i++) {
      const s = sentences[i].trim();
      const words = s.split(/\s+/).length;
      if (words > maxWords && words < 40) { // Avoid run-on sentences
        longest = s;
        maxWords = words;
      }
    }
    
    if (longest && !summary.includes(longest)) {
      summary.push(longest);
    }
    
    // Keep last sentence (often conclusion)
    const last = sentences[sentences.length - 1].trim();
    if (!summary.includes(last) && summary.length < 3) {
      summary.push(last);
    }
    
    return summary.join(' ');
  }
  
  // Enrich Clip
  static enrichClip(clip: ClipItem): ClipItem {
    if (clip.isSensitive) return clip; // Skip sensitive
    
    if (!clip.detectedLanguage) {
      clip.detectedLanguage = this.detectLanguage(clip.text);
    }
    if (!clip.extractedEntities || clip.extractedEntities.length === 0) {
      clip.extractedEntities = this.extractEntities(clip.text);
    }
    
    return clip;
  }

  // Gemini API Summarization
  static async summarizeWithGemini(text: string, apiKey: string, lang: 'ur' | 'en'): Promise<string> {
    const prompt = lang === 'ur'
      ? `درج ذیل متن کا انتہائی مختصر، جامع اور سادہ الفاظ میں نچوڑ/خلاصہ (صرف 2 سے 3 جملوں میں) اردو زبان میں لکھیں۔ یاد رہے کہ یہ تشریح یا تفصیل نہ ہو، بلکہ صرف مرکزی پیغام اور مفہوم واضح کرے تاکہ تفصیلات کے لیے اصل تحریر پڑھی جا سکے۔\n\nمتن:\n${text}`
      : `Provide an extremely brief, concise summary (strictly 2 to 3 sentences) of the following text in English. It must NOT be an explanation or elaboration. Focus only on the core message and main takeaway, so that the reader has to read the text for details.\n\nText:\n${text}`;
      
    const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey}`;
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        contents: [{
          parts: [{
            text: prompt
          }]
        }]
      })
    });
    
    if (!response.ok) {
      const errData = await response.json().catch(() => ({}));
      throw new Error(errData?.error?.message || `HTTP ${response.status}`);
    }
    
    const data = await response.json();
    const summary = data?.candidates?.[0]?.content?.parts?.[0]?.text;
    if (!summary) {
      throw new Error(lang === 'ur' ? 'جیمینائی سے کوئی جواب موصول نہیں ہوا۔' : 'No summary returned from Gemini.');
    }
    
    return summary.trim();
  }
}

