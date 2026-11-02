import * as Haptics from 'expo-haptics';
import React, { useRef, useState } from 'react';
import {
  Animated,
  PanResponder,
  Pressable,
  Share,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { SensitiveDetector } from '../services/CoreServices';
import { t, NASTALEEQ_FONT } from '../services/i18n';
import { AppLanguage, ClipItem } from '../services/types';
import { ThemeColors } from '../theme/colors';
import { getCategoryColors } from './CategoryUtils';
import { SmartActions } from './SmartActions';

export function relativeTime(timestamp: string, lang: AppLanguage): string {
  const diff = Date.now() - new Date(timestamp).getTime();
  if (lang === 'en') {
    if (diff < 60_000) return 'Just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return new Date(timestamp).toLocaleDateString('en-US');
  }
  if (diff < 60_000) return 'ابھی';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}م پہلے`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}گھ پہلے`;
  return new Date(timestamp).toLocaleDateString('ur-PK');
}

type ClipCardProps = {
  clip: ClipItem;
  maskSensitive: boolean;
  onFavorite: (id: string, value: boolean) => void;
  onDelete: (id: string) => void;
  onCopy: (text: string) => void;
  onPin: (id: string, value: boolean) => void;
  onEdit?: (clip: ClipItem) => void;
  onMarkNormal?: (id: string) => void;
  lang: AppLanguage;
  T: ThemeColors;
  selected?: boolean;
  selectionMode?: boolean;
  onSelect?: () => void;
  onLongPressCard?: () => void;
};

export function ClipCard({ clip, maskSensitive, onFavorite, onDelete, onCopy, onPin, onEdit, onMarkNormal, lang, T, selected, selectionMode, onSelect, onLongPressCard }: ClipCardProps) {
  const styles = React.useMemo(() => getCardStyles(T, lang), [T, lang]);
  const categoryColor = getCategoryColors(T)[clip.category] ?? T.accent;
  const scale = useRef(new Animated.Value(1)).current;
  const swipeAnim = useRef(new Animated.Value(0)).current;
  const [expanded, setExpanded] = useState(false);
  const [revealed, setRevealed] = useState(false);
  const [showMoreActions, setShowMoreActions] = useState(false);

  const isMasked = clip.isSensitive && maskSensitive && !revealed;

  // ── Swipe PanResponder ──────────────────────────────────
  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => false,
      onMoveShouldSetPanResponder: (_, gs) => Math.abs(gs.dx) > 10 && Math.abs(gs.dy) < 20,
      onPanResponderMove: (_, gs) => {
        if (gs.dx < 0) swipeAnim.setValue(gs.dx);
      },
      onPanResponderRelease: (_, gs) => {
        if (gs.dx < -80) {
          // Swipe left → delete
          Animated.timing(swipeAnim, { toValue: -400, duration: 200, useNativeDriver: true }).start(() => {
            onDelete(clip.id);
            swipeAnim.setValue(0);
          });
        } else if (gs.dx > 60) {
          // Swipe right → favorite
          void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
          onFavorite(clip.id, !clip.isFavorite);
          Animated.spring(swipeAnim, { toValue: 0, useNativeDriver: true }).start();
        } else {
          Animated.spring(swipeAnim, { toValue: 0, useNativeDriver: true }).start();
        }
      },
    }),
  ).current;

  const preview = expanded
    ? clip.text
    : clip.text.substring(0, 140) + (clip.text.length > 140 ? '…' : '');

  const pressCard = () => {
    if (selectionMode) {
      void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      onSelect?.();
      return;
    }
    Animated.sequence([
      Animated.timing(scale, { toValue: 0.97, duration: 70, useNativeDriver: true }),
      Animated.timing(scale, { toValue: 1, duration: 70, useNativeDriver: true }),
    ]).start();
    setExpanded(v => !v);
  };

  return (
    <View style={{ marginBottom: 10, position: 'relative' }}>
      {/* Swipe hint */}
      <View style={[StyleSheet.absoluteFill, styles.swipeHintContainer]}>
        <Text style={[styles.swipeHintText, { color: T.green }]}>{t('swipeFav', lang)}</Text>
        <Text style={[styles.swipeHintText, { color: T.red, textAlign: 'right' }]}>{t('swipeLeft', lang)}</Text>
      </View>

      <Animated.View
        style={[
          styles.card,
          clip.isPinned && { borderColor: `${T.amber}66` },
          clip.isSensitive && { borderColor: `${T.red}44` },
          selected && { borderColor: T.accent, borderWidth: 2, backgroundColor: `${T.accent}11` },
          { transform: [{ scale }, { translateX: swipeAnim }] },
        ]}
        {...panResponder.panHandlers}
      >
        <Pressable
        onPress={pressCard}
        onLongPress={() => {
          if (selectionMode) {
            onSelect?.();
          } else if (onLongPressCard) {
            onLongPressCard();
          } else {
            void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            onCopy(clip.text);
          }
        }}
      >
        {/* Header */}
        <View style={styles.cardHeader}>
          <View style={[styles.catBadge, { backgroundColor: `${categoryColor}22` }]}>
            <Text style={[styles.catText, { color: categoryColor }]}>{t(('cat' + clip.category.replace(' ', '')) as any, lang)}</Text>
          </View>
          {clip.detectedLanguage && (
            <View style={[styles.catBadge, { backgroundColor: `${T.border}88`, marginLeft: 4 }]}>
               <Text style={[styles.catText, { color: T.text }]}>{clip.detectedLanguage.toUpperCase()}</Text>
            </View>
          )}
          {clip.isPinned && <Text style={styles.pinnedBadge}>📌</Text>}
          {clip.isSensitive && (
            <View style={{ flexDirection: 'row', gap: 6 }}>
              <TouchableOpacity onPress={() => setRevealed(v => !v)}>
                <Text style={styles.sensitiveBadge}>
                  {revealed ? (lang === 'en' ? 'Hide' : 'چھپائیں') : (lang === 'en' ? 'Unlock once' : 'ایک بار کھولیں')}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity onPress={() => onMarkNormal?.(clip.id)}>
                <Text style={[styles.sensitiveBadge, { backgroundColor: `${T.green}22`, color: T.green }]}>
                  {lang === 'en' ? 'Mark Normal' : 'غیر حساس کریں'}
                </Text>
              </TouchableOpacity>
            </View>
          )}
          <Text style={styles.timeText}>{relativeTime(clip.timestamp, lang)}</Text>
        </View>

        {/* Content */}
        <Text style={[styles.clipText, isMasked && styles.maskedText]} numberOfLines={expanded ? undefined : 3}>
          {isMasked
            ? SensitiveDetector.mask(clip.text, clip.sensitiveType)
            : preview}
        </Text>

        {clip.extractedEntities && clip.extractedEntities.length > 0 && !isMasked && (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 4, paddingHorizontal: 12, paddingBottom: 6 }}>
            {clip.extractedEntities.map(ent => (
              <View key={ent} style={{ backgroundColor: `${T.accent}22`, borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2 }}>
                <Text style={{ fontSize: 10, color: T.accent }}>{ent}</Text>
              </View>
            ))}
          </View>
        )}

        {/* Stats */}
        <View style={styles.statsRow}>
          <Text style={styles.statText}>{clip.wordCount} {t('words', lang)}</Text>
          <Text style={styles.statDot}>·</Text>
          <Text style={styles.statText}>{clip.charCount} {t('chars', lang)}</Text>
          {clip.isSensitive && (
            <>
              <Text style={styles.statDot}>·</Text>
              <Text style={[styles.statText, { color: T.red }]}>{clip.sensitiveType ?? 'sensitive'}</Text>
            </>
          )}
        </View>
      </Pressable>

      {/* Smart Actions */}
      {!isMasked && <SmartActions clip={clip} lang={lang} T={T} />}

      {/* Action Bar */}
      <View style={styles.cardActions}>
        <ActionBtn
          icon="⎘"
          label={t('copy', lang)}
          color={T.accent}
          onPress={() => onCopy(clip.text)}
          T={T} lang={lang}
        />
        <ActionBtn
          icon={clip.isFavorite ? '★' : '☆'}
          label={clip.isFavorite ? t('unfavorite', lang) : t('favorite', lang)}
          color={clip.isFavorite ? T.amber : T.muted}
          onPress={() => onFavorite(clip.id, !clip.isFavorite)}
          T={T} lang={lang}
        />
        <ActionBtn
          icon={clip.isPinned ? '📌' : '📍'}
          label={clip.isPinned ? t('unpin', lang) : t('pin', lang)}
          color={clip.isPinned ? T.amber : T.muted}
          onPress={() => onPin(clip.id, !clip.isPinned)}
          T={T} lang={lang}
        />
        {showMoreActions && (
          <>
            <ActionBtn
              icon="✏️"
              label={lang === 'en' ? 'Edit' : 'ترمیم'}
              color={T.accent}
              onPress={() => onEdit?.(clip)}
              T={T} lang={lang}
            />
            <ActionBtn
              icon="⤴"
              label={t('share', lang)}
              color={T.green}
              onPress={() => void Share.share({ message: clip.isSensitive ? '(sensitive content)' : clip.text })}
              T={T} lang={lang}
            />
            <ActionBtn
              icon="✕"
              label={t('delete', lang)}
              color={T.red}
              onPress={() => onDelete(clip.id)}
              T={T} lang={lang}
            />
          </>
        )}
        <ActionBtn
          icon={showMoreActions ? '«' : '⋯'}
          label={showMoreActions ? (lang === 'en' ? 'Less' : 'کم') : (lang === 'en' ? 'More' : 'مزید')}
          color={T.muted}
          onPress={() => setShowMoreActions(v => !v)}
          T={T} lang={lang}
        />
      </View>
    </Animated.View>
    </View>
  );
}

function ActionBtn({ icon, label, onPress, color, T, lang }: { icon: string; label: string; onPress: () => void; color: string; T: ThemeColors, lang: AppLanguage }) {
  const styles = React.useMemo(() => getCardStyles(T, lang), [T, lang]);
  return (
    <TouchableOpacity
      accessibilityLabel={label}
      style={[styles.actionBtn, { borderColor: `${color}44` }]}
      onPress={() => {
        void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onPress();
      }}
      activeOpacity={0.7}
    >
      <Text style={[styles.actionIcon, { color }]}>{icon}</Text>
      <Text style={[styles.actionLabel, { color }]} numberOfLines={1}>{label}</Text>
    </TouchableOpacity>
  );
}

const getCardStyles = (T: ThemeColors, lang: AppLanguage) => {
  const nasta = lang === 'ur' ? NASTALEEQ_FONT : undefined;
  const ff = nasta ? { fontFamily: nasta } : {};
  return StyleSheet.create({
  card: {
    backgroundColor: (T as any).card || T.surface,
    borderWidth: 1,
    borderColor: T.border,
    borderRadius: 14,
    overflow: 'hidden',
  },
  swipeHintContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    backgroundColor: T.bg,
    borderRadius: 14,
  },
  swipeHintText: { fontSize: 12, fontWeight: '700', opacity: 0.6, ...ff },
  cardHeader: { flexDirection: 'row', alignItems: 'center', padding: 12, paddingBottom: 6, gap: 6 },
  catBadge: { borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 },
  catText: { fontSize: 11, fontWeight: '800', ...ff },
  pinnedBadge: { fontSize: 14 },
  sensitiveBadge: {
    fontSize: 11,
    color: T.red,
    fontWeight: '700',
    backgroundColor: `${T.red}22`,
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: 6,
    ...ff
  },
  timeText: { fontSize: 11, color: T.muted, marginLeft: 'auto', ...ff },
  clipText: { fontSize: 14, color: T.text, lineHeight: 21, paddingHorizontal: 12, paddingBottom: 6, ...ff },
  maskedText: { color: T.red, fontSize: 15, letterSpacing: 4 },
  statsRow: { flexDirection: 'row', gap: 4, paddingHorizontal: 12, paddingBottom: 8, alignItems: 'center' },
  statText: { fontSize: 11, color: T.muted, ...ff },
  statDot: { fontSize: 11, color: `${T.muted}66` },
  cardActions: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: T.border,
    padding: 9,
    gap: 6,
    backgroundColor: T.surface,
  },
  actionBtn: {
    flex: 1,
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 1,
  },
  actionIcon: { fontSize: 16, fontWeight: '900', ...ff },
  actionLabel: { fontSize: 9.5, fontWeight: '900', marginTop: 2, ...ff },
});
};
// ActionBtn usages inside ClipCard need the `lang` prop
// since I cannot use regex replace over multiple blocks easily, I will just add lang={lang} to ActionBtn calls in ClipCard.
// Wait, I should replace the usages as well.
