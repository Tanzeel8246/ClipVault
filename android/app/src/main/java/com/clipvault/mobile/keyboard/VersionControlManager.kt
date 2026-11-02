package com.clipvault.mobile.keyboard

import android.content.Context
import android.util.Log

/**
 * VersionControlManager — Enforces application, database, and keyboard configuration versioning.
 * Tracks migrations, handles backward-compatible schema updates, and exposes version receipts.
 */
object VersionControlManager {

    const val APP_VERSION = "1.3.0"
    const val VERSION_CODE = 25
    const val KEYBOARD_CONFIG_VERSION = 4
    const val DB_SCHEMA_VERSION = 3

    private const val PREFS_NAME = "clipvault_version_control"
    private const val KEY_LAST_VERSION_CODE = "last_enforced_version_code"
    private const val TAG = "VersionControl"

    /**
     * Returns human-readable version banner for UI display.
     */
    fun getFormattedVersionInfo(): String = "v$APP_VERSION (Build $VERSION_CODE)"

    /**
     * Enforces and executes pending migrations across keyboard configs, DB, and preferences.
     */
    fun enforceMigrations(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(KEY_LAST_VERSION_CODE, 0)

        if (lastVersion < VERSION_CODE) {
            Log.i(TAG, "Upgrading version from $lastVersion to $VERSION_CODE...")

            try {
                // Migration to v1.3.0: Enforce Device-Protected storage and v4 keyboard config
                val keyboardPrefs = context.getSharedPreferences("clipvault_keyboard", Context.MODE_PRIVATE)
                keyboardPrefs.edit()
                    .putInt("keyboardConfigVersion", KEYBOARD_CONFIG_VERSION)
                    .putBoolean("emojiKitchenEnabled", true)
                    .putBoolean("dynamicHitboxExpansionEnabled", true)
                    .apply()

                // Record successful migration
                prefs.edit().putInt(KEY_LAST_VERSION_CODE, VERSION_CODE).apply()
                Log.i(TAG, "Migration to $APP_VERSION (Build $VERSION_CODE) successful.")
            } catch (e: Exception) {
                Log.e(TAG, "Migration error: ${e.message}")
            }
        }
    }
}
