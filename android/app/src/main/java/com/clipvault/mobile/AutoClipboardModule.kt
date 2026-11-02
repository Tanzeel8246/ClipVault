package com.clipvault.mobile

import android.content.Intent
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray

class AutoClipboardModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "AutoClipboardMonitor"

  @ReactMethod
  fun start(durationMinutes: Int, promise: Promise) {
    try {
      val safeMinutes = if (durationMinutes <= 0) 0 else durationMinutes.coerceIn(5, 240)
      val intent = Intent(reactContext, AutoClipboardMonitorService::class.java).apply {
        action = AutoClipboardMonitorService.ACTION_START
        putExtra(AutoClipboardMonitorService.EXTRA_DURATION_MINUTES, safeMinutes)
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        reactContext.startForegroundService(intent)
      } else {
        reactContext.startService(intent)
      }

      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("AUTO_MONITOR_START_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun stop(promise: Promise) {
    try {
      val intent = Intent(reactContext, AutoClipboardMonitorService::class.java).apply {
        action = AutoClipboardMonitorService.ACTION_STOP
      }
      reactContext.startService(intent)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("AUTO_MONITOR_STOP_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun openKeyboardSettings(promise: Promise) {
    try {
      val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      reactContext.startActivity(intent)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("KEYBOARD_SETTINGS_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun showKeyboardPicker(promise: Promise) {
    try {
      val manager = reactContext.getSystemService(Context.INPUT_METHOD_SERVICE)
        as android.view.inputmethod.InputMethodManager
      manager.showInputMethodPicker()
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("KEYBOARD_PICKER_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun saveClipDetailed(
    id: String,
    text: String,
    category: String,
    timestamp: String,
    isFavorite: Boolean,
    isPinned: Boolean,
    promise: Promise,
  ) {
    try {
      promise.resolve(NativeClipStore.saveDetailed(reactContext, id, text, category, timestamp, isFavorite, isPinned))
    } catch (error: Throwable) {
      promise.reject("CLIP_SAVE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun hasClipText(text: String, promise: Promise) {
    try {
      promise.resolve(NativeClipStore.hasText(reactContext, text))
    } catch (error: Throwable) {
      promise.reject("CLIP_EXISTS_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun getClips(
    search: String,
    category: String,
    favoritesOnly: Boolean,
    sensitiveOnly: Boolean,
    limit: Int,
    offset: Int,
    promise: Promise,
  ) {
    try {
      promise.resolve(NativeClipStore.clipsJson(reactContext, search, category, favoritesOnly, sensitiveOnly, limit, offset))
    } catch (error: Throwable) {
      promise.reject("CLIPS_LOAD_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun getStats(promise: Promise) {
    try {
      promise.resolve(NativeClipStore.statsJson(reactContext))
    } catch (error: Throwable) {
      promise.reject("CLIP_STATS_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun toggleFavorite(id: String, value: Boolean, promise: Promise) {
    try {
      NativeClipStore.setFavorite(reactContext, id, value)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("CLIP_FAVORITE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun togglePin(id: String, value: Boolean, promise: Promise) {
    try {
      NativeClipStore.setPinned(reactContext, id, value)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("CLIP_PIN_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun toggleSensitive(id: String, value: Boolean, promise: Promise) {
    try {
      NativeClipStore.setSensitive(reactContext, id, value)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("CLIP_SENSITIVE_TOGGLE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun updateClipText(id: String, text: String, category: String, sensitive: Boolean, promise: Promise) {
    try {
      promise.resolve(NativeClipStore.updateText(reactContext, id, text, category, sensitive))
    } catch (error: Throwable) {
      promise.reject("CLIP_UPDATE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun deleteClip(id: String, promise: Promise) {
    try {
      NativeClipStore.delete(reactContext, id)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("CLIP_DELETE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun bulkDeleteClips(idsArray: ReadableArray, promise: Promise) {
    try {
      val ids = mutableListOf<String>()
      for (i in 0 until idsArray.size()) {
        idsArray.getString(i)?.let { ids.add(it) }
      }
      NativeClipStore.bulkDelete(reactContext, ids)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("BULK_DELETE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun bulkPinClips(idsArray: ReadableArray, value: Boolean, promise: Promise) {
    try {
      val ids = mutableListOf<String>()
      for (i in 0 until idsArray.size()) {
        idsArray.getString(i)?.let { ids.add(it) }
      }
      NativeClipStore.bulkPin(reactContext, ids, value)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("BULK_PIN_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun bulkFavoriteClips(idsArray: ReadableArray, value: Boolean, promise: Promise) {
    try {
      val ids = mutableListOf<String>()
      for (i in 0 until idsArray.size()) {
        idsArray.getString(i)?.let { ids.add(it) }
      }
      NativeClipStore.bulkFavorite(reactContext, ids, value)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("BULK_FAVORITE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun clearAllClips(promise: Promise) {
    try {
      NativeClipStore.clearAll(reactContext)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("CLIPS_CLEAR_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun clearSensitiveClips(promise: Promise) {
    try {
      promise.resolve(NativeClipStore.clearSensitive(reactContext))
    } catch (error: Throwable) {
      promise.reject("SENSITIVE_CLEAR_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun applyClipMaintenance(autoDelete: String, maxClips: String, promise: Promise) {
    try {
      NativeClipStore.applyMaintenance(reactContext, autoDelete, maxClips)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("CLIP_MAINTENANCE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun isKeyboardEnabled(promise: Promise) {
    try {
      val imm = reactContext.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
      val enabledList = imm.enabledInputMethodList
      var isEnabled = false
      val ourPackage = reactContext.packageName
      for (imi in enabledList) {
        if (imi.packageName == ourPackage) {
          isEnabled = true
          break
        }
      }
      promise.resolve(isEnabled)
    } catch (error: Throwable) {
      try {
        val enabledInputMethods = Settings.Secure.getString(
          reactContext.contentResolver,
          Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: ""
        val isEnabled = enabledInputMethods.contains(reactContext.packageName)
        promise.resolve(isEnabled)
      } catch (inner: Throwable) {
        promise.reject("KEYBOARD_CHECK_FAILED", inner.message, inner)
      }
    }
  }

  @ReactMethod
  fun isKeyboardSelected(promise: Promise) {
    try {
      val defaultInputMethod = Settings.Secure.getString(
        reactContext.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
      ) ?: ""
      val isSelected = defaultInputMethod.contains(reactContext.packageName)
      promise.resolve(isSelected)
    } catch (error: Throwable) {
      promise.reject("KEYBOARD_SELECTED_CHECK_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun setKeyboardPrefs(heightScale: Float, textScale: Float, promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("clipvault_keyboard_prefs", Context.MODE_PRIVATE)
      prefs.edit()
        .putFloat("keyboardHeightScale", heightScale)
        .putFloat("keyTextScale", textScale)
        .apply()
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("SET_KEYBOARD_PREFS_FAILED", error.message, error)
    }
  }
}
