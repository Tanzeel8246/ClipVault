package com.clipvault.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object NativeClipStore {
  private const val DB_NAME = "clipvault.db"
  private const val KEYBOARD_MIRROR_FILE = "keyboard_clips.json"
  private const val KEYBOARD_PINS_KEY = "keyboard_board_pins"
  private const val KEYBOARD_PREFS = "clipvault_keyboard"
  private const val PIN_SEPARATOR = "\u001F" // unit separator (safe delimiter)

  fun recent(context: Context, limit: Int = 8): List<String> {
    val mirrored = readKeyboardMirror(context, limit)
    if (mirrored.isNotEmpty()) return mirrored

    val db = openDb(context) ?: return emptyList()
    return try {
      ensureSchema(db)
      pruneExpired(db)
      val safeLimit = limit.coerceIn(1, 200).toString()
      val clips = mutableListOf<String>()
      db.rawQuery(
        "SELECT text FROM clips WHERE isDeleted = 0 AND isSensitive = 0 ORDER BY timestamp DESC LIMIT ?",
        arrayOf(safeLimit),
      ).use { cursor ->
        while (cursor.moveToNext()) {
          val value = cursor.getString(0).orEmpty()
          if (!value.startsWith("ENC:")) clips.add(value)
        }
      }
      clips
    } catch (_: RuntimeException) { emptyList() }
    finally { db.close() }
  }

  // ─── Direct Boot Protection (Device-Protected Storage on Android 7.0+) ─────
  fun getSafeContext(context: Context): Context {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
      if (context.isDeviceProtectedStorage) {
        context
      } else {
        context.createDeviceProtectedStorageContext() ?: context
      }
    } else {
      context
    }
  }

  private fun getSafePrefs(context: Context): android.content.SharedPreferences {
    return getSafeContext(context).getSharedPreferences(KEYBOARD_PREFS, Context.MODE_PRIVATE)
  }

  // ─── Keyboard-level pin helpers (stored in SharedPreferences) ───────────────
  fun getKeyboardPins(context: Context): List<String> {
    val raw = getSafePrefs(context).getString(KEYBOARD_PINS_KEY, "") ?: ""
    return if (raw.isBlank()) emptyList()
    else raw.split(PIN_SEPARATOR).filter { it.isNotBlank() }
  }

  fun toggleKeyboardPin(context: Context, clip: String): Boolean {
    val prefs = getSafePrefs(context)
    val current = getKeyboardPins(context).toMutableList()
    val isPinned: Boolean
    if (current.contains(clip)) {
      current.remove(clip)
      isPinned = false
    } else {
      current.add(0, clip)
      isPinned = true
    }
    prefs.edit().putString(KEYBOARD_PINS_KEY, current.joinToString(PIN_SEPARATOR)).apply()
    return isPinned
  }

  fun searchKeyboardPins(context: Context, query: String): List<String> {
    if (query.isBlank()) return getKeyboardPins(context)
    return getKeyboardPins(context).filter { it.contains(query, ignoreCase = true) }
  }

  // ─── Learned Words for Suggestions (Stored in SharedPreferences) ─────────────
  private const val LEARNED_WORDS_KEY = "keyboard_learned_words"

  fun getLearnedWords(context: Context): List<String> {
    val raw = getSafePrefs(context).getString(LEARNED_WORDS_KEY, "") ?: ""
    return if (raw.isBlank()) emptyList() else raw.split(PIN_SEPARATOR).filter { it.isNotBlank() }
  }

  fun addLearnedWord(context: Context, word: String) {
    if (word.length < 2 || word.length > 30) return
    val prefs = getSafePrefs(context)
    val current = getLearnedWords(context).toMutableList()
    current.remove(word)
    current.add(0, word)
    // Keep top 300 words
    val trimmed = current.take(300)
    prefs.edit().putString(LEARNED_WORDS_KEY, trimmed.joinToString(PIN_SEPARATOR)).apply()
  }

  fun search(context: Context, query: String, limit: Int = 15): List<String> {
    if (query.isBlank()) return recent(context, limit)
    val db = openDb(context) ?: return emptyList()
    return try {
      ensureSchema(db)
      pruneExpired(db)
      val clips = mutableListOf<String>()
      db.rawQuery(
        "SELECT text FROM clips WHERE isDeleted = 0 AND isSensitive = 0 AND text LIKE ? ORDER BY timestamp DESC LIMIT ?",
        arrayOf("%$query%", limit.toString()),
      ).use { cursor ->
        while (cursor.moveToNext()) {
          val value = cursor.getString(0).orEmpty()
          if (!value.startsWith("ENC:")) clips.add(value)
        }
      }
      clips
    } catch (_: RuntimeException) { emptyList() }
    finally { db.close() }
  }

  // ─── Favorites for keyboard panel ────────────────────────────────────────────
  fun favorites(context: Context, limit: Int = 10): List<String> {
    val db = openDb(context) ?: return emptyList()
    return try {
      ensureSchema(db)
      pruneExpired(db)
      val clips = mutableListOf<String>()
      db.rawQuery(
        "SELECT text FROM clips WHERE isDeleted = 0 AND isSensitive = 0 AND isFavorite = 1 ORDER BY timestamp DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 200).toString()),
      ).use { cursor ->
        while (cursor.moveToNext()) {
          val value = cursor.getString(0).orEmpty()
          if (!value.startsWith("ENC:")) clips.add(value)
        }
      }
      clips
    } catch (_: RuntimeException) { emptyList() }
    finally { db.close() }
  }

  // ─── Category + Search combo for keyboard tabs ───────────────────────────────
  // category: "" = all, "URL", "Phone", "Email", "Code", "Short", "Text", etc.
  // favoritesOnly: true = only favorites tab
  fun searchByCategory(
    context: Context,
    query: String,
    category: String,
    favoritesOnly: Boolean,
    limit: Int = 20,
  ): List<String> {
    val db = openDb(context) ?: return emptyList()
    return try {
      ensureSchema(db)
      pruneExpired(db)
      val clauses = mutableListOf("isDeleted = 0", "isSensitive = 0")
      val args = mutableListOf<String>()
      if (favoritesOnly) clauses.add("isFavorite = 1")
      if (category.isNotBlank()) { clauses.add("category = ?"); args.add(category) }
      if (query.isNotBlank()) { clauses.add("text LIKE ?"); args.add("%$query%") }
      args.add(limit.coerceIn(1, 200).toString())
      val clips = mutableListOf<String>()
      db.rawQuery(
        "SELECT text FROM clips WHERE ${clauses.joinToString(" AND ")} ORDER BY isPinned DESC, timestamp DESC LIMIT ?",
        args.toTypedArray(),
      ).use { cursor ->
        while (cursor.moveToNext()) {
          val value = cursor.getString(0).orEmpty()
          if (!value.startsWith("ENC:")) clips.add(value)
        }
      }
      clips
    } catch (_: RuntimeException) { emptyList() }
    finally { db.close() }
  }

  // ─── Toggle favorite by text (keyboard-level, no id needed) ─────────────────
  fun toggleFavoriteByText(context: Context, clipText: String): Boolean {
    val db = openDb(context) ?: return false
    return try {
      ensureSchema(db)
      val hash = md5(clipText.trim())
      var nowFavorite = false
      db.rawQuery("SELECT id, isFavorite FROM clips WHERE hash = ? LIMIT 1", arrayOf(hash)).use { cursor ->
        if (cursor.moveToFirst()) {
          val id = cursor.getString(0)
          val currentlyFav = cursor.getInt(1) == 1
          nowFavorite = !currentlyFav
          db.execSQL("UPDATE clips SET isFavorite = ? WHERE id = ?", arrayOf<Any>(if (nowFavorite) 1 else 0, id))
        }
      }
      nowFavorite
    } catch (_: RuntimeException) { false }
    finally { db.close() }
  }

  fun save(context: Context, value: String): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    return saveDetailed(
      context = context,
      id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
      value = text,
      category = detectCategory(text),
      timestamp = isoNow(),
      isFavorite = false,
      isPinned = false,
    )
  }

  fun saveDetailed(
    context: Context,
    id: String,
    value: String,
    category: String,
    timestamp: String,
    isFavorite: Boolean,
    isPinned: Boolean,
  ): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    val db = openDb(context) ?: return false
    return try {
      ensureSchema(db)
      val hash = md5(text)
      val now = timestamp.ifBlank { isoNow() }
      val sensitiveType = detectSensitiveType(text)
      val sensitive = sensitiveType != null
      var inserted = false
      db.rawQuery("SELECT id, isFavorite, isPinned FROM clips WHERE hash = ? LIMIT 1", arrayOf(hash)).use { cursor ->
        if (cursor.moveToFirst()) {
          val existingId = cursor.getString(0)
          val favoriteValue = if (isFavorite) 1 else cursor.getInt(1)
          val pinnedValue = if (isPinned) 1 else cursor.getInt(2)
          db.execSQL(
            "UPDATE clips SET text=?,category=?,timestamp=?,isFavorite=?,isPinned=?,wordCount=?,charCount=?,isDeleted=0,isSensitive=?,sensitiveType=? WHERE id=?",
            arrayOf<Any>(
              text,
              category.ifBlank { detectCategory(text) },
              now,
              favoriteValue,
              pinnedValue,
              countWords(text),
              text.length,
              if (sensitive) 1 else 0,
              sensitiveType ?: "",
              existingId,
            ),
          )
          inserted = true
          return inserted
        }
      }
      db.execSQL(
        "INSERT INTO clips (id,text,hash,category,timestamp,isFavorite,isPinned,wordCount,charCount,isSensitive,sensitiveType) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        arrayOf<Any>(
          id.ifBlank { "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}" },
          text,
          hash,
          category.ifBlank { detectCategory(text) },
          now,
          if (isFavorite) 1 else 0,
          if (isPinned) 1 else 0,
          countWords(text),
          text.length,
          if (sensitive) 1 else 0,
          sensitiveType ?: "",
        ),
      )
      true
    } catch (_: RuntimeException) { false }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun hasText(context: Context, value: String): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    val db = openDb(context) ?: return false
    return try {
      ensureSchema(db)
      db.rawQuery("SELECT id FROM clips WHERE hash = ? LIMIT 1", arrayOf(md5(text))).use { cursor ->
        cursor.moveToFirst()
      }
    } catch (_: RuntimeException) { false }
    finally { db.close() }
  }

  fun clipsJson(
    context: Context,
    search: String,
    category: String,
    favoritesOnly: Boolean,
    sensitiveOnly: Boolean,
    limit: Int,
    offset: Int,
  ): String {
    val db = openDb(context) ?: return "[]"
    return try {
      ensureSchema(db)
      pruneExpired(db)
      val clauses = mutableListOf("isDeleted = 0")
      val args = mutableListOf<String>()
      if (category.isNotBlank() && category != "All") {
        clauses.add("category = ?")
        args.add(category)
      }
      if (favoritesOnly) clauses.add("isFavorite = 1")
      if (sensitiveOnly) clauses.add("isSensitive = 1")
      if (search.isNotBlank()) {
        clauses.add("text LIKE ?")
        args.add("%$search%")
      }
      args.add(limit.coerceIn(1, 10000).toString())
      args.add(offset.coerceAtLeast(0).toString())

      val arr = JSONArray()
      db.rawQuery(
        """
        SELECT id,text,category,timestamp,isFavorite,isPinned,wordCount,charCount,tags,isDeleted,isSensitive,sensitiveType
        FROM clips
        WHERE ${clauses.joinToString(" AND ")}
        ORDER BY isPinned DESC, timestamp DESC
        LIMIT ? OFFSET ?
        """.trimIndent(),
        args.toTypedArray(),
      ).use { cursor ->
        while (cursor.moveToNext()) {
          arr.put(rowToJson(cursor))
        }
      }
      arr.toString()
    } catch (_: RuntimeException) { "[]" }
    finally { db.close() }
  }

  fun statsJson(context: Context): String {
    val db = openDb(context) ?: return "{}"
    return try {
      ensureSchema(db)
      val today = isoNow().substring(0, 10)
      val result = JSONObject()
      result.put("total", scalarInt(db, "SELECT COUNT(*) FROM clips WHERE isDeleted = 0"))
      result.put("today", scalarInt(db, "SELECT COUNT(*) FROM clips WHERE isDeleted = 0 AND timestamp LIKE ?", arrayOf("$today%")))
      result.put("favorites", scalarInt(db, "SELECT COUNT(*) FROM clips WHERE isDeleted = 0 AND isFavorite = 1"))
      result.put("sensitive", scalarInt(db, "SELECT COUNT(*) FROM clips WHERE isDeleted = 0 AND isSensitive = 1"))
      val categories = JSONObject()
      db.rawQuery("SELECT category, COUNT(*) FROM clips WHERE isDeleted = 0 GROUP BY category", emptyArray<String>()).use { cursor ->
        while (cursor.moveToNext()) {
          categories.put(cursor.getString(0).orEmpty(), cursor.getInt(1))
        }
      }
      result.put("categories", categories)
      result.toString()
    } catch (_: RuntimeException) { "{}" }
    finally { db.close() }
  }

  fun setFavorite(context: Context, id: String, value: Boolean) {
    updateFlag(context, id, "isFavorite", value)
  }

  fun setPinned(context: Context, id: String, value: Boolean) {
    updateFlag(context, id, "isPinned", value)
  }

  fun delete(context: Context, id: String) {
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      db.execSQL("UPDATE clips SET isDeleted = 1 WHERE id = ?", arrayOf<Any>(id))
    } catch (e: Exception) { Log.w("NativeClipStore", "DB operation error", e) }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun bulkDelete(context: Context, ids: List<String>) {
    if (ids.isEmpty()) return
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      val placeholders = ids.joinToString(",") { "?" }
      db.execSQL("UPDATE clips SET isDeleted = 1 WHERE id IN ($placeholders)", ids.toTypedArray())
    } catch (e: Exception) { Log.w("NativeClipStore", "DB operation error", e) }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun bulkPin(context: Context, ids: List<String>, value: Boolean) {
    if (ids.isEmpty()) return
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      val placeholders = ids.joinToString(",") { "?" }
      val flag = if (value) 1 else 0
      val args = (listOf<Any>(flag) + ids).toTypedArray()
      db.execSQL("UPDATE clips SET isPinned = ? WHERE id IN ($placeholders)", args)
    } catch (e: Exception) { Log.w("NativeClipStore", "DB operation error", e) }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun bulkFavorite(context: Context, ids: List<String>, value: Boolean) {
    if (ids.isEmpty()) return
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      val placeholders = ids.joinToString(",") { "?" }
      val flag = if (value) 1 else 0
      val args = (listOf<Any>(flag) + ids).toTypedArray()
      db.execSQL("UPDATE clips SET isFavorite = ? WHERE id IN ($placeholders)", args)
    } catch (e: Exception) { Log.w("NativeClipStore", "DB operation error", e) }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun clearAll(context: Context) {
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      db.execSQL("UPDATE clips SET isDeleted = 1 WHERE isDeleted = 0")
    } catch (e: Exception) { Log.w("NativeClipStore", "DB operation error", e) }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun clearSensitive(context: Context): Int {
    val db = openDb(context) ?: return 0
    return try {
      ensureSchema(db)
      db.execSQL("UPDATE clips SET isDeleted = 1 WHERE isDeleted = 0 AND isSensitive = 1")
      scalarInt(db, "SELECT changes()")
    } catch (_: RuntimeException) { 0 }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun applyMaintenance(context: Context, autoDelete: String, maxClips: String) {
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      val days = when (autoDelete) {
        "7d" -> 7
        "30d" -> 30
        "90d" -> 90
        else -> 0
      }
      if (days > 0) {
        val cutoff = isoAt(System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L)
        db.execSQL(
          "UPDATE clips SET isDeleted = 1 WHERE isDeleted = 0 AND isPinned = 0 AND isFavorite = 0 AND isSensitive = 0 AND timestamp < ?",
          arrayOf<Any>(cutoff),
        )
      }

      if (maxClips != "unlimited") {
        val max = maxClips.toIntOrNull() ?: 0
        if (max > 0) {
          db.execSQL(
            """
            UPDATE clips
            SET isDeleted = 1
            WHERE isDeleted = 0
              AND isPinned = 0
              AND id NOT IN (
                SELECT id FROM (
                  SELECT id FROM clips
                  WHERE isDeleted = 0
                  ORDER BY isPinned DESC, timestamp DESC
                  LIMIT ?
                )
              )
            """.trimIndent(),
            arrayOf<Any>(max),
          )
        }
      }
    } catch (_: RuntimeException) { }
    finally { db.close() }
  }

  private fun openDb(context: Context): SQLiteDatabase? {
    val safeCtx = getSafeContext(context)
    val dbFile = databaseFile(context)
    return try {
      if (dbFile.exists()) {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null)
      } else {
        val safeDb = databaseFile(safeCtx)
        SQLiteDatabase.openOrCreateDatabase(safeDb, null)
      }
    } catch (_: Exception) {
      try {
        val safeDb = databaseFile(safeCtx)
        SQLiteDatabase.openOrCreateDatabase(safeDb, null)
      } catch (_: Exception) {
        null
      }
    }
  }

  private fun databaseFile(context: Context): File {
    val baseDir = try {
      context.filesDir
    } catch (_: Exception) {
      getSafeContext(context).filesDir
    }
    val directory = File(baseDir, "SQLite")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, DB_NAME)
  }

  private fun mirrorFile(context: Context): File {
    val baseDir = try {
      getSafeContext(context).filesDir
    } catch (_: Exception) {
      context.filesDir
    }
    return File(baseDir, KEYBOARD_MIRROR_FILE)
  }

  private fun readKeyboardMirror(context: Context, limit: Int): List<String> {
    return try {
      val file = mirrorFile(context)
      if (!file.exists()) {
        emptyList()
      } else {
        val arr = JSONArray(file.readText(Charsets.UTF_8))
        val clips = mutableListOf<String>()
        for (i in 0 until arr.length()) {
          val value = arr.optString(i).trim()
          if (value.isNotBlank()) clips.add(value)
          if (clips.size >= limit) break
        }
        clips
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun rebuildKeyboardMirror(context: Context) {
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      val clips = mutableListOf<String>()
      db.rawQuery(
        "SELECT text FROM clips WHERE isDeleted = 0 AND isSensitive = 0 ORDER BY timestamp DESC LIMIT 40",
        null
      ).use { cursor ->
        while (cursor.moveToNext()) {
          clips.add(cursor.getString(0).orEmpty())
        }
      }
      val arr = JSONArray()
      clips.forEach { arr.put(it) }
      mirrorFile(context).writeText(arr.toString(), Charsets.UTF_8)
    } catch (_: Exception) { }
    finally { db.close() }
  }

  fun setSensitive(context: Context, id: String, value: Boolean) {
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      db.execSQL(
        "UPDATE clips SET isSensitive = ?, sensitiveType = ? WHERE id = ?",
        arrayOf<Any>(if (value) 1 else 0, "", id)
      )
    } catch (_: RuntimeException) { }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  fun updateText(context: Context, id: String, value: String, category: String, sensitive: Boolean): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    val db = openDb(context) ?: return false
    return try {
      ensureSchema(db)
      val hash = md5(text)
      val sensitiveType = detectSensitiveType(text)
      val finalSensitive = sensitive || sensitiveType != null
      val finalCategory = category.ifBlank { detectCategory(text) }
      db.execSQL(
        "UPDATE clips SET text=?, hash=?, category=?, wordCount=?, charCount=?, isSensitive=?, sensitiveType=? WHERE id=?",
        arrayOf<Any>(
          text,
          hash,
          finalCategory,
          countWords(text),
          text.length,
          if (finalSensitive) 1 else 0,
          sensitiveType ?: "",
          id,
        )
      )
      true
    } catch (_: RuntimeException) { false }
    finally {
      db.close()
      rebuildKeyboardMirror(context)
    }
  }

  private fun ensureSchema(db: SQLiteDatabase) {
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS clips (
        id TEXT PRIMARY KEY, text TEXT NOT NULL, hash TEXT UNIQUE,
        category TEXT DEFAULT 'Text', timestamp TEXT NOT NULL,
        isFavorite INTEGER DEFAULT 0, isPinned INTEGER DEFAULT 0,
        wordCount INTEGER DEFAULT 0, charCount INTEGER DEFAULT 0,
        tags TEXT DEFAULT '[]', isDeleted INTEGER DEFAULT 0,
        isSensitive INTEGER DEFAULT 0, sensitiveType TEXT DEFAULT NULL,
        expires_at TEXT DEFAULT NULL
      )""".trimIndent())
    val migrations = listOf(
      "ALTER TABLE clips ADD COLUMN tags TEXT DEFAULT '[]'",
      "ALTER TABLE clips ADD COLUMN isDeleted INTEGER DEFAULT 0",
      "ALTER TABLE clips ADD COLUMN isSensitive INTEGER DEFAULT 0",
      "ALTER TABLE clips ADD COLUMN sensitiveType TEXT DEFAULT NULL",
      "ALTER TABLE clips ADD COLUMN hash TEXT",
      "ALTER TABLE clips ADD COLUMN wordCount INTEGER DEFAULT 0",
      "ALTER TABLE clips ADD COLUMN charCount INTEGER DEFAULT 0",
      "ALTER TABLE clips ADD COLUMN isFavorite INTEGER DEFAULT 0",
      "ALTER TABLE clips ADD COLUMN isPinned INTEGER DEFAULT 0",
      "ALTER TABLE clips ADD COLUMN expires_at TEXT DEFAULT NULL",
    )
    migrations.forEach { sql ->
      try { db.execSQL(sql) } catch (_: RuntimeException) { }
    }
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON clips(timestamp DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_category ON clips(category)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_favorite ON clips(isFavorite)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_hash ON clips(hash)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_sensitive ON clips(isSensitive)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted ON clips(isDeleted)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_pinned ON clips(isPinned)")
  }

  private fun pruneExpired(db: SQLiteDatabase) {
    try {
      db.execSQL("UPDATE clips SET isDeleted = 1 WHERE isDeleted = 0 AND expires_at IS NOT NULL AND expires_at < ?", arrayOf<Any>(isoNow()))
    } catch (_: Exception) {}
  }

  private fun rowToJson(cursor: android.database.Cursor): JSONObject {
    val text = cursor.getString(1).orEmpty()
    return JSONObject().apply {
      put("id", cursor.getString(0).orEmpty())
      put("text", text)
      put("category", cursor.getString(2).orEmpty().ifBlank { detectCategory(text) })
      put("timestamp", cursor.getString(3).orEmpty())
      put("isFavorite", cursor.getInt(4) == 1)
      put("isPinned", cursor.getInt(5) == 1)
      put("wordCount", cursor.getInt(6))
      put("charCount", cursor.getInt(7))
      put("tags", cursor.getString(8) ?: "[]")
      put("isDeleted", cursor.getInt(9) == 1)
      put("isSensitive", cursor.getInt(10) == 1)
      val sensitiveType = cursor.getString(11)
      if (sensitiveType.isNullOrBlank()) put("sensitiveType", JSONObject.NULL) else put("sensitiveType", sensitiveType)
    }
  }

  private fun scalarInt(db: SQLiteDatabase, sql: String, args: Array<String> = emptyArray()): Int {
    db.rawQuery(sql, args).use { cursor ->
      return if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
  }

  private fun updateFlag(context: Context, id: String, column: String, value: Boolean) {
    val db = openDb(context) ?: return
    try {
      ensureSchema(db)
      db.execSQL("UPDATE clips SET $column = ? WHERE id = ?", arrayOf<Any>(if (value) 1 else 0, id))
    } catch (_: RuntimeException) { }
    finally { db.close() }
  }

  private fun detectCategory(value: String): String {
    val text = value.trim()
    return when {
      Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "URL"
      Regex("^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$").containsMatchIn(text) -> "Email"
      Regex("^(\\+?\\d[\\d\\s().-]{8,}\\d)$").containsMatchIn(text) -> "Phone"
      Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{1,30}$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Bank Account"
      Regex("^\\d{9,18}$").containsMatchIn(text) -> "Bank Account"
      Regex("^\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}").containsMatchIn(text) -> "Date"
      Regex("^\\d+(\\.\\d+)?$").containsMatchIn(text) -> "Number"
      Regex("(^|\\n)\\s*(import|export|function|const|let|var|class|def)\\b|=>|</?[A-Za-z][\\s\\S]*>").containsMatchIn(text) -> "Code"
      text.startsWith("{") && text.endsWith("}") || text.startsWith("<") && text.endsWith(">") -> "Code"
      text.lines().size > 5 || text.length > 500 -> "Long Text"
      text.split(Regex("\\s+")).filter { it.isNotBlank() }.size <= 3 -> "Short"
      else -> "Text"
    }
  }

  private fun countWords(value: String): Int =
    if (value.isBlank()) 0 else value.trim().split(Regex("\\s+")).size

  private fun isSensitive(value: String): Boolean {
    return detectSensitiveType(value) != null
  }

  private fun detectSensitiveType(value: String): String? {
    val text = value.trim()
    return when {
      Regex("^\\d{4,8}$|(?:otp|code|verify|pin|verification)[:\\s]*(\\d{4,8})", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "otp"
      Regex("(?:password|passwd|pwd|secret|api[_\\s-]?key|token|bearer|auth)[:\\s=]+\\S+", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "password"
      Regex("\\b\\d{5}[-\\s]?\\d{7}[-\\s]?\\d{1}\\b").containsMatchIn(text) -> "cnic"
      Regex("\\b(?:\\d[\\s-]?){13,19}\\b").containsMatchIn(text) -> "card"
      Regex("\\b[A-Za-z0-9+/=_-]{32,}\\b|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").containsMatchIn(text) -> "token"
      else -> null
    }
  }

  private fun md5(value: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun isoNow(): String {
    return isoAt(System.currentTimeMillis())
  }

  private fun isoAt(timeMillis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(timeMillis))
  }
}
