import React from 'react';
import { View, Text, TouchableOpacity, Modal, StyleSheet, Image } from 'react-native';
import { ThemeColors } from '../theme/colors';

interface Props {
  visible: boolean;
  lang: 'en' | 'ur';
  T: ThemeColors;
  onRateNow: () => void;
  onLater: () => void;
  onAlreadyReviewed: () => void;
}

export function ReviewPromptModal({
  visible,
  lang,
  T,
  onRateNow,
  onLater,
  onAlreadyReviewed,
}: Props) {
  const isUr = lang === 'ur';

  return (
    <Modal visible={visible} animationType="fade" transparent onRequestClose={onLater}>
      <View style={styles.overlay}>
        <View style={[styles.dialog, { backgroundColor: T.surface, borderColor: T.border }]}>
          {/* Header Icon / Stars */}
          <View style={styles.iconContainer}>
            <Image
              source={require('../assets/splash-icon.png')}
              style={styles.appIcon}
              resizeMode="contain"
            />
            <View style={styles.starsRow}>
              {['⭐', '⭐', '⭐', '⭐', '⭐'].map((star, idx) => (
                <Text key={idx} style={styles.star}>
                  {star}
                </Text>
              ))}
            </View>
          </View>

          {/* Title */}
          <Text style={[styles.title, { color: T.text }]}>
            {isUr ? 'آپ کا تاثر ہمارے لیے قیمتی ہے!' : 'Enjoying ClipVault?'}
          </Text>

          {/* User's Exact Prompt Message */}
          <Text style={[styles.message, { color: T.muted }]}>
            {isUr
              ? 'اگر آپ کو کلپ والٹ پسند آئی ہے تو برائے مہربانی پلے اسٹور پر ایک چھوٹا سا ریویو دے کر ہماری حوصلہ افزائی کریں۔'
              : 'If you enjoy using ClipVault, please take a moment to leave a short review on the Play Store to support us.'}
          </Text>

          {/* Primary Action Button: Rate Now */}
          <TouchableOpacity
            style={[styles.primaryBtn, { backgroundColor: '#3b82f6' }]}
            activeOpacity={0.85}
            onPress={onRateNow}
          >
            <Text style={styles.primaryBtnText}>
              {isUr ? '⭐ پلے اسٹور پر ریویو دیں' : '⭐ Rate on Google Play'}
            </Text>
          </TouchableOpacity>

          {/* Secondary Action Button: Later */}
          <TouchableOpacity
            style={[styles.secondaryBtn, { borderColor: T.border }]}
            activeOpacity={0.8}
            onPress={onLater}
          >
            <Text style={[styles.secondaryBtnText, { color: T.text }]}>
              {isUr ? 'بعد میں' : 'Maybe Later'}
            </Text>
          </TouchableOpacity>

          {/* Already Reviewed Option */}
          <TouchableOpacity
            style={styles.tertiaryBtn}
            activeOpacity={0.7}
            onPress={onAlreadyReviewed}
          >
            <Text style={[styles.tertiaryBtnText, { color: T.muted }]}>
              {isUr ? 'پہلے ہی ریویو دے دیا ہے' : 'Already Reviewed'}
            </Text>
          </TouchableOpacity>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.72)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  dialog: {
    width: '100%',
    maxWidth: 340,
    borderRadius: 24,
    borderWidth: 1,
    paddingHorizontal: 22,
    paddingTop: 24,
    paddingBottom: 16,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.5,
    shadowRadius: 20,
    elevation: 15,
  },
  iconContainer: {
    alignItems: 'center',
    marginBottom: 12,
  },
  appIcon: {
    width: 60,
    height: 60,
    borderRadius: 16,
    marginBottom: 10,
  },
  starsRow: {
    flexDirection: 'row',
    gap: 4,
  },
  star: {
    fontSize: 20,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
    textAlign: 'center',
    marginBottom: 10,
  },
  message: {
    fontSize: 14,
    lineHeight: 22,
    textAlign: 'center',
    marginBottom: 20,
    paddingHorizontal: 4,
  },
  primaryBtn: {
    width: '100%',
    paddingVertical: 14,
    borderRadius: 14,
    alignItems: 'center',
    marginBottom: 10,
    shadowColor: '#3b82f6',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.35,
    shadowRadius: 8,
    elevation: 4,
  },
  primaryBtnText: {
    color: '#ffffff',
    fontSize: 15,
    fontWeight: '700',
  },
  secondaryBtn: {
    width: '100%',
    paddingVertical: 12,
    borderRadius: 14,
    borderWidth: 1,
    alignItems: 'center',
    marginBottom: 8,
  },
  secondaryBtnText: {
    fontSize: 14,
    fontWeight: '600',
  },
  tertiaryBtn: {
    paddingVertical: 8,
    paddingHorizontal: 12,
  },
  tertiaryBtnText: {
    fontSize: 12,
    textDecorationLine: 'underline',
  },
});
