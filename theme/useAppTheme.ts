import { useMemo } from 'react';
import { useClipStore } from '../store/useClipStore';
import { themes, ThemeColors } from './colors';

export function useAppTheme(): ThemeColors {
  const { settings } = useClipStore();
  return themes[settings.theme || 'CLASSIC_DARK'] || themes.CLASSIC_DARK;
}
