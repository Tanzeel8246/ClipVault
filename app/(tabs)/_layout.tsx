import { Tabs } from 'expo-router';
import React from 'react';
import { Text } from 'react-native';
import { useAppTheme } from '../../theme/useAppTheme';
import { useClipStore } from '../../store/useClipStore';
import { t } from '../../services/i18n';
import { AppLanguage } from '../../services/types';

export default function TabLayout() {
  const T = useAppTheme();
  const settings = useClipStore(state => state.settings);
  const lang = (settings.appLanguage as AppLanguage) ?? 'ur';

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarStyle: {
          backgroundColor: T.surface,
          borderTopColor: T.border,
          borderTopWidth: 1,
        },
        tabBarActiveTintColor: T.accent,
        tabBarInactiveTintColor: T.muted,
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: t('tabClips', lang),
          tabBarIcon: ({ color }) => <Text style={{ fontSize: 18, color }}>📋</Text>,
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: t('tabSettings', lang),
          tabBarIcon: ({ color }) => <Text style={{ fontSize: 18, color }}>⚙️</Text>,
        }}
      />
    </Tabs>
  );
}
