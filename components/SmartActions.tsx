import React, { useState } from 'react';
import * as Clipboard from 'expo-clipboard';
import { Alert, Linking, StyleSheet, Text, TouchableOpacity, View, ActivityIndicator } from 'react-native';
import { useRouter } from 'expo-router';
import { AppLanguage, ClipItem } from '../services/types';
import { ThemeColors } from '../theme/colors';
import { LocalAIProcessor } from '../services/CoreServices';
import { useClipStore } from '../store/useClipStore';

export function SmartActions({ clip, lang, T }: { clip: ClipItem; lang: AppLanguage; T: ThemeColors }) {
  const router = useRouter();
  const settings = useClipStore(state => state.settings);
  const [loadingSummary, setLoadingSummary] = useState(false);
  const smartStyles = React.useMemo(() => getSmartStyles(T, lang), [T, lang]);
  const actions: Array<{ label: string; color: string; loading?: boolean; onPress: () => void }> = [];

  const showAiResult = (title: string, result: string) => {
    Alert.alert(title, result, [
      { text: lang === 'en' ? 'Cancel' : 'منسوخ', style: 'cancel' },
      {
        text: lang === 'en' ? 'Copy' : 'کاپی',
        onPress: () => void Clipboard.setStringAsync(result),
      },
    ]);
  };

  if (clip.category === 'URL') {
    actions.push({
      label: lang === 'en' ? 'Open' : 'کھولیں',
      color: '#14b8a6',
      onPress: () => {
        const url = clip.text.startsWith('http') ? clip.text : `https://${clip.text}`;
        void Linking.openURL(url);
      },
    });
  }

  if (clip.category === 'Phone') {
    actions.push({
      label: lang === 'en' ? 'Call' : 'کال',
      color: T.green,
      onPress: () => void Linking.openURL(`tel:${clip.text.replace(/\s/g, '')}`),
    });
    actions.push({
      label: 'WhatsApp',
      color: '#25d366',
      onPress: () => void Linking.openURL(`https://wa.me/${clip.text.replace(/\D/g, '')}`),
    });
  }

  if (clip.category === 'Email') {
    actions.push({
      label: lang === 'en' ? 'Email' : 'ای میل',
      color: '#a855f7',
      onPress: () => void Linking.openURL(`mailto:${clip.text}`),
    });
  }

  if (clip.text.trim().length > 25) {
    actions.push({
      label: lang === 'en' ? 'Clean' : 'صاف کریں',
      color: '#38bdf8',
      onPress: () => showAiResult(lang === 'en' ? 'Clean Text' : 'صاف متن', cleanText(clip.text, lang)),
    });

    actions.push({
      label: lang === 'en' ? '✨ AI Summary' : '✨ اے آئی خلاصہ',
      color: '#f59e0b',
      loading: loadingSummary,
      onPress: async () => {
        const apiKey = settings.geminiApiKey?.trim();
        if (!apiKey) {
          Alert.alert(
            lang === 'en' ? 'API Key Required' : 'اے پی آئی کی درکار ہے',
            lang === 'en'
              ? 'Please add your Gemini API Key in settings to use the AI Summary feature.'
              : 'اے آئی خلاصہ فیچر استعمال کرنے کے لیے براہ کرم ترتیبات میں جا کر اپنی جیمینائی اے پی آئی کی درج کریں۔',
            [
              { text: lang === 'en' ? 'Cancel' : 'منسوخ', style: 'cancel' },
              {
                text: lang === 'en' ? 'Settings' : 'ترتیبات',
                onPress: () => router.push('/(tabs)/settings?focusApiKey=true'),
              },
            ]
          );
          return;
        }

        setLoadingSummary(true);
        try {
          const summaryText = await LocalAIProcessor.summarizeWithGemini(clip.text, apiKey, lang);
          showAiResult(lang === 'en' ? 'AI Summary' : 'اے آئی خلاصہ', summaryText);
        } catch (err) {
          Alert.alert(
            lang === 'en' ? 'AI Summary Failed' : 'اے آئی خلاصہ ناکام',
            err instanceof Error ? err.message : String(err)
          );
        } finally {
          setLoadingSummary(false);
        }
      },
    });
  }

  if (actions.length === 0) return null;

  return (
    <View style={smartStyles.row}>
      {actions.map(a => (
        <TouchableOpacity
          key={a.label}
          disabled={a.loading}
          style={[smartStyles.btn, { borderColor: `${a.color}55`, backgroundColor: `${a.color}11` }]}
          onPress={a.onPress}
          activeOpacity={0.75}
        >
          {a.loading ? (
            <ActivityIndicator size="small" color={a.color} style={{ height: 16 }} />
          ) : (
            <Text style={[smartStyles.text, { color: a.color }]} numberOfLines={1}>{a.label}</Text>
          )}
        </TouchableOpacity>
      ))}
    </View>
  );
}

function cleanText(value: string, lang: AppLanguage): string {
  const normalized = value
    .replace(/[ \t]+/g, ' ')
    .replace(/\s+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/\s+([,.!?؟،۔])/g, '$1')
    .trim();

  if (lang === 'ur') {
    return normalized
      .replace(/\bامين\b/g, 'آمین')
      .replace(/\bامین\b/g, 'آمین')
      .replace(/\bاپ\b/g, 'آپ')
      .replace(/\bاج\b/g, 'آج')
      .replace(/\bاسانی\b/g, 'آسانی')
      .replace(/\bاسان\b/g, 'آسان')
      .replace(/\bاواز\b/g, 'آواز');
  }

  return normalized.replace(/\b(teh|adn|recieve|adress|mesage|setings|clipbord|keybord)\b/gi, match => {
    const fixes: Record<string, string> = {
      teh: 'the',
      adn: 'and',
      recieve: 'receive',
      adress: 'address',
      mesage: 'message',
      setings: 'settings',
      clipbord: 'clipboard',
      keybord: 'keyboard',
    };
    return fixes[match.toLowerCase()] ?? match;
  });
}

function summarizeText(value: string, lang: AppLanguage): string {
  const clean = cleanText(value, lang);
  const parts = clean
    .replace(/([.!?؟۔])\s+/g, '$1\n')
    .split(/\n+/)
    .map(item => item.trim())
    .filter(Boolean);

  if (parts.length <= 2) return clean;
  return parts.slice(0, 2).join(' ');
}

import { NASTALEEQ_FONT } from '../services/i18n';

const getSmartStyles = (T: ThemeColors, lang: AppLanguage) => {
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;
  const ff = nasta ? { fontFamily: nasta } : {};
  return StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', paddingHorizontal: 12, paddingBottom: 8, gap: 6 },
  btn: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6, minWidth: 70, alignItems: 'center' },
  text: { fontSize: 12, fontWeight: '700', ...ff },
});
};
