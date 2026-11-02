import { ThemeColors } from '../theme/colors';

export const CATEGORIES = ['All', 'Template', 'Text', 'URL', 'Email', 'Phone', 'Code', 'Number', 'Date', 'Short', 'Long Text'];

export const getCategoryColors = (T: ThemeColors): Record<string, string> => ({
  URL: '#14b8a6',
  Email: '#a855f7',
  Phone: T.green,
  Code: T.amber,
  Number: '#38bdf8',
  Date: '#f97316',
  Text: T.accent,
  Short: T.muted,
  'Long Text': '#ec4899',
  All: T.muted,
});
