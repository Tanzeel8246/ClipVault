package com.clipvault.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class AutoClipboardMonitorService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var clipboardManager: ClipboardManager? = null
  private var stopAtMillis = 0L
  private var lastClipContent = ""
  private var savedCount = 0

  private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
    pollClipboard()
  }

  private val pollRunnable = object : Runnable {
    override fun run() {
      if (System.currentTimeMillis() >= stopAtMillis) {
        stopSelf()
        return
      }

      pollClipboard()
      handler.postDelayed(this, POLL_INTERVAL_MS)
    }
  }

  override fun onCreate() {
    super.onCreate()
    clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }

    val minutes = intent?.getIntExtra(EXTRA_DURATION_MINUTES, DEFAULT_DURATION_MINUTES) ?: DEFAULT_DURATION_MINUTES
    val safeMinutes = if (minutes <= 0) 0 else minutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
    stopAtMillis = if (safeMinutes == 0) Long.MAX_VALUE else System.currentTimeMillis() + safeMinutes * 60_000L

    val message = if (safeMinutes == 0) "Auto monitor active until stopped" else "Auto monitor active for $safeMinutes minutes"
    startForeground(NOTIFICATION_ID, buildNotification(message))

    clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
    clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    handler.removeCallbacks(pollRunnable)
    handler.post(pollRunnable)

    return START_STICKY
  }

  override fun onDestroy() {
    handler.removeCallbacks(pollRunnable)
    clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun pollClipboard() {
    val text = readClipboardText().trim()
    if (text.isBlank() || text == lastClipContent) return

    lastClipContent = text
    if (saveClip(text)) {
      savedCount += 1
      updateNotification("Auto monitor active - $savedCount saved")
    }
  }

  private fun readClipboardText(): String {
    return try {
      val clip = clipboardManager?.primaryClip ?: return ""
      if (clip.itemCount == 0) return ""

      clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
    } catch (_: SecurityException) {
      ""
    } catch (_: RuntimeException) {
      ""
    }
  }

  private fun saveClip(text: String): Boolean {
    return NativeClipStore.save(this, text)
  }

  private fun databaseFile(): File {
    val directory = File(filesDir, "SQLite")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, DB_NAME)
  }

  private fun ensureSchema(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS clips (
        id TEXT PRIMARY KEY,
        text TEXT NOT NULL,
        hash TEXT UNIQUE,
        category TEXT DEFAULT 'Text',
        timestamp TEXT NOT NULL,
        isFavorite INTEGER DEFAULT 0,
        isPinned INTEGER DEFAULT 0,
        wordCount INTEGER DEFAULT 0,
        charCount INTEGER DEFAULT 0,
        tags TEXT DEFAULT '[]',
        isDeleted INTEGER DEFAULT 0
      )
      """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON clips(timestamp DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_category ON clips(category)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_favorite ON clips(isFavorite)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_hash ON clips(hash)")
  }

  private fun detectCategory(value: String): String {
    val text = value.trim()

    return when {
      Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "URL"
      Regex("^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$").containsMatchIn(text) -> "Email"
      Regex("^(\\+?\\d[\\d\\s().-]{8,}\\d)$").containsMatchIn(text) -> "Phone"
      Regex("^\\d+(\\.\\d+)?$").containsMatchIn(text) -> "Number"
      Regex("^\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}").containsMatchIn(text) -> "Date"
      Regex("(^|\\n)\\s*(import|export|function|const|let|var|class|def)\\b|=>|</?[A-Za-z][\\s\\S]*>").containsMatchIn(text) -> "Code"
      text.lines().size > 5 || text.length > 500 -> "Long Text"
      text.split(Regex("\\s+")).filter { it.isNotBlank() }.size <= 3 -> "Short"
      else -> "Text"
    }
  }

  private fun countWords(value: String): Int {
    val text = value.trim()
    if (text.isBlank()) return 0
    return text.split(Regex("\\s+")).size
  }

  private fun md5(value: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun isoNow(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date())
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val channel = NotificationChannel(
      CHANNEL_ID,
      "ClipVault auto monitor",
      NotificationManager.IMPORTANCE_LOW,
    )
    channel.description = "Timed clipboard monitor session"

    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
  }

  private fun buildNotification(message: String): Notification {
    val stopIntent = Intent(this, AutoClipboardMonitorService::class.java).apply {
      action = ACTION_STOP
    }
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val stopPendingIntent = PendingIntent.getService(this, 1001, stopIntent, flags)

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }

    return builder
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("ClipVault")
      .setContentText(message)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(R.mipmap.ic_launcher, "Stop", stopPendingIntent)
      .build()
  }

  private fun updateNotification(message: String) {
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(NOTIFICATION_ID, buildNotification(message))
  }

  companion object {
    const val ACTION_START = "com.clipvault.mobile.AUTO_CLIPBOARD_START"
    const val ACTION_STOP = "com.clipvault.mobile.AUTO_CLIPBOARD_STOP"
    const val EXTRA_DURATION_MINUTES = "durationMinutes"

    private const val DB_NAME = "clipvault.db"
    private const val CHANNEL_ID = "clipvault_auto_monitor"
    private const val NOTIFICATION_ID = 4382
    private const val POLL_INTERVAL_MS = 1_000L
    private const val DEFAULT_DURATION_MINUTES = 30
    private const val MIN_DURATION_MINUTES = 5
    private const val MAX_DURATION_MINUTES = 240
  }
}
