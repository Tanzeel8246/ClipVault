import * as FileSystem from 'expo-file-system';

const MIRROR_FILE = 'keyboard_clips.json';
const MAX_MIRROR_CLIPS = 40;

const mirrorPath = () => (FileSystem.documentDirectory ? `${FileSystem.documentDirectory}${MIRROR_FILE}` : '');

async function readMirror(): Promise<string[]> {
  const path = mirrorPath();
  if (!path) return [];

  try {
    const info = await FileSystem.getInfoAsync(path);
    if (!info.exists) return [];

    const raw = await FileSystem.readAsStringAsync(path);
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter(v => typeof v === 'string' && v.trim().length > 0) : [];
  } catch {
    return [];
  }
}

export class KeyboardMirror {
  static async addClip(text: string): Promise<void> {
    const value = text.trim();
    const path = mirrorPath();
    if (!value || !path) return;

    const existing = await readMirror();
    const next = [value, ...existing.filter(item => item !== value)].slice(0, MAX_MIRROR_CLIPS);
    await FileSystem.writeAsStringAsync(path, JSON.stringify(next));
  }
}
