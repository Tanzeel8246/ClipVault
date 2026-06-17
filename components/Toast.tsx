import React, { useEffect, useRef } from 'react';
import { Animated, Easing, StyleSheet, Text } from 'react-native';
import { ThemeColors } from '../theme/colors';

export function Toast({ message, visible, T }: { message: string; visible: boolean; T: ThemeColors }) {
  const toastStyles = React.useMemo(() => getToastStyles(T), [T]);
  const anim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (visible) {
      Animated.timing(anim, { toValue: 1, duration: 200, useNativeDriver: true, easing: Easing.out(Easing.quad) }).start();
    } else {
      Animated.timing(anim, { toValue: 0, duration: 180, useNativeDriver: true }).start();
    }
  }, [visible, anim]);

  return (
    <Animated.View
      pointerEvents="none"
      style={[
        toastStyles.wrap,
        {
          opacity: anim,
          transform: [{ translateY: anim.interpolate({ inputRange: [0, 1], outputRange: [-16, 0] }) }],
        },
      ]}
    >
      <Text style={toastStyles.text}>{message}</Text>
    </Animated.View>
  );
}

const getToastStyles = (T: ThemeColors) => StyleSheet.create({
  wrap: {
    position: 'absolute',
    top: 100,
    alignSelf: 'center',
    zIndex: 999,
    backgroundColor: '#22c55e',
    borderRadius: 20,
    paddingHorizontal: 18,
    paddingVertical: 8,
    maxWidth: '92%',
    shadowColor: '#22c55e',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.4,
    shadowRadius: 8,
    elevation: 8,
  },
  text: { color: '#fff', fontSize: 13, fontWeight: '700', textAlign: 'center' },
});
