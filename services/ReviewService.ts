import AsyncStorage from '@react-native-async-storage/async-storage';
import { Linking } from 'react-native';

const KEY_FIRST_LAUNCH = 'clipvault_first_launch_time';
const KEY_LAST_PROMPT = 'clipvault_last_review_prompt_time';
const KEY_HAS_REVIEWED = 'clipvault_has_reviewed_app';

// 3.5 days in milliseconds (between 3 to 4 days)
const PROMPT_INTERVAL_MS = 3.5 * 24 * 60 * 60 * 1000;

export const PLAY_STORE_WEB_URL = 'https://play.google.com/store/apps/details?id=com.clipvault.mobile';
export const PLAY_STORE_MARKET_URL = 'market://details?id=com.clipvault.mobile';

export class ReviewService {
  /**
   * Checks if user has been using the app for at least 3-4 days
   * and hasn't reviewed yet, or if 3-4 days have passed since last snooze.
   */
  static async checkShouldPromptReview(): Promise<boolean> {
    try {
      const hasReviewed = await AsyncStorage.getItem(KEY_HAS_REVIEWED);
      if (hasReviewed === 'true') return false;

      const now = Date.now();
      const firstLaunchStr = await AsyncStorage.getItem(KEY_FIRST_LAUNCH);

      if (!firstLaunchStr) {
        // Record initial install/launch time
        await AsyncStorage.setItem(KEY_FIRST_LAUNCH, String(now));
        return false;
      }

      const firstLaunch = Number(firstLaunchStr);
      const lastPromptStr = await AsyncStorage.getItem(KEY_LAST_PROMPT);

      if (lastPromptStr) {
        const lastPrompt = Number(lastPromptStr);
        return now - lastPrompt >= PROMPT_INTERVAL_MS;
      }

      // If never prompted yet, wait at least 3.5 days from first launch
      return now - firstLaunch >= PROMPT_INTERVAL_MS;
    } catch {
      return false;
    }
  }

  /**
   * Records that user clicked to review or stated they already reviewed.
   */
  static async recordReviewCompleted(): Promise<void> {
    try {
      await AsyncStorage.setItem(KEY_HAS_REVIEWED, 'true');
    } catch {}
  }

  /**
   * Snoozes prompt for another 3.5 days.
   */
  static async snoozeReviewPrompt(): Promise<void> {
    try {
      await AsyncStorage.setItem(KEY_LAST_PROMPT, String(Date.now()));
    } catch {}
  }

  /**
   * Opens Google Play Store directly.
   */
  static async openPlayStore(): Promise<void> {
    try {
      const canOpenMarket = await Linking.canOpenURL(PLAY_STORE_MARKET_URL);
      if (canOpenMarket) {
        await Linking.openURL(PLAY_STORE_MARKET_URL);
        return;
      }
    } catch {}
    await Linking.openURL(PLAY_STORE_WEB_URL);
  }
}
