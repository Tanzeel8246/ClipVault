import React, { useEffect, useRef } from 'react';
import { Animated, StyleSheet, Text, View } from 'react-native';
import { t } from '../services/i18n';
import { AppLanguage } from '../services/types';
import { ThemeColors } from '../theme/colors';

export function EmptyState({ search, lang, T }: { search: string; lang: AppLanguage; T: ThemeColors }) {
  const emptyStyles = React.useMemo(() => getEmptyStyles(T, lang), [T, lang]);
  const pulse = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    const anim = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1.1, duration: 1400, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 1, duration: 1400, useNativeDriver: true }),
      ]),
    );
    anim.start();
    return () => anim.stop();
  }, [pulse]);

  return (
    <View style={emptyStyles.wrap}>
      <Animated.Text style={[emptyStyles.icon, { transform: [{ scale: pulse }] }]}>📋</Animated.Text>
      <Text style={emptyStyles.title}>{search ? (lang === 'en' ? 'No results found' : 'کوئی نتیجہ نہیں') : t('noClips', lang)}</Text>
      <Text style={emptyStyles.hint}>
        {search
          ? (lang === 'en' ? `No clip found for "${search}"` : `"${search}" کے لیے کوئی کلپ نہیں ملا`)
          : t('noClipsHint', lang)}
      </Text>
    </View>
  );
}

import { NASTALEEQ_FONT } from '../services/i18n';

const getEmptyStyles = (T: ThemeColors, lang: AppLanguage) => {
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;
  const ff = nasta ? { fontFamily: nasta } : {};
  return StyleSheet.create({
  wrap: { alignItems: 'center', paddingTop: 80, paddingHorizontal: 32 },
  icon: { fontSize: 64, marginBottom: 20 },
  title: { color: T.text, fontSize: 20, fontWeight: '700', marginBottom: 8, ...ff },
  hint: { color: T.muted, fontSize: 14, textAlign: 'center', lineHeight: 22, ...ff },
});
};
