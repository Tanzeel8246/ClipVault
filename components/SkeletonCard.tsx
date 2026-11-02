import React, { useEffect, useRef } from 'react';
import { Animated, StyleSheet, View } from 'react-native';
import { ThemeColors } from '../theme/colors';

export function SkeletonCard({ T }: { T: ThemeColors }) {
  const opacity = useRef(new Animated.Value(0.3)).current;

  useEffect(() => {
    const anim = Animated.loop(
      Animated.sequence([
        Animated.timing(opacity, { toValue: 0.7, duration: 800, useNativeDriver: true }),
        Animated.timing(opacity, { toValue: 0.3, duration: 800, useNativeDriver: true }),
      ])
    );
    anim.start();
    return () => anim.stop();
  }, [opacity]);

  return (
    <View style={[styles.card, { backgroundColor: T.surface, borderColor: T.border }]}>
      <View style={styles.header}>
        <Animated.View style={[styles.pill, { opacity, backgroundColor: `${T.accent}44` }]} />
        <Animated.View style={[styles.pillSmall, { opacity, backgroundColor: `${T.muted}33` }]} />
      </View>
      <Animated.View style={[styles.lineLong, { opacity, backgroundColor: `${T.text}22` }]} />
      <Animated.View style={[styles.lineMedium, { opacity, backgroundColor: `${T.text}22` }]} />
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: 14,
    borderWidth: 1,
    padding: 14,
    marginBottom: 10,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  pill: {
    width: 64,
    height: 18,
    borderRadius: 6,
  },
  pillSmall: {
    width: 48,
    height: 14,
    borderRadius: 4,
  },
  lineLong: {
    height: 14,
    borderRadius: 4,
    marginBottom: 8,
    width: '90%',
  },
  lineMedium: {
    height: 14,
    borderRadius: 4,
    width: '60%',
  },
});
