import { ThemeSetting } from '../services/types';

export interface ThemeColors {
  bg: string;
  surface: string;
  card: string;
  border: string;
  accent: string;
  accentGlow: string;
  text: string;
  muted: string;
  red: string;
  redDim: string;
  green: string;
  greenDim: string;
  amber: string;
  amberDim: string;
}

import themesJson from '../android/app/src/main/assets/themes.json';

export const themes: Record<ThemeSetting, ThemeColors> = Object.keys(themesJson).reduce((acc, key) => {
  acc[key as ThemeSetting] = (themesJson as any)[key].rn;
  return acc;
}, {} as Record<ThemeSetting, ThemeColors>);
