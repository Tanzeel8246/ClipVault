import { useMemo } from 'react';
import { useColorScheme } from 'react-native';
import { useClipStore } from '../store/useClipStore';
import { themes, ThemeColors } from './colors';

export function useAppTheme(): ThemeColors {
  const { settings } = useClipStore();
  const systemScheme = useColorScheme();

  return useMemo(() => {
    const selected = (settings.theme || 'CLASSIC_DARK') as string;
    if (selected === 'SYSTEM' || selected === 'system') {
      return systemScheme === 'light' ? themes.SOFT_IVORY : themes.CLASSIC_DARK;
    }
    return themes[selected] || themes.CLASSIC_DARK;
  }, [settings.theme, systemScheme]);
}
