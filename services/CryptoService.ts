import CryptoJS from 'crypto-js';

/**
 * Provides AES-256 encryption for secure backups.
 */
export const CryptoService = {
  /**
   * Encrypts a plaintext string using AES-256.
   * @param text The plaintext to encrypt.
   * @param secret The sync password.
   * @returns The encrypted ciphertext.
   */
  encrypt: (text: string, secret: string): string => {
    if (!text || !secret) return text;
    try {
      return CryptoJS.AES.encrypt(text, secret).toString();
    } catch (e) {
      console.error('Encryption failed:', e);
      throw new Error('Failed to encrypt backup.');
    }
  },

  /**
   * Decrypts an AES-256 encrypted string.
   * @param ciphertext The encrypted string.
   * @param secret The sync password.
   * @returns The decrypted plaintext.
   */
  decrypt: (ciphertext: string, secret: string): string => {
    if (!ciphertext || !secret) return ciphertext;
    // Check if it's actually encrypted (heuristic)
    if (!ciphertext.startsWith('U2FsdGVkX1')) {
      // Not encrypted with crypto-js standard format, assume plain JSON
      return ciphertext;
    }
    
    try {
      const bytes = CryptoJS.AES.decrypt(ciphertext, secret);
      const originalText = bytes.toString(CryptoJS.enc.Utf8);
      if (!originalText) throw new Error('Incorrect password or corrupt data.');
      return originalText;
    } catch (e) {
      console.error('Decryption failed:', e);
      throw new Error('Incorrect Sync Password or corrupt backup file.');
    }
  }
};
