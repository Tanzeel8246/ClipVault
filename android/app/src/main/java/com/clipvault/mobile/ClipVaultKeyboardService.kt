package com.clipvault.mobile

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import android.util.LruCache
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.LayerDrawable

import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

class ClipVaultKeyboardService : InputMethodService(), SpellCheckerSession.SpellCheckerSessionListener {
  private enum class LayoutMode(val code: String, val spaceLabel: String, val voiceLocale: String) {
    EN("EN", "English", "en-US"),
    UR("UR", "\u0627\u0631\u062F\u0648", "ur-PK"),
    AR("AR", "\u0639\u0631\u0628\u064A", "ar"),
    SYMBOLS("123", "Space", "en-US"),
  }

  private enum class KeyboardTheme(val displayName: String) {
    CLASSIC_DARK("Classic"),
    SOFT_IVORY("Ivory"),
    NEON_GLASS("Neon"),
    MIDNIGHT_GOLD("Gold"),
    GRAPHITE("Graphite"),
    OCEAN_BLUE("Ocean"),
    FOREST_GREEN("Forest"),
    ROYAL_PURPLE("Purple"),
    SUNSET_ORANGE("Orange"),
    ROSE_PETAL("Rose"),
  }

  private enum class PanelMode {
    KEYS,
    EMOJI,
    CLIPS,
    DIACRITICS,
    THEMES,
    LANGUAGES,
    ALTERNATES,
  }

  private enum class ShiftState {
    OFF,
    SHIFTED,
    CAPS_LOCK
  }

  private data class ThemeColors(
    val background: Int,
    val surface: Int,
    val key: Int,
    val specialKey: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val stroke: Int,
    val radius: Int,
    val elevation: Float,
  )

  private data class EmojiCategory(val icon: String, val emojis: List<String>)

  private var clipboardManager: ClipboardManager? = null
  private var speechRecognizer: SpeechRecognizer? = null
  private val repeatHandler = Handler(Looper.getMainLooper())
  private var repeatDeleteRunnable: Runnable? = null
  private var pendingLongPressRunnable: Runnable? = null
  private var touchConsumed = false
  private var longPressTriggered = false
  private var lastClipContent = ""
  private var savedCount = 0
  private var shiftState = ShiftState.OFF
  private var isListening = false
  private var voiceShouldKeepListening = false
  private var isSearchingClips = false
  private var voiceEndedWithSilence = false
  private var rtlTypeface: Typeface? = null
  private var layoutMode = LayoutMode.EN
  private var lastTextLayoutMode = LayoutMode.EN
  private var themeMode = KeyboardTheme.CLASSIC_DARK
  private var panelMode = PanelMode.KEYS
  private var enabledLanguages = mutableSetOf(LayoutMode.EN, LayoutMode.UR)
  private var emojiPageIndex = 0
  private var typedBuffer = ""
  private var voicePartialText = ""
  private var alternateKeys: List<String> = emptyList()
  private var alternatePopup: PopupWindow? = null
  private var slideAlternateButtons: List<Button> = emptyList()
  private var slideHighlightedIndex = -1
  private var symbolPageIndex = 0
  private var statusMessage = "clipboard capture active"
  private var statusView: TextView? = null
  private val suggestionButtons = mutableListOf<Button>()
  private var lastVoiceLevelStatusAt = 0L

  private var isDialpadMode = false
  private var selectionStart = -1
  private var selectionEnd = -1
  private var keyboardHeightScale = 1.0f
  private var keyTextScale = 1.0f
  private var hapticsEnabled = true
  //  Background worker + caches (fixes hang on every keystroke) ──────────────
  private var cachedThemesJson: JSONObject? = null
  private val drawableCache = LruCache<String, Drawable>(100)
  private val backgroundExecutor = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())
  private var cachedLearnedWords: List<String> = emptyList()
  private var lastLearnedWordsRefreshAt = 0L
  private var cachedRecentClips: List<String> = emptyList()
  private var themePanelScrollY = 0
  private var themeScrollViewRef: ScrollView? = null
  // Voice auto-timeout
  private var voiceSilenceCount = 0
  private var voicePulseRunnable: Runnable? = null
  private var voiceAutoStopRunnable: Runnable? = null
  private var voiceLineBreakRunnable: Runnable? = null
  // Smart suggestion: track last accepted suggestion for undo-on-back
  private var lastSuggestionOriginal: String? = null
  private var lastSuggestionApplied: String? = null
  private val rejectedCorrections = mutableSetOf<String>()
  // Emoji categories
  private var emojiCategoryIndex = 0
  // Mic button reference for live pulse animation (without full keyboard rebuild)
  private var micButtonRef: Button? = null

  // Spell Checker
  private var spellCheckerSession: SpellCheckerSession? = null
  private var nativeSuggestions: List<String> = emptyList()

  private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
    captureClipboard()
  }

  private val diacriticValues = mapOf(
    "zabar" to "\u064E",
    "zer" to "\u0650",
    "pesh" to "\u064F",
    "do_zabar" to "\u064B",
    "do_zer" to "\u064D",
    "do_pesh" to "\u064C",
    "sukun" to "\u0652",
    "shadda" to "\u0651",
    "maddah" to "\u0653",
    "khari_zabar" to "\u0670",
    "khari_zer" to "\u0656",
    "ulta_pesh" to "\u0657",
    "hamza_above" to "\u0654",
  )

  override fun onCreate() {
    super.onCreate()
    loadKeyboardPrefs()
    clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager?.addPrimaryClipChangedListener(clipboardListener)

    try {
      val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
      spellCheckerSession = tsm.newSpellCheckerSession(null, null, this, true)
    } catch (e: Exception) {}
  }

  override fun onDestroy() {
    clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
    stopRepeatingDelete()
    cancelPendingLongPress()
    dismissAlternatePopup()
    releaseSpeechRecognizer()
    stopVoicePulse()
    stopVoiceAutoStopTimer()
    spellCheckerSession?.close()
    backgroundExecutor.shutdownNow()
    super.onDestroy()
  }

  override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    selectionStart = -1
    selectionEnd = -1
    updateShiftState()

    if (!restarting) {
      if (panelMode != PanelMode.KEYS || layoutMode == LayoutMode.SYMBOLS) {
        panelMode = PanelMode.KEYS
        layoutMode = lastTextLayoutMode
        refreshKeyboard()
      }
    }

    try {
      val themeFile = java.io.File(filesDir, "theme.txt")
      if (themeFile.exists()) {
        val savedTheme = themeFile.readText().trim()
        val newTheme = KeyboardTheme.valueOf(savedTheme)
        if (newTheme != themeMode) {
          themeMode = newTheme
          refreshKeyboard()
        }
      }
    } catch(e: Exception) {}
    captureClipboard()
    refreshCachedDataAsync()  // non-blocking DB refresh
  }

  override fun onUpdateSelection(
    oldSelStart: Int, oldSelEnd: Int,
    newSelStart: Int, newSelEnd: Int,
    candidatesStart: Int, candidatesEnd: Int
  ) {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    selectionStart = newSelStart
    selectionEnd = newSelEnd
    updateShiftState()
  }

  private fun updateShiftState() {
    if (shiftState == ShiftState.CAPS_LOCK) return
    val caps = currentInputConnection?.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) ?: 0
    val shouldShift = caps != 0
    if (shouldShift && shiftState != ShiftState.SHIFTED) {
      shiftState = ShiftState.SHIFTED
      refreshKeyboard()
    } else if (!shouldShift && shiftState == ShiftState.SHIFTED && typedBuffer.isEmpty()) {
      shiftState = ShiftState.OFF
      refreshKeyboard()
    }
  }

  override fun onFinishInputView(finishingInput: Boolean) {
    stopRepeatingDelete()
    cancelPendingLongPress()
    dismissAlternatePopup()
    super.onFinishInputView(finishingInput)
  }

  override fun onCreateInputView(): View {
    val colors = themeColors()
    val textRowH = dp((48 * keyboardHeightScale).toInt().coerceIn(42, 56))
    
    val showToolbarAndBottom = !isDialpadMode || panelMode != PanelMode.KEYS || layoutMode != LayoutMode.SYMBOLS
    val toolbarH = if (showToolbarAndBottom) dp(36) else 0
    val bottomRowH = if (showToolbarAndBottom) (textRowH + dp(2)) else 0
    val actualPanelH = if (!showToolbarAndBottom) {
      ((textRowH + dp(2)) * 5) + dp(36) + dp(33)
    } else {
      (textRowH + dp(2)) * 4
    }

    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(5), dp(4), dp(5), dp(6))
      setBackgroundColor(colors.background)

      if (showToolbarAndBottom) {
        addSuggestionStrip(this, colors)  // row 1: suggestions at top
        addToolbarRow(this, colors)       // row 2: toolbar
      }

      // Fixed-height panel
      addView(LinearLayout(this@ClipVaultKeyboardService).apply {
        orientation = LinearLayout.VERTICAL
        when (panelMode) {
          PanelMode.KEYS -> {
            if (this@ClipVaultKeyboardService.layoutMode == LayoutMode.SYMBOLS) {
              val rowH = if (!showToolbarAndBottom) (actualPanelH / 5) else textRowH
              addSymbolsPanel(this, colors, rowH)
            } else {
              rowsForLayout().forEach { row -> addKeyRow(this, row, colors, textRowH, weight = 1f) }
            }
          }
          PanelMode.EMOJI -> addEmojiPanel(this, colors)
          PanelMode.CLIPS -> addClipboardPanel(this, colors)
          PanelMode.DIACRITICS -> addDiacriticsPanel(this, colors)
          PanelMode.THEMES -> addThemePanel(this, colors)
          PanelMode.LANGUAGES -> addLanguagePanel(this, colors)
          PanelMode.ALTERNATES -> addAlternatesPanel(this, colors)
        }
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actualPanelH, 1f))

      if (showToolbarAndBottom) {
        addBottomRow(this, colors, textRowH)
      }
    }
  }

  private fun rowsForLayout(): List<List<String>> {
    return when (layoutMode) {
      LayoutMode.EN -> listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "-"),   // 10 keys
        listOf("shift", "@", "z", "x", "c", "v", "b", "n", "m", "back"), // 10 keys
      )
      LayoutMode.UR -> listOf(
        listOf("\u0636", "\u0635", "\u063A", "\u0691", "\u0679", "\u062B", "\u062D", "\u0626", "\u0638", "\u0637"),
        listOf("\u0642", "\u0648", "\u0639", "\u0631", "\u062A", "\u06D2", "\u0621", "\u06CC", "\u06C1", "\u067E"),
        listOf("\u0627", "\u0633", "\u0688", "\u062F", "\u0641", "\u06AF", "\u06BE", "\u062C", "\u06A9", "\u0644"),
        listOf("\u0630", "\u0632", "\u0634", "\u062E", "\u0686", "\u0628", "\u06BA", "\u0646", "\u0645", "back"), // \u06BA=\u0646 swapped
      )
      LayoutMode.AR -> listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062C"),
        listOf("\u0634", "\u0633", "\u064A", "\u0628", "\u0644", "\u0627", "\u062A", "\u0646", "\u0645", "\u0643"),
        listOf("\u0626", "\u0621", "\u0624", "\u0631", "\u0644\u0627", "\u0649", "\u0629", "\u0632", "\u0638", "back"),
      )
      LayoutMode.SYMBOLS -> listOf()
    }
  }

  // Symbol characters organised into pages: 4 rows x 10 columns per page
  private fun symbolPages(): List<List<List<String>>> {
    val p1 = listOf(
      listOf("1","2","3","4","5","6","7","8","9","0"),
      listOf("@","#","$","%","&","-","+","(",")","/"),
      listOf("*","\"","'",":",";","!","?","\\","_","back"),
      listOf("sym_cycle", "~","`","|","\u2022","\u221A","\u03C0","\u00F7","\u00D7","\u2206")
    )
    val p2 = listOf(
      listOf("¹","²","³","⁴","⁵","⁶","⁷","⁸","⁹","⁰"),
      listOf("\u00A3","\u00A2","\u20AC","\u00A5","^","\u00B0","=","{","}","\\"),
      listOf("%","\u00A9","\u00AE","\u2122","\u2713","[","]","<",">","back"),
      listOf("sym_cycle", "\u00A7","\u221E","\u2260","\u2248","\u2264","\u2265","\u00B1","\u2039","\u203A")
    )
    return listOf(p1, p2)
  }

  // Toolbar row: always same 6 buttons regardless of layout mode
  // sym_cycle is now in bottom row for SYMBOLS mode
  private fun addToolbarRow(parent: LinearLayout, colors: ThemeColors) {
    val keys = listOf("theme", "mic", "clips", "ai_rewrite", "languages", "next_keyboard")

    // Build toolbar manually so we can save micButtonRef for live pulse animation
    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(0, dp(2), 0, dp(2))
      keys.forEach { key ->
        val btn = createKeyButton(labelFor(key), key, colors, isControlKey(key)).apply {
          bindKeyTouch(this, key)
          if (key == "mic") micButtonRef = this  // live pulse reference
        }
        addView(btn, LinearLayout.LayoutParams(0, dp(32), keyWeight(key)).apply {
          setMargins(dp(2), 0, dp(2), 0)
        })
      }
    })
  }

  // Minimal status row (14dp) — hidden entirely when mic is active (mic button IS the indicator)
  private fun addStatusRow(parent: LinearLayout, colors: ThemeColors) {
    // Deprecated by user request, mic button pulse handles indicator now
  }

  private fun addSuggestionStrip(parent: LinearLayout, colors: ThemeColors) {
    suggestionButtons.clear()

    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, dp(3))

      repeat(3) {
        addView(createKeyButton(" ", "", colors, true).apply {
          textSize = 16f
          setOnClickListener {
            val suggestion = tag as? String ?: ""
            if (suggestion == "CANCEL_SEARCH") {
              isSearchingClips = false
              typedBuffer = ""
              updateSuggestionStrip()
            } else if (suggestion.isNotBlank()) {
              if (isSearchingClips) isSearchingClips = false
              insertSuggestion(suggestion)
            }
          }
          suggestionButtons.add(this)
        }, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
          setMargins(dp(3), 0, dp(3), 0)
        })
      }
    })

    updateSuggestionStrip()
  }

  private fun addBottomRow(parent: LinearLayout, colors: ThemeColors, height: Int) {
    if (panelMode == PanelMode.EMOJI) {
      val keys = listOf("keys", "space", "back")
      addKeyRow(parent, keys, colors, height)
      return
    }
    val keys = when (layoutMode) {
      LayoutMode.EN -> listOf("symbols", "lang", ",", "space", ".", "!", "enter")
      LayoutMode.UR, LayoutMode.AR -> listOf("symbols", "lang", "urdu_comma", "space", "urdu_period", "urdu_qmark", "enter")
      LayoutMode.SYMBOLS -> {
        if (isDialpadMode) {
          listOf("abc", "dialpad_toggle", "0", "enter")
        } else {
          listOf("abc", "dialpad_toggle", "lang", ",", "space", ".", "enter")
        }
      }
    }
    addKeyRow(parent, keys, colors, height)
  }

  // Emoji panel: vertical scroll through ALL categories (no horizontal tab strip)
  private fun addEmojiPanel(parent: LinearLayout, colors: ThemeColors) {
    val cats = emojiCategories()

    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(2), 0, dp(2), dp(4))

      cats.forEach { cat ->
        // Category header row (icon only, compact)
        addView(LinearLayout(this@ClipVaultKeyboardService).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(6), dp(6), dp(6), dp(4))

          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = cat.icon
            textSize = 18f
            setTextColor(colors.accent)
          })
        }, LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Emoji grid for this category: chunked into 9 columns for spaced borderless layout
        cat.emojis.chunked(9).forEach { row -> addEmojiRow(this, row, colors, dp(38)) }
      }
    }

    parent.addView(ScrollView(this).apply {
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
      addView(content)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  private fun addEmojiRow(parent: LinearLayout, emojis: List<String>, colors: ThemeColors, height: Int) {
    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(0, dp(4), 0, dp(4)) // Spacing for layout

      emojis.forEach { emoji ->
        addView(createKeyButton(emoji, emoji, colors, false).apply {
          bindKeyTouch(this, emoji)
        }, LinearLayout.LayoutParams(0, height, 1f).apply {
          setMargins(dp(4), 0, dp(4), 0) // Spaced-out margin
        })
      }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  private fun addSymbolsPanel(parent: LinearLayout, colors: ThemeColors, height: Int) {
    if (isDialpadMode) {
      val dpRows = listOf(
        listOf("+", "1", "2", "3", "/"),
        listOf("-", "4", "5", "6", "="),
        listOf("(", "7", "8", "9", "?"),
        listOf(")", "*", "0", "#", "!"),
        listOf("abc", "dialpad_toggle", "space", "back", "enter")
      )
      dpRows.forEach { row -> addKeyRow(parent, row, colors, height, weight = 1f) }
    } else {
      val pages = symbolPages()
      val pageIdx = symbolPageIndex.coerceIn(0, pages.size - 1)
      pages[pageIdx].forEach { row -> addKeyRow(parent, row, colors, height, weight = 1f) }
    }
  }

  private fun addClipboardPanel(parent: LinearLayout, colors: ThemeColors) {
    val pinnedClips = NativeClipStore.getKeyboardPins(this)
    val recentClips = cachedRecentClips

    // Outer scrollable container — fills fixed-height panel
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(4), dp(4), dp(4), dp(4))

      addView(Button(this@ClipVaultKeyboardService).apply {
         text = "🔍 Search Clips"
         setTextColor(colors.accent)
         textSize = 14f
         isAllCaps = false
         background = statusBackground(colors)
         setOnClickListener {
           isSearchingClips = true
           panelMode = PanelMode.KEYS
           typedBuffer = ""
           updateStatus("Type to search clips...")
           refreshKeyboard()
         }
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
         setMargins(dp(4), dp(2), dp(4), dp(10))
      })

      if (pinnedClips.isEmpty() && recentClips.isEmpty()) {
        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = "No saved clips yet"
          setTextColor(colors.muted)
          textSize = 15f
          gravity = Gravity.CENTER
          setPadding(0, dp(20), 0, dp(20))
        })
        return@apply
      }

      // ── Pinned section ──────────────────────────────────────────────────────
      if (pinnedClips.isNotEmpty()) {
        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = "\uD83D\uDCCC Pinned"
          textSize = 11f
          typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
          setTextColor(colors.accent)
          setPadding(dp(4), dp(2), dp(4), dp(2))
        })
        pinnedClips.forEach { clip -> addClipRow(this, clip, colors, isPinned = true) }
      }

      // ── Recent section ──────────────────────────────────────────────────────
      if (recentClips.isNotEmpty()) {
        if (pinnedClips.isNotEmpty()) {
          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = "\uD83D\uDD51 Recent"
            textSize = 11f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            setTextColor(colors.accent)
            setPadding(dp(4), dp(6), dp(4), dp(2))
          })
        }
        recentClips.forEach { clip ->
          if (!pinnedClips.contains(clip)) addClipRow(this, clip, colors, isPinned = false)
        }
      }
    }

    parent.addView(ScrollView(this).apply {
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
      addView(content)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  private fun addClipRow(parent: LinearLayout, clip: String, colors: ThemeColors, isPinned: Boolean) {
    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(if (isPinned) (colors.specialKey) else colors.key)
        setStroke(dp(1), colors.stroke)
      }
      setPadding(dp(8), dp(6), dp(4), dp(6))

      // Clip text area — click to paste
      addView(TextView(this@ClipVaultKeyboardService).apply {
        val displayText = clip.trim()
        text = displayText
        textSize = 12.5f
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(colors.text)
        typeface = if (containsRtlText("", displayText)) {
          rtlTypeface ?: buildRtlTypeface().also { rtlTypeface = it }
        } else {
          android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        }
        setOnClickListener {
          currentInputConnection?.commitText(clip, 1)
          typedBuffer = ""
          panelMode = PanelMode.KEYS
          updateStatus("✓ clip inserted")
          refreshKeyboard()
        }
      }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

      // Pin / unpin button
      addView(TextView(this@ClipVaultKeyboardService).apply {
        text = if (isPinned) "\uD83D\uDCCC" else "\uD83D\uDCCB"
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(4), dp(4))
        setOnClickListener {
          val nowPinned = NativeClipStore.toggleKeyboardPin(this@ClipVaultKeyboardService, clip)
          updateStatus(if (nowPinned) "📌 pinned" else "unpinned")
          refreshCachedDataAsync()
          refreshKeyboard()
        }
      }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      setMargins(0, dp(2), 0, dp(2))
    })
  }

  private fun addDiacriticsPanel(parent: LinearLayout, colors: ThemeColors) {
    val rows = listOf(
      listOf("zabar", "zer", "pesh", "do_zabar", "do_zer", "do_pesh"),
      listOf("sukun", "shadda", "maddah", "khari_zabar", "khari_zer", "ulta_pesh", "hamza_above"),
    )

    rows.forEach { row -> addKeyRow(parent, row, colors, dp(40), weight = 1f) }
  }

  private fun addThemePanel(parent: LinearLayout, colors: ThemeColors) {
    val scroll = ScrollView(this).apply {
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      isFillViewport = true
      isVerticalScrollBarEnabled = false
    }
    themeScrollViewRef = scroll
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    val rows = listOf(
      listOf("theme_CLASSIC_DARK", "theme_SOFT_IVORY", "theme_GRAPHITE"),
      listOf("theme_NEON_GLASS", "theme_MIDNIGHT_GOLD", "theme_OCEAN_BLUE"),
      listOf("theme_FOREST_GREEN", "theme_ROYAL_PURPLE", "theme_SUNSET_ORANGE"),
      listOf("theme_ROSE_PETAL", "haptics_toggle", "keys"),
      listOf("height_minus", "height_plus", "keysize_minus", "keysize_plus"),
      listOf("ai_rewrite")
    )

    rows.forEach { row -> addKeyRow(content, row, colors, dp(44)) }
    scroll.addView(content)
    parent.addView(scroll)

    scroll.post {
      scroll.scrollTo(0, themePanelScrollY)
    }
  }

  private fun addLanguagePanel(parent: LinearLayout, colors: ThemeColors) {
    val scroll = ScrollView(this).apply {
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      isFillViewport = true
      isVerticalScrollBarEnabled = false
    }
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }

    val arabicAction = if (enabledLanguages.contains(LayoutMode.AR)) "lang_AR" else "download_AR"
    val rows = listOf(
      listOf("lang_EN", "remove_EN"),
      listOf("lang_UR", "remove_UR"),
      listOf(arabicAction, "remove_AR"),
      listOf("download_HI", "download_FR"),
      listOf("download_ES", "download_TR"),
      listOf("download_ZH", "download_WORLD"),
      listOf("add_more_languages", "switch_system"),
    )

    rows.forEach { row -> addKeyRow(content, row, colors, dp(42)) }
    scroll.addView(content)
    parent.addView(scroll)
  }

  private fun addAlternatesPanel(parent: LinearLayout, colors: ThemeColors) {
    if (alternateKeys.isEmpty()) {
      panelMode = PanelMode.KEYS
      return
    }

    alternateKeys.chunked(6).forEach { row -> addKeyRow(parent, row, colors, dp(44), weight = 1f) }
  }

  private fun addKeyRow(parent: LinearLayout, keys: List<String>, colors: ThemeColors, height: Int, weight: Float = 0f) {
    val lp = if (weight > 0f) {
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
    } else {
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(0, dp(1), 0, dp(1))

      keys.forEach { key ->
        val keyHeight = if (weight > 0f) ViewGroup.LayoutParams.MATCH_PARENT else height
        addView(createKeyButton(labelFor(key), key, colors, isControlKey(key)).apply {
          bindKeyTouch(this, key)
        }, LinearLayout.LayoutParams(0, keyHeight, keyWeight(key)).apply {
          setMargins(0, 0, 0, 0)
        })
      }
    }, lp)
  }

  private fun addScrollableKeyRow(parent: LinearLayout, keys: List<String>, colors: ThemeColors, height: Int) {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(1), 0, dp(1))

      keys.forEach { key ->
        addView(createKeyButton(labelFor(key), key, colors, isControlKey(key)).apply {
          bindKeyTouch(this, key)
        }, LinearLayout.LayoutParams(symbolKeyWidth(key), height).apply {
          setMargins(0, 0, 0, 0)
        })
      }
    }

    parent.addView(HorizontalScrollView(this).apply {
      isHorizontalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
      addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
      setMargins(0, dp(1), 0, dp(1))
    })
  }

  private fun createKeyButton(label: String, key: String, colors: ThemeColors, special: Boolean): Button {
    val previewColors = previewColorsForKey(key) ?: colors
    val usesPreview = key.startsWith("theme_")
    val micActive = key == "mic" && isListening

    // Urdu row 1: add visible number hints (long-press = number)
    val urduHints = mapOf(
      "\u0636" to "1", "\u0635" to "2", "\u063A" to "3", "\u0691" to "4", "\u0679" to "5",
      "\u062B" to "6", "\u062D" to "7", "\u0626" to "8", "\u0638" to "9", "\u0637" to "0"
    )
    val hint = if (layoutMode == LayoutMode.UR) urduHints[key] else null
    val emojiHint = if (key == "," || key == "urdu_comma") "\u263A" else null
    val displayText: CharSequence = when {
      hint != null -> {
        SpannableString("$label\n$hint").apply {
          val hStart = label.length + 1
          // Larger and more opaque for visibility
          setSpan(RelativeSizeSpan(0.48f), hStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          setSpan(ForegroundColorSpan(Color.argb(175, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent))), hStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
      }
      emojiHint != null -> {
        SpannableString("$label$emojiHint").apply {
          val hStart = label.length
          setSpan(RelativeSizeSpan(0.55f), hStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          setSpan(ForegroundColorSpan(Color.argb(175, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent))), hStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
      }
      else -> label
    }
    val allowsTwoLines = hint != null || usesPreview || key in setOf("switch_system", "add_more_languages") ||
      key.startsWith("download_") || key.startsWith("remove_")

    return Button(this).apply {
      text = displayText
      setSingleLine(!allowsTwoLines)
      maxLines = if (allowsTwoLines) 2 else 1
      ellipsize = android.text.TextUtils.TruncateAt.END
      setTextColor(when {
        usesPreview -> previewColors.text
        special || micActive -> colors.accent
        else -> colors.text
      })
      // Mic active: dark red base background; pulse animation will animate it
      background = if (panelMode == PanelMode.EMOJI && !isControlKey(key)) {
        StateListDrawable().apply {
          addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(colors.radius).toFloat()
            setColor(Color.argb(40, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent)))
          })
          addState(intArrayOf(), GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
          })
        }
      } else if (micActive) {
        GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = dp(colors.radius).toFloat()
          setColor(Color.argb(220, 180, 30, 30))  // dark red base
          setStroke(dp(2), Color.rgb(220, 60, 60))
        }
      } else {
        keyBackground(previewColors, if (usesPreview) false else special)
      }
      textSize = textSizeFor(key, label) * keyTextScale * 1.08f
      typeface = typefaceForKey(key, label)
      textDirection = if (containsRtlText(key, label)) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
      isAllCaps = false
      minHeight = 0
      minWidth = 0
      includeFontPadding = false
      elevation = if (panelMode == PanelMode.EMOJI && !isControlKey(key)) 0f else colors.elevation
      setPadding(0, 0, 0, 0)
    }
  }

  private fun bindKeyTouch(button: Button, key: String) {
    button.setOnTouchListener { view, event ->
      when (key) {
        "back" -> handleBackTouch(event)
        else -> handleResponsiveKeyTouch(view, key, event)
      }
    }
  }

  private fun handleResponsiveKeyTouch(view: View, key: String, event: MotionEvent): Boolean {
    when (event.action) {
      MotionEvent.ACTION_DOWN -> {
        view.isPressed = true
        if (hapticsEnabled) triggerVibration()
        touchConsumed = false
        slideHighlightedIndex = -1
        dismissAlternatePopup()
        cancelPendingLongPress()
        longPressTriggered = false

        val alternates = alternatesForKey(key)
        when {
          key == "lang" -> {
            pendingLongPressRunnable = Runnable {
              touchConsumed = true
              longPressTriggered = true
              panelMode = PanelMode.LANGUAGES
              refreshKeyboard()
            }
            repeatHandler.postDelayed(pendingLongPressRunnable!!, 280L)
          }
          key == "shift" -> {
            pendingLongPressRunnable = Runnable {
              touchConsumed = true
              longPressTriggered = true
              shiftState = ShiftState.CAPS_LOCK
              refreshKeyboard()
            }
            repeatHandler.postDelayed(pendingLongPressRunnable!!, 350L)
          }
          alternates.isNotEmpty() -> {
            touchConsumed = true
            pendingLongPressRunnable = Runnable {
              longPressTriggered = true
              if (hapticsEnabled) triggerVibration()
              showAlternatesPopup(view, alternates)
            }
            repeatHandler.postDelayed(pendingLongPressRunnable!!, 200L)
          }
          else -> {
            if (panelMode == PanelMode.KEYS || panelMode == PanelMode.DIACRITICS || panelMode == PanelMode.ALTERNATES) {
              touchConsumed = true
              handleKey(key)
            } else {
              touchConsumed = false
            }
          }
        }
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (alternatePopup?.isShowing == true && slideAlternateButtons.isNotEmpty()) {
          val rawX = event.rawX
          val rawY = event.rawY
          val loc = IntArray(2)
          var matched = false
          for (idx in slideAlternateButtons.indices) {
            val btn = slideAlternateButtons[idx]
            btn.getLocationOnScreen(loc)
            val left = loc[0].toFloat() - dp(4)
            val right = left + btn.width + dp(8)
            val top = loc[1].toFloat() - dp(8)
            val bottom = top + btn.height + dp(16)
            if (rawX in left..right && rawY in top..bottom) {
              matched = true
              if (slideHighlightedIndex != idx) {
                slideHighlightedIndex = idx
                val c = themeColors()
                slideAlternateButtons.forEachIndexed { i, b ->
                  b.background = keyBackground(c, i == idx)
                  b.alpha = if (i == idx) 1f else 0.55f
                }
              }
              break
            }
          }
          if (!matched) {
            slideHighlightedIndex = -1
            val c = themeColors()
            slideAlternateButtons.forEach { b -> b.background = keyBackground(c, false); b.alpha = 1f }
          }
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        view.isPressed = false
        cancelPendingLongPress()
        if (alternatePopup?.isShowing == true) {
          val idx = if (slideHighlightedIndex >= 0) slideHighlightedIndex else 0
          if (idx >= 0 && idx < slideAlternateButtons.size) {
            val alt = slideAlternateButtons[idx].tag as? String
            if (alt != null) { handleKey(alt); updateStatus("\u2713 alternate") }
          }
          dismissAlternatePopup()
          slideHighlightedIndex = -1
        } else if (!touchConsumed) {
          handleKey(key)
        } else if (alternatesForKey(key).isNotEmpty() && !longPressTriggered) {
          handleKey(key)
        }
        touchConsumed = false
        longPressTriggered = false
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        view.isPressed = false
        cancelPendingLongPress()
        touchConsumed = false
        longPressTriggered = false
        return true
      }
    }
    return true
  }

  private fun keyBackground(colors: ThemeColors, special: Boolean): StateListDrawable {
    val baseColor = if (special) colors.specialKey else colors.key
    val cacheKey = "key_${baseColor}_${colors.stroke}_${colors.radius}"
    
    val cached = drawableCache.get(cacheKey)
    if (cached != null) {
      return (cached.constantState?.newDrawable()?.mutate() as? StateListDrawable) ?: cached as StateListDrawable
    }

    // Pressed state (flat, no bevel, shifted down visually)
    val pressedBg = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(colors.radius).toFloat()
      setColor(baseColor)
      setStroke(dp(1), colors.stroke)
      alpha = 200 // Slightly darken when pressed
    }
    val pressedLayer = LayerDrawable(arrayOf(pressedBg)).apply {
      setLayerInset(0, dp(2), dp(2), dp(2), dp(2))
    }

    // Normal state (with 3D bottom bevel)
    val bevelColor = Color.argb(
      255,
      (Color.red(baseColor) * 0.8).toInt(),
      (Color.green(baseColor) * 0.8).toInt(),
      (Color.blue(baseColor) * 0.8).toInt()
    )
    val shadowBg = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(colors.radius).toFloat()
      setColor(bevelColor)
    }
    val topBg = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(colors.radius).toFloat()
      setColor(baseColor)
      setStroke(dp(1), colors.stroke)
    }
    val normalLayer = LayerDrawable(arrayOf(shadowBg, topBg)).apply {
      setLayerInset(0, dp(2), dp(2), dp(2), dp(2))
      setLayerInset(1, dp(2), dp(2), dp(2), dp(6))
    }

    val stateList = StateListDrawable().apply {
      addState(intArrayOf(android.R.attr.state_pressed), pressedLayer)
      addState(intArrayOf(), normalLayer)
    }
    
    drawableCache.put(cacheKey, stateList)
    return stateList
  }

  private fun statusBackground(colors: ThemeColors): GradientDrawable {
    val cacheKey = "status_${colors.surface}_${colors.accent}_${colors.stroke}_${isListening}"
    val cached = drawableCache.get(cacheKey)
    if (cached != null) {
      return (cached.constantState?.newDrawable()?.mutate() as? GradientDrawable) ?: cached as GradientDrawable
    }

    val drawable = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(8).toFloat()
      setColor(if (isListening) colors.accent else colors.surface)
      setStroke(dp(1), if (isListening) colors.accent else colors.stroke)
    }
    drawableCache.put(cacheKey, drawable)
    return drawable
  }

  private fun updateSuggestionStrip() {
    try {
      if (isSearchingClips && typedBuffer.isEmpty()) {
        suggestionButtons[0].text = "❌ Cancel Search"
        suggestionButtons[0].tag = "CANCEL_SEARCH"
        suggestionButtons[0].isEnabled = true
        suggestionButtons[0].alpha = 1f
        
        suggestionButtons[1].text = " "
        suggestionButtons[1].tag = ""
        suggestionButtons[1].isEnabled = false
        suggestionButtons[1].alpha = 0f
        
        suggestionButtons[2].text = " "
        suggestionButtons[2].tag = ""
        suggestionButtons[2].isEnabled = false
        suggestionButtons[2].alpha = 0f
        return
      }

      val suggestions = (suggestionsForBuffer() + listOf("", "", "")).take(3)
      
      if (typedBuffer.isEmpty() && suggestions.all { it.isBlank() }) {
        suggestionButtons.forEach { it.text = " "; it.isEnabled = false; it.alpha = 0f }
        return
      }

      suggestionButtons.forEachIndexed { index, button ->
        val suggestion = suggestions.getOrNull(index).orEmpty()
        button.text = if (suggestion.isBlank()) " " else compactLabel(suggestion, 18)
        button.tag = suggestion
        button.isEnabled = suggestion.isNotBlank()
        button.alpha = if (suggestion.isBlank()) 0.45f else 1f
      }
    } catch (e: Exception) {
      // Prevent unhandled exception from crashing InputMethodService
      suggestionButtons.forEach { it.text = " "; it.isEnabled = false }
    }
  }

  private fun labelFor(key: String): String {
    return when (key) {
      "shift" -> when (shiftState) {
        ShiftState.OFF -> "\u21E7"
        ShiftState.SHIFTED -> "\u2B06"
        ShiftState.CAPS_LOCK -> "\u21EA"
      }
      "back" -> "\u232B"
      "space" -> if (layoutMode == LayoutMode.SYMBOLS) lastTextLayoutMode.spaceLabel else layoutMode.spaceLabel
      "enter" -> {
        val action = currentInputEditorInfo?.imeOptions?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
        when (action) {
          android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> "\uD83D\uDD0D" // Magnifying glass
          android.view.inputmethod.EditorInfo.IME_ACTION_GO -> "Go"
          android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> "Send"
          android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> "\u2713"
          android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> "\u21E2"
          else -> "\u21B5"
        }
      }
      "lang" -> "\uD83C\uDF10"
      "theme" -> "\u25C9"
      "mic" -> "\uD83C\uDF99\uFE0F"  // 🎙️ always — red blink animation signals active state
      "next_keyboard" -> "\u2328"
      "urdu_qmark" -> "\u061F"  // Urdu question mark ؟
      "switch_system" -> "\u2328 System"
      "clips" -> "\uD83D\uDCCB"
      "emoji", "emoji_comma" -> "\u263A"
      "emoji_prev" -> "\u2039"
      "emoji_next" -> "\u203A"
      "emoji_page" -> "${emojiPageIndex + 1}/${emojiPages().size}"
      "keys" -> "ABC"
      "languages" -> "\u22EE"
      "marks" -> "\u0621\u064B"
      "," -> ","
      "urdu_comma" -> "\u060C"
      "urdu_period" -> "\u06D4"
      "symbols" -> "123"
      "sym_prev" -> "\u25C4 Prev"
      "sym_next" -> "Next \u25BA"
      "sym_cycle" -> "${symbolPageIndex + 1}/${symbolPages().size}\u21C4"  // e.g. "1/2 ⇄"
      "abc" -> when (lastTextLayoutMode) {
        LayoutMode.UR -> "\u0627\u0631\u062F\u0648"
        LayoutMode.AR -> "\u0639\u0631\u0628\u064A"
        else -> "ABC"
      }
      "dialpad_toggle" -> if (isDialpadMode) "Sym" else "123"
      "zabar" -> "\u25CC\u064E"
      "zer" -> "\u25CC\u0650"
      "pesh" -> "\u25CC\u064F"
      "do_zabar" -> "\u25CC\u064B"
      "do_zer" -> "\u25CC\u064D"
      "do_pesh" -> "\u25CC\u064C"
      "sukun" -> "\u25CC\u0652"
      "shadda" -> "\u25CC\u0651"
      "maddah" -> "\u25CC\u0653"
      "khari_zabar" -> "\u25CC\u0670"
      "khari_zer" -> "\u25CC\u0656"
      "ulta_pesh" -> "\u25CC\u0657"
      "hamza_above" -> "\u25CC\u0654"
      "theme_CLASSIC_DARK" -> "Classic\nAa"
      "theme_SOFT_IVORY" -> "Ivory\nAa"
      "theme_NEON_GLASS" -> "Neon\nAa"
      "theme_MIDNIGHT_GOLD" -> "Gold\nAa"
      "theme_GRAPHITE" -> "Graphite\nAa"
      "theme_OCEAN_BLUE" -> "Ocean\nAa"
      "theme_FOREST_GREEN" -> "Forest\nAa"
      "theme_ROYAL_PURPLE" -> "Purple\nAa"
      "theme_SUNSET_ORANGE" -> "Orange\nAa"
      "theme_ROSE_PETAL" -> "Rose\nAa"
      "height_minus" -> "H-"
      "height_plus" -> "H+"
      "keysize_minus" -> "Aa-"
      "keysize_plus" -> "Aa+"
      "haptics_toggle" -> if (hapticsEnabled) "Vib On" else "Vib Off"
      "ai_rewrite" -> "✨ AI Rewrite"
      else -> {
        val topRowUR = listOf("\u0636", "\u0635", "\u063A", "\u0691", "\u0679", "\u062B", "\u062D", "\u0626", "\u0638", "\u0637")
        val topRowAR = listOf("\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062C")
        val superscripts = listOf("¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "⁰")
        
        if (layoutMode == LayoutMode.UR) {
            val idx = topRowUR.indexOf(key)
            if (idx != -1) return key + superscripts[idx]
        } else if (layoutMode == LayoutMode.AR) {
            val idx = topRowAR.indexOf(key)
            if (idx != -1) return key + superscripts[idx]
        }
        if (layoutMode == LayoutMode.EN && key.length == 1 && key[0].isLetter()) {
          if (shiftState != ShiftState.OFF) key.uppercase() else key.lowercase()
        } else {
          key
        }
      }
    }
  }

  private fun isControlKey(key: String): Boolean {
    return key in setOf(
      "shift", "back", "enter", "lang", "theme", "mic", "next_keyboard", "switch_system",
      "symbols", "abc", "clips", "emoji", "emoji_comma", "emoji_prev", "emoji_next",
      "emoji_page", "keys", "languages", "marks", "space", ",", ".", "urdu_comma",
      "urdu_period", "urdu_qmark", "sym_prev", "sym_next", "sym_cycle", "!", "dialpad_toggle",
      "height_minus", "height_plus", "keysize_minus", "keysize_plus", "haptics_toggle", "ai_rewrite"
    ) || diacriticValues.containsKey(key) ||
      key.startsWith("theme_") ||
      key.startsWith("lang_") ||
      key.startsWith("emoji_cat_") ||
      key.startsWith("download_") ||
      key.startsWith("remove_") ||
      key == "add_more_languages"
  }

  private fun keyWeight(key: String): Float {
    if (layoutMode == LayoutMode.SYMBOLS) {
      if (isDialpadMode) {
        return when (key) {
          "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "*", "#" -> 1.5f
          "space" -> 3.2f
          "enter", "back" -> 1.5f
          "abc", "dialpad_toggle" -> 1.2f
          else -> 1f
        }
      }
      return when (key) {
        "space" -> 3.2f
        "enter" -> 1.5f
        "abc", "dialpad_toggle" -> 1.2f
        else -> 1f
      }
    }
    return when (key) {
      "space" -> 3.2f
      "enter" -> 1.5f
      "shift", "back", "symbols", "abc" -> 1.35f
      "emoji_comma", "lang", "marks" -> 1.2f
      ",", ".", "urdu_comma", "urdu_period" -> 1.0f
      "emoji_prev", "emoji_next", "emoji_page", "keys" -> 1.25f
      "ai_rewrite" -> 2f
      "theme_CLASSIC_DARK", "theme_SOFT_IVORY", "theme_NEON_GLASS", "theme_MIDNIGHT_GOLD", "theme_GRAPHITE",
      "theme_OCEAN_BLUE", "theme_FOREST_GREEN", "theme_ROYAL_PURPLE", "theme_SUNSET_ORANGE", "theme_ROSE_PETAL" -> 2f
      "height_minus", "height_plus", "keysize_minus", "keysize_plus" -> 1.4f
      "haptics_toggle" -> 2f
      "add_more_languages", "switch_system" -> 2f
      else -> if (key.startsWith("lang_") || key.startsWith("download_") || key.startsWith("remove_")) 2f else 1f
    }
  }

  private fun symbolKeyWidth(key: String): Int {
    return when {
      key.length > 1 -> dp(56)
      key in setOf("\u060C", "\u06D4", "\u061F", "\u2026") -> dp(48)
      else -> dp(43)
    }
  }

  private fun textSizeFor(key: String, label: String): Float {
    return when {
      panelMode == PanelMode.EMOJI && !isControlKey(key) -> 23f
      key == "space" -> 18f
      key in setOf(",", ".", "urdu_comma", "urdu_period") -> 22f
      key in setOf("theme", "mic", "next_keyboard", "clips", "emoji", "emoji_comma", "enter", "marks", "lang", "languages", "ai_rewrite") -> 22f
      key in setOf("symbols", "abc", "shift", "back", "sym_cycle", "dialpad_toggle") -> 18f
      key.startsWith("theme_") -> 13f
      key in setOf("height_minus", "height_plus", "keysize_minus", "keysize_plus", "haptics_toggle") -> 14f
      key.startsWith("lang_") || key.startsWith("download_") || key.startsWith("remove_") || key == "add_more_languages" || key == "switch_system" -> 14f
      diacriticValues.containsKey(key) -> 24f
      key in setOf("emoji_prev", "emoji_next") -> 23f
      key == "emoji_page" || key == "keys" -> 14f
      label.length > 4 -> 16f
      layoutMode == LayoutMode.EN -> 26f
      else -> 27f
    }
  }

  private fun typefaceForKey(key: String, label: String): Typeface {
    return if (containsRtlText(key, label)) {
      rtlTypeface ?: buildRtlTypeface().also { rtlTypeface = it }
    } else {
      Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
  }

  private fun buildRtlTypeface(): Typeface {
    runCatching {
      return Typeface.createFromAsset(assets, "fonts/jameel_noori_nastaleeq.ttf")
    }

    val candidates = listOf(
      "Noto Nastaliq Urdu",
      "Noto Nastaliq Urdu Regular",
      "Jameel Noori Nastaleeq",
      "Jameel Noori Nastaliq",
      "serif",
    )
    return candidates
      .map { Typeface.create(it, Typeface.NORMAL) }
      .firstOrNull()
      ?: Typeface.create(Typeface.SERIF, Typeface.NORMAL)
  }

  private fun containsRtlText(key: String, label: String): Boolean {
    return (key + label).any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0x08A0..0x08FF }
  }

  private fun handleKey(key: String) {
    dismissAlternatePopup()

    if (panelMode == PanelMode.ALTERNATES && alternateKeys.contains(key)) {
      val isSpecial = key in setOf("marks", "emoji", "keys", "symbols", "abc", "languages", "theme", "mic", "clips")
      if (!isSpecial) {
        if (diacriticValues.containsKey(key)) {
          currentInputConnection?.commitText(diacriticValues[key], 1)
        } else {
          commitLiteralKey(key)
        }
        alternateKeys = emptyList()
        panelMode = PanelMode.KEYS
        refreshKeyboard()
        return
      }
    }

    if (key.startsWith("theme_")) {
      applySelectedTheme(key.removePrefix("theme_"))
      return
    }

    when (key) {
      "height_minus" -> { adjustKeyboardHeight(-0.08f); return }
      "height_plus" -> { adjustKeyboardHeight(0.08f); return }
      "keysize_minus" -> { adjustKeyTextScale(-0.06f); return }
      "keysize_plus" -> { adjustKeyTextScale(0.06f); return }
      "haptics_toggle" -> { toggleHaptics(); return }
      "ai_rewrite" -> {
        panelMode = PanelMode.KEYS
        refreshKeyboard()
        aiRewrite()
        return
      }
    }

    if (key.startsWith("lang_")) {
      selectLanguage(key.removePrefix("lang_"))
      return
    }

    if (key.startsWith("download_")) {
      downloadLanguage(key.removePrefix("download_"))
      return
    }

    if (key.startsWith("remove_")) {
      removeLanguage(key.removePrefix("remove_"))
      return
    }

    if (diacriticValues.containsKey(key)) {
      currentInputConnection?.commitText(diacriticValues[key], 1)
      updateSuggestionStrip()
      return
    }

    when (key) {
      "shift" -> {
        shiftState = when (shiftState) {
          ShiftState.OFF -> ShiftState.SHIFTED
          ShiftState.SHIFTED -> ShiftState.OFF
          ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        refreshKeyboard()
      }
      "lang" -> cycleEnabledLanguage()
      "dialpad_toggle" -> {
        isDialpadMode = !isDialpadMode
        refreshKeyboard()
      }
      "languages" -> {
        panelMode = if (panelMode == PanelMode.LANGUAGES) PanelMode.KEYS else PanelMode.LANGUAGES
        alternateKeys = emptyList()
        refreshKeyboard()
      }
      "theme" -> {
        panelMode = if (panelMode == PanelMode.THEMES) PanelMode.KEYS else PanelMode.THEMES
        themePanelScrollY = 0
        alternateKeys = emptyList()
        refreshKeyboard()
      }
      "mic" -> toggleVoiceTyping()
      "clips" -> {
        panelMode = if (panelMode == PanelMode.CLIPS) PanelMode.KEYS else PanelMode.CLIPS
        alternateKeys = emptyList()
        refreshKeyboard()
      }
      "emoji", "emoji_comma" -> {
        panelMode = if (panelMode == PanelMode.EMOJI) PanelMode.KEYS else PanelMode.EMOJI
        alternateKeys = emptyList()
        refreshKeyboard()
      }
      "emoji_prev" -> {
        val total = emojiPages().size
        emojiPageIndex = if (emojiPageIndex <= 0) total - 1 else emojiPageIndex - 1
        refreshKeyboard()
      }
      "emoji_next" -> {
        val total = emojiPages().size
        emojiPageIndex = (emojiPageIndex + 1) % total
        refreshKeyboard()
      }
      "emoji_page" -> Unit
      "keys" -> {
        panelMode = PanelMode.KEYS
        refreshKeyboard()
      }
      "marks" -> {
        panelMode = if (panelMode == PanelMode.DIACRITICS) PanelMode.KEYS else PanelMode.DIACRITICS
        alternateKeys = emptyList()
        refreshKeyboard()
      }
      "next_keyboard", "switch_system" -> switchToNextKeyboard()
      "symbols" -> {
        if (layoutMode != LayoutMode.SYMBOLS) lastTextLayoutMode = layoutMode
        layoutMode = LayoutMode.SYMBOLS
        panelMode = PanelMode.KEYS
        symbolPageIndex = 0
        typedBuffer = ""
        saveKeyboardPrefs()
        refreshKeyboard()
      }
      "sym_prev" -> {
        if (symbolPageIndex > 0) { symbolPageIndex--; refreshKeyboard() }
      }
      "sym_next" -> {
        val maxPage = symbolPages().size - 1
        if (symbolPageIndex < maxPage) { symbolPageIndex++; refreshKeyboard() }
      }
      "sym_cycle" -> {
        symbolPageIndex = (symbolPageIndex + 1) % symbolPages().size
        refreshKeyboard()
      }
      "abc" -> {
        layoutMode = lastTextLayoutMode
        panelMode = PanelMode.KEYS
        typedBuffer = ""
        saveKeyboardPrefs()
        refreshKeyboard()
      }
      "add_more_languages" -> {
        updateStatus("world language packs need install bundles")
        panelMode = PanelMode.LANGUAGES
        refreshKeyboard()
      }
      "urdu_qmark" -> {
        currentInputConnection?.commitText("\u061F", 1)
        typedBuffer = ""
        updateSuggestionStrip()
      }
      else -> {
        if (isSearchingClips && isControlKey(key) && key != "back" && key != "space") {
          isSearchingClips = false // exit search on control keys
        }
        sendTextKey(key)
      }
    }
  }

  private fun aiRewrite() {
    val connection = currentInputConnection ?: return
    var textToRewrite = connection.getSelectedText(0)?.toString()
    var isSelected = true
    if (textToRewrite.isNullOrBlank()) {
      textToRewrite = connection.getTextBeforeCursor(200, 0)?.toString()
      isSelected = false
    }
    
    if (textToRewrite.isNullOrBlank()) {
      updateStatus("No text to rewrite")
      return
    }

    updateStatus("⏳ AI Rewriting...")

    Thread {
      val keys = listOf(
        "AIzaSyCOwePzEnkjw_WMi0lcNPKGtyZ0ZvF0gW4",
        "AIzaSyBM8j_ECs_FJU5RWE79V_mpy6AmAJHnamI",
        "AIzaSyAVYWaqcTFB7MtqfH_WoWrWvqK9oJw8uBQ"
      )

      val payload = JSONObject()
      val contents = JSONArray()
      val content = JSONObject()
      val parts = JSONArray()
      val part = JSONObject()
      part.put("text", "Rewrite this text to be professional and grammatically correct. Output ONLY the rewritten text, without quotes or conversational filler:\n\n$textToRewrite")
      parts.put(part)
      content.put("parts", parts)
      contents.put(content)
      payload.put("contents", contents)

      var result: String? = null
      for (key in keys) {
        try {
          val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$key")
          val conn = url.openConnection() as java.net.HttpURLConnection
          conn.requestMethod = "POST"
          conn.setRequestProperty("Content-Type", "application/json")
          conn.doOutput = true
          conn.outputStream.use { it.write(payload.toString().toByteArray()) }

          if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val rewritten = json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            result = rewritten.trim()
            break
          }
        } catch (e: Exception) {
          // Fallback
        }
      }

      Handler(Looper.getMainLooper()).post {
        if (result != null) {
          if (isSelected) {
            currentInputConnection?.commitText(result, 1)
          } else {
            currentInputConnection?.deleteSurroundingText(textToRewrite!!.length, 0)
            currentInputConnection?.commitText(result, 1)
          }
          updateStatus("✨ AI Rewritten")
        } else {
          updateStatus("❌ AI Failed")
        }
      }
    }.start()
  }

  private fun selectLanguage(code: String) {
    val requested = runCatching { LayoutMode.valueOf(code) }.getOrNull() ?: return
    if (requested == LayoutMode.AR) enabledLanguages.add(LayoutMode.AR)
    if (requested != LayoutMode.SYMBOLS) {
      layoutMode = requested
      lastTextLayoutMode = requested
    }
    panelMode = PanelMode.KEYS
    typedBuffer = ""
    saveKeyboardPrefs()
    refreshKeyboard()
  }

  private fun downloadLanguage(code: String) {
    val requested = runCatching { LayoutMode.valueOf(code) }.getOrNull()
    if (requested == null) {
      updateStatus("${languagePackName(code)} pack manager ready")
      panelMode = PanelMode.LANGUAGES
      refreshKeyboard()
      return
    }

    if (requested == LayoutMode.EN || requested == LayoutMode.UR || requested == LayoutMode.AR) {
      enabledLanguages.add(requested)
      layoutMode = requested
      lastTextLayoutMode = requested
      panelMode = PanelMode.KEYS
      typedBuffer = ""
      updateStatus("${requested.code} added")
      saveKeyboardPrefs()
      refreshKeyboard()
    }
  }

  private fun languagePackName(code: String): String {
    return when (code) {
      "HI" -> "Hindi"
      "FR" -> "French"
      "ES" -> "Spanish"
      "TR" -> "Turkish"
      "ZH" -> "Chinese"
      "WORLD" -> "World language"
      else -> code
    }
  }

  private fun removeLanguage(code: String) {
    val requested = runCatching { LayoutMode.valueOf(code) }.getOrNull() ?: return
    if (requested == LayoutMode.EN || requested == LayoutMode.UR) {
      updateStatus("${requested.code} is required")
      panelMode = PanelMode.LANGUAGES
      refreshKeyboard()
      return
    }

    enabledLanguages.remove(requested)
    if (layoutMode == requested) {
      layoutMode = LayoutMode.EN
      lastTextLayoutMode = LayoutMode.EN
    }
    panelMode = PanelMode.LANGUAGES
    typedBuffer = ""
    updateStatus("${requested.code} removed")
    saveKeyboardPrefs()
    refreshKeyboard()
  }

  private fun cycleEnabledLanguage() {
    val languages = listOf(LayoutMode.EN, LayoutMode.UR, LayoutMode.AR).filter { enabledLanguages.contains(it) }
    if (languages.isEmpty()) {
      layoutMode = LayoutMode.EN
      return
    }

    val current = if (layoutMode == LayoutMode.SYMBOLS) lastTextLayoutMode else layoutMode
    val nextIndex = (languages.indexOf(current).takeIf { it >= 0 } ?: 0) + 1
    val next = languages[nextIndex % languages.size]
    layoutMode = next
    lastTextLayoutMode = next
    panelMode = PanelMode.KEYS
    typedBuffer = ""
    saveKeyboardPrefs()
    refreshKeyboard()
  }

  private fun sendTextKey(key: String) {
    val connection = currentInputConnection ?: return

    when (key) {
      "back" -> deleteOneCharacter()
      "space" -> commitSpaceWithAutoCorrect()
      "enter" -> {
        val action = currentInputEditorInfo?.imeOptions?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
          connection.performEditorAction(action)
        } else {
          connection.commitText("\n", 1)
        }
        typedBuffer = ""
        updateSuggestionStrip()
      }
      "." -> {
        connection.commitText(".", 1)
        typedBuffer = ""
        updateSuggestionStrip()
      }
      "," -> {
        connection.commitText(",", 1)
        typedBuffer = ""
        updateSuggestionStrip()
      }
      "urdu_comma" -> {
        connection.commitText("\u060C", 1)
        typedBuffer = ""
        updateSuggestionStrip()
      }
      "urdu_period" -> {
        connection.commitText("\u06D4", 1)
        typedBuffer = ""
        updateSuggestionStrip()
      }
      else -> commitLiteralKey(key)
    }
  }

  private fun commitLiteralKey(key: String) {
    try {
      val value = if (layoutMode == LayoutMode.EN && key.length == 1 && key[0].isLetter()) {
        if (shiftState != ShiftState.OFF) key.uppercase() else key.lowercase()
      } else {
        key
      }

      currentInputConnection?.commitText(value, 1)
      trackTypedValue(value)
      
      if (shiftState == ShiftState.SHIFTED && value.length == 1 && value[0].isLetter()) {
        shiftState = ShiftState.OFF
        refreshKeyboard()
      }
      updateSuggestionStrip()
    } catch (e: Exception) {
      // Suppress unhandled exceptions that hang the keyboard service
    }
  }

  private fun commitSpaceWithAutoCorrect() {
    val connection = currentInputConnection ?: return

    val learnedWords = NativeClipStore.getLearnedWords(this)
    val typed = typedBuffer.trim()
    // Do not autocorrect if the word is already learned or in personal dict
    val shouldAutocorrect = typed.isNotBlank() && !learnedWords.contains(typed) && !getPersonalDictionary().contains(typed)

    val topSuggestion = if (shouldAutocorrect) {
       nativeSuggestions.firstOrNull() ?: suggestionsForBuffer().firstOrNull()
    } else {
       typed
    }

    if (typed.isNotBlank() && topSuggestion != null && topSuggestion.isNotBlank() && topSuggestion != typed) {
      val diff = typed.length
      connection.deleteSurroundingText(diff, 0)
      connection.commitText(topSuggestion, 1)
      lastSuggestionOriginal = typed
      lastSuggestionApplied = topSuggestion
    } else if (typed.isNotBlank()) {
      registerWord(typed)
      NativeClipStore.addLearnedWord(this, typed)
      lastSuggestionOriginal = null
      lastSuggestionApplied = null
    }
    
    connection.commitText(" ", 1)
    typedBuffer = ""
    nativeSuggestions = emptyList()
    updateSuggestionStrip()
  }

  private fun registerWord(word: String) {
    val trimmed = word.trim()
    if (trimmed.length < 2 || trimmed.any { it.isDigit() }) return
    try {
      val prefs = getSharedPreferences("clipvault_personal_dict", android.content.Context.MODE_PRIVATE)
      val count = prefs.getInt(trimmed, 0)
      prefs.edit().putInt(trimmed, count + 1).apply()
    } catch (e: Exception) {}
  }

  private fun getPersonalDictionary(): List<String> {
    return try {
      val prefs = getSharedPreferences("clipvault_personal_dict", android.content.Context.MODE_PRIVATE)
      prefs.all.mapNotNull { entry ->
        val count = entry.value as? Int ?: 0
        if (count >= 2) entry.key else null
      }
    } catch (e: Exception) { emptyList() }
  }

  private fun trackTypedValue(value: String) {
    val isWordCharacter = value.length == 1 && (
      value[0].isLetterOrDigit() ||
        value[0].code in 0x0600..0x06FF
      )

    typedBuffer = if (isWordCharacter) {
      (typedBuffer + value).takeLast(24)
    } else {
      ""
    }

    if (typedBuffer.length > 1) {
      try {
        spellCheckerSession?.getSentenceSuggestions(arrayOf(TextInfo(typedBuffer)), 3)
      } catch (e: Exception) {}
    } else {
      nativeSuggestions = emptyList()
    }
  }

  private fun handleBackTouch(event: MotionEvent): Boolean {
    when (event.action) {
      MotionEvent.ACTION_DOWN -> {
        deleteOneCharacter(refresh = false)
        beginRepeatingDelete()
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        stopRepeatingDelete()
        return true
      }
    }
    return true
  }

  private fun beginRepeatingDelete() {
    stopRepeatingDelete()

    val runnable = object : Runnable {
      override fun run() {
        deleteOneCharacter(refresh = false)
        repeatHandler.postDelayed(this, 55L)
      }
    }

    repeatDeleteRunnable = runnable
    repeatHandler.postDelayed(runnable, 300L)
  }

  private fun stopRepeatingDelete() {
    repeatDeleteRunnable?.let { repeatHandler.removeCallbacks(it) }
    repeatDeleteRunnable = null
  }

  private fun cancelPendingLongPress() {
    pendingLongPressRunnable?.let { repeatHandler.removeCallbacks(it) }
    pendingLongPressRunnable = null
  }

  private fun deleteOneCharacter(refresh: Boolean = true) {
    try {
      val ic = currentInputConnection ?: return

      // ─ Undo last accepted suggestion on first back-press
      val orig = lastSuggestionOriginal
      val applied = lastSuggestionApplied
      if (orig != null && applied != null) {
        val toDelete = "$applied "
        ic.deleteSurroundingText(toDelete.length, 0)
        ic.commitText(orig, 1)
        typedBuffer = orig
        // Learn rejection — never suggest this pair again, AND learn the original word
        rejectedCorrections.add("$orig\u00B6$applied")
        NativeClipStore.addLearnedWord(this, orig)
        if (rejectedCorrections.size > 400) rejectedCorrections.remove(rejectedCorrections.first())
        saveRejectedCorrections()
        updateStatus("\u21A9 restored: $orig")
        lastSuggestionOriginal = null
        lastSuggestionApplied = null
        if (refresh) updateSuggestionStrip()
        return
      }

      lastSuggestionOriginal = null
      lastSuggestionApplied = null
      
      // Handle Select-All + Delete
      val selected = ic.getSelectedText(0)
      if (!selected.isNullOrEmpty()) {
        ic.commitText("", 1)
      } else {
        ic.deleteSurroundingText(1, 0)
      }
      if (typedBuffer.isNotEmpty()) typedBuffer = typedBuffer.dropLast(1)
      if (refresh) updateSuggestionStrip()
    } catch (e: Exception) {
      // Ensure crashes don't bring down the keyboard
    }
  }

  private fun showAlternates(keys: List<String>) {
    alternateKeys = keys.distinct()
    panelMode = PanelMode.ALTERNATES
    refreshKeyboard()
  }

  private fun showAlternatesPopup(anchor: View, keys: List<String>) {
    val alternates = keys.distinct()
    if (alternates.isEmpty()) return

    dismissAlternatePopup()

    if (alternates.size == 1) {
      handleKey(alternates.first())
      updateStatus("alternate inserted")
      return
    }

    val colors = themeColors()
    val buttons = mutableListOf<Button>()

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(4), dp(4), dp(4), dp(4))
      background = keyBackground(colors, true)
    }

    val rows = if (alternates.size > 6) {
      val mid = (alternates.size + 1) / 2
      listOf(alternates.take(mid), alternates.drop(mid))
    } else {
      listOf(alternates)
    }

    rows.forEach { rowKeys ->
      val rowLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(1), 0, dp(1))
      }
      rowKeys.forEach { alternate ->
        val btn = createKeyButton(labelFor(alternate), alternate, colors, false).apply {
          textSize = if (alternate.length > 1) 18f else 24f
          tag = alternate   // stored for slide-to-select
          setOnClickListener {
            handleKey(alternate)
            dismissAlternatePopup()
            updateStatus("\u2713 alternate")
          }
        }
        buttons.add(btn)
        rowLayout.addView(btn, LinearLayout.LayoutParams(dp(44), dp(46)).apply {
          setMargins(dp(2), 0, dp(2), 0)
        })
      }
      container.addView(rowLayout)
    }

    slideAlternateButtons = buttons
    slideHighlightedIndex = 0

    val yOffset = if (alternates.size > 6) -dp(120) else -dp(98)

    alternatePopup = PopupWindow(
      container,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      false,
    ).apply {
      isOutsideTouchable = true
      isClippingEnabled = true
      elevation = dp(10).toFloat()
      showAsDropDown(anchor, 0, yOffset)
    }

    buttons.firstOrNull()?.let {
      it.background = keyBackground(colors, true)
      it.alpha = 1f
    }
  }

  private fun dismissAlternatePopup() {
    alternatePopup?.dismiss()
    alternatePopup = null
    slideAlternateButtons = emptyList()
    slideHighlightedIndex = -1
  }

  private fun alternatesForKey(key: String): List<String> {
    val numberAlternates = topRowNumberAlternates()[key].orEmpty()
    val letterAlternates = when (layoutMode) {
      LayoutMode.UR -> urduAlternates()[key].orEmpty()
      LayoutMode.AR -> arabicAlternates()[key].orEmpty()
      LayoutMode.EN, LayoutMode.SYMBOLS -> englishAlternates()[key].orEmpty()
    }

    return (numberAlternates + letterAlternates).distinct().filter { it != key }
  }

  private fun topRowNumberAlternates(): Map<String, List<String>> {
    val topRow = when (layoutMode) {
      LayoutMode.UR -> listOf("\u0636", "\u0635", "\u063A", "\u0691", "\u0679", "\u062B", "\u062D", "\u0626", "\u0638", "\u0637")
      LayoutMode.AR -> listOf("\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062C")
      else -> emptyList()
    }

    return topRow.zip(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
      .associate { (letter, number) -> letter to listOf(number) }
  }

  private fun englishAlternates(): Map<String, List<String>> {
    return mapOf(
      "a" to listOf("@", "\u00E1", "\u00E0"),
      "e" to listOf("\u00E9", "\u00E8"),
      "i" to listOf("\u00ED", "\u00EC"),
      "o" to listOf("\u00F3", "\u00F2"),
      "u" to listOf("\u00FA", "\u00F9"),
      "." to listOf(",", "?", "!"),
      "," to listOf("emoji"),
    )
  }

  private fun emojiPages(): List<List<String>> {
    return listOf(
      listOf(
        "\uD83D\uDE00", "\uD83D\uDE03", "\uD83D\uDE04", "\uD83D\uDE01", "\uD83D\uDE06", "\uD83D\uDE05", "\uD83E\uDD23", "\uD83D\uDE02",
        "\uD83D\uDE42", "\uD83D\uDE43", "\uD83D\uDE09", "\uD83D\uDE0A", "\uD83D\uDE07", "\uD83E\uDD70", "\uD83D\uDE0D", "\uD83E\uDD29",
        "\uD83D\uDE18", "\uD83D\uDE17", "\u263A\uFE0F", "\uD83D\uDE1A", "\uD83D\uDE19", "\uD83E\uDD72", "\uD83D\uDE0B", "\uD83D\uDE1B",
        "\uD83D\uDE1C", "\uD83E\uDD2A", "\uD83D\uDE1D", "\uD83E\uDD11", "\uD83E\uDD17", "\uD83E\uDD2D", "\uD83E\uDEE2", "\uD83E\uDEE3",
      ),
      listOf(
        "\uD83E\uDD14", "\uD83E\uDEE1", "\uD83E\uDD10", "\uD83E\uDD28", "\uD83D\uDE10", "\uD83D\uDE11", "\uD83D\uDE36", "\uD83D\uDE36\u200D\uD83C\uDF2B\uFE0F",
        "\uD83D\uDE0F", "\uD83D\uDE12", "\uD83D\uDE44", "\uD83D\uDE2C", "\uD83D\uDE2E\u200D\uD83D\uDCA8", "\uD83E\uDD25", "\uD83D\uDE0C", "\uD83D\uDE14",
        "\uD83D\uDE2A", "\uD83E\uDD24", "\uD83D\uDE34", "\uD83D\uDE37", "\uD83E\uDD12", "\uD83E\uDD15", "\uD83E\uDD27", "\uD83E\uDD2E",
        "\uD83E\uDD22", "\uD83E\uDD2F", "\uD83E\uDD20", "\uD83E\uDD73", "\uD83E\uDD78", "\uD83D\uDE0E", "\uD83E\uDD13", "\uD83E\uDDD0",
      ),
      listOf(
        "\uD83D\uDE15", "\uD83E\uDEE4", "\uD83D\uDE1F", "\uD83D\uDE2E", "\uD83D\uDE2F", "\uD83D\uDE32", "\uD83D\uDE33", "\uD83E\uDD7A",
        "\uD83E\uDD79", "\uD83D\uDE26", "\uD83D\uDE27", "\uD83D\uDE28", "\uD83D\uDE30", "\uD83D\uDE25", "\uD83D\uDE22", "\uD83D\uDE2D",
        "\uD83D\uDE31", "\uD83D\uDE16", "\uD83D\uDE23", "\uD83D\uDE1E", "\uD83D\uDE13", "\uD83D\uDE29", "\uD83D\uDE2B", "\uD83E\uDD71",
        "\uD83D\uDE24", "\uD83D\uDE21", "\uD83D\uDE20", "\uD83E\uDD2C", "\uD83D\uDE08", "\uD83D\uDC7F", "\uD83D\uDC80", "\u2620\uFE0F",
      ),
      listOf(
        "\uD83D\uDC4B", "\uD83E\uDD1A", "\uD83D\uDD90\uFE0F", "\u270B", "\uD83D\uDD96", "\uD83D\uDC4C", "\uD83E\uDD0C", "\uD83E\uDD0F",
        "\u270C\uFE0F", "\uD83E\uDD1E", "\uD83E\uDEE0", "\uD83E\uDD1F", "\uD83E\uDD18", "\uD83E\uDD19", "\uD83D\uDC48", "\uD83D\uDC49",
        "\uD83D\uDC46", "\uD83D\uDC47", "\u261D\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\u270A", "\uD83D\uDC4A", "\uD83E\uDD1B",
        "\uD83E\uDD1C", "\uD83D\uDC4F", "\uD83E\uDEB6", "\uD83D\uDE4C", "\uD83E\uDEE3", "\uD83E\uDD32", "\uD83D\uDE4F", "\u270D\uFE0F",
      ),
      listOf(
        "\uD83D\uDC76", "\uD83E\uDDD2", "\uD83D\uDC66", "\uD83D\uDC67", "\uD83E\uDDD1", "\uD83D\uDC68", "\uD83D\uDC69", "\uD83E\uDDD4",
        "\uD83D\uDC74", "\uD83D\uDC75", "\uD83D\uDE4D", "\uD83D\uDE4E", "\uD83D\uDE45", "\uD83D\uDE46", "\uD83D\uDC81", "\uD83D\uDE4B",
        "\uD83E\uDDD1\u200D\uD83C\uDF93", "\uD83E\uDDD1\u200D\uD83D\uDCBB", "\uD83E\uDDD1\u200D\uD83C\uDFEB", "\uD83E\uDDD1\u200D\u2695\uFE0F", "\uD83E\uDDD1\u200D\uD83D\uDE80", "\uD83D\uDC6E", "\uD83D\uDD75\uFE0F", "\uD83D\uDC82",
        "\uD83E\uDD77", "\uD83D\uDC77", "\uD83E\uDD34", "\uD83D\uDC78", "\uD83D\uDC73", "\uD83D\uDC72", "\uD83E\uDD35", "\uD83D\uDC70",
      ),
      listOf(
        "\uD83D\uDC36", "\uD83D\uDC31", "\uD83D\uDC2D", "\uD83D\uDC39", "\uD83D\uDC30", "\uD83E\uDD8A", "\uD83D\uDC3B", "\uD83D\uDC3C",
        "\uD83D\uDC28", "\uD83D\uDC2F", "\uD83E\uDD81", "\uD83D\uDC2E", "\uD83D\uDC37", "\uD83D\uDC38", "\uD83D\uDC35", "\uD83D\uDE48",
        "\uD83D\uDE49", "\uD83D\uDE4A", "\uD83D\uDC14", "\uD83D\uDC27", "\uD83D\uDC26", "\uD83E\uDD86", "\uD83E\uDD85", "\uD83E\uDD89",
        "\uD83E\uDD87", "\uD83D\uDC3A", "\uD83D\uDC17", "\uD83D\uDC34", "\uD83E\uDD84", "\uD83D\uDC1D", "\uD83E\uDD8B", "\uD83D\uDC1E",
      ),
      listOf(
        "\uD83C\uDF4F", "\uD83C\uDF4E", "\uD83C\uDF50", "\uD83C\uDF4A", "\uD83C\uDF4B", "\uD83C\uDF4C", "\uD83C\uDF49", "\uD83C\uDF47",
        "\uD83C\uDF53", "\uD83E\uDED0", "\uD83C\uDF48", "\uD83C\uDF52", "\uD83C\uDF51", "\uD83E\uDD6D", "\uD83C\uDF4D", "\uD83E\uDD65",
        "\uD83E\uDD5D", "\uD83C\uDF45", "\uD83E\uDD51", "\uD83E\uDD66", "\uD83E\uDD52", "\uD83C\uDF36\uFE0F", "\uD83E\uDED1", "\uD83C\uDF3D",
        "\uD83E\uDD55", "\uD83E\uDED2", "\uD83C\uDF5E", "\uD83E\uDD50", "\uD83E\uDD56", "\uD83E\uDD68", "\uD83E\uDD6F", "\uD83E\uDD5E",
      ),
      listOf(
        "\uD83C\uDF54", "\uD83C\uDF5F", "\uD83C\uDF55", "\uD83C\uDF2D", "\uD83E\uDD6A", "\uD83C\uDF2E", "\uD83C\uDF2F", "\uD83E\uDED4",
        "\uD83E\uDD57", "\uD83E\uDD58", "\uD83C\uDF5D", "\uD83C\uDF5C", "\uD83C\uDF72", "\uD83C\uDF5B", "\uD83C\uDF63", "\uD83C\uDF71",
        "\uD83E\uDD5F", "\uD83E\uDD60", "\uD83C\uDF66", "\uD83C\uDF67", "\uD83C\uDF70", "\uD83C\uDF82", "\uD83E\uDDC1", "\uD83C\uDF6B",
        "\u2615", "\uD83E\uDD64", "\uD83E\uDDC3", "\uD83C\uDF7A", "\uD83C\uDF77", "\uD83E\uDD42", "\uD83C\uDF79", "\uD83E\uDDC9",
      ),
      listOf(
        "\uD83D\uDE97", "\uD83D\uDE95", "\uD83D\uDE99", "\uD83D\uDE8C", "\uD83D\uDE8E", "\uD83C\uDFCE\uFE0F", "\uD83D\uDE93", "\uD83D\uDE91",
        "\uD83D\uDE92", "\uD83D\uDE9A", "\uD83D\uDEB2", "\uD83D\uDEF5", "\uD83C\uDFCD\uFE0F", "\u2708\uFE0F", "\uD83D\uDE80", "\uD83D\uDEF8",
        "\uD83D\uDE81", "\uD83D\uDE86", "\uD83D\uDE87", "\uD83D\uDEA2", "\u26F5", "\uD83D\uDEA4", "\uD83D\uDDFD", "\uD83D\uDDFC",
        "\uD83C\uDFE0", "\uD83C\uDFE2", "\uD83C\uDFE5", "\uD83C\uDFE6", "\uD83C\uDFE8", "\uD83C\uDFEA", "\uD83D\uDCCD", "\uD83D\uDDFA\uFE0F",
      ),
      listOf(
        "\uD83D\uDCF1", "\uD83D\uDCBB", "\u2328\uFE0F", "\uD83D\uDDA5\uFE0F", "\uD83D\uDDA8\uFE0F", "\uD83D\uDCF7", "\uD83C\uDFA5", "\uD83D\uDCFA",
        "\uD83D\uDCB0", "\uD83D\uDCB3", "\uD83D\uDC8E", "\u2696\uFE0F", "\uD83D\uDEE0\uFE0F", "\u2699\uFE0F", "\uD83D\uDD27", "\uD83D\uDD28",
        "\uD83D\uDCA1", "\uD83D\uDD26", "\uD83D\uDD0B", "\uD83E\uDDF2", "\uD83D\uDCD6", "\uD83D\uDCDD", "\uD83D\uDCCB", "\uD83D\uDCCC",
        "\uD83D\uDCE7", "\uD83D\uDCE8", "\uD83D\uDD12", "\uD83D\uDD11", "\uD83D\uDD0E", "\uD83D\uDD17", "\u2702\uFE0F", "\uD83D\uDDD1\uFE0F",
      ),
      listOf(
        "\u2764\uFE0F", "\uD83E\uDDE1", "\uD83D\uDC9B", "\uD83D\uDC9A", "\uD83D\uDC99", "\uD83D\uDC9C", "\uD83E\uDD0E", "\uD83D\uDDA4",
        "\uD83E\uDD0D", "\uD83D\uDC94", "\u2763\uFE0F", "\uD83D\uDCAF", "\uD83D\uDCA2", "\uD83D\uDCA5", "\uD83D\uDCAB", "\uD83D\uDCA6",
        "\uD83D\uDCA8", "\uD83D\uDD73\uFE0F", "\uD83D\uDCA3", "\uD83D\uDCAC", "\uD83D\uDCAC", "\uD83D\uDDE8\uFE0F", "\uD83D\uDDEF\uFE0F", "\uD83D\uDCA4",
        "\uD83C\uDF1F", "\u2728", "\u26A1", "\uD83D\uDD25", "\uD83C\uDF08", "\u2600\uFE0F", "\uD83C\uDF19", "\u2B50",
      ),
      listOf(
        "\u2705", "\u2611\uFE0F", "\u2714\uFE0F", "\u274C", "\u274E", "\u2795", "\u2796", "\u2797",
        "\u27A1\uFE0F", "\u2B05\uFE0F", "\u2B06\uFE0F", "\u2B07\uFE0F", "\u2197\uFE0F", "\u2198\uFE0F", "\u21A9\uFE0F", "\u21AA\uFE0F",
        "\u2139\uFE0F", "\u2757", "\u2753", "\u2755", "\u2754", "\u267B\uFE0F", "\u267E\uFE0F", "\u269C\uFE0F",
        "\uD83D\uDD31", "\uD83D\uDD30", "\u2B55", "\uD83D\uDFE2", "\uD83D\uDD35", "\uD83D\uDFE3", "\uD83D\uDFE1", "\u26AB",
      ),
    )
  }

  private fun urduAlternates(): Map<String, List<String>> {
    return mapOf(
      "urdu_comma" to listOf("emoji"),
      "urdu_qmark" to listOf("zabar", "zer", "pesh", "do_zabar", "do_zer", "do_pesh", "sukun", "shadda", "maddah", "khari_zabar", "khari_zer", "ulta_pesh", "hamza_above", "marks"),
      "\u0627" to listOf("\u0622", "\u0623", "\u0625"),
      "\u0628" to listOf("\u067E"),
      "\u062A" to listOf("\u0679", "\u062B"),
      "\u062C" to listOf("\u0686"),
      "\u062D" to listOf("\u062E"),
      "\u062F" to listOf("\u0688", "\u0630"),
      "\u0631" to listOf("\u0691"),
      "\u0632" to listOf("\u0698"),
      "\u0633" to listOf("\u0634", "\u0635"),
      "\u0637" to listOf("\u0638"),
      "\u06A9" to listOf("\u06AF", "\u0643"),
      "\u0646" to listOf("\u06BA"),
      "\u06C1" to listOf("\u06BE", "\u06C2", "\u06C3", "\u0629"),
      "\u0621" to listOf("\u0654", "\u0624", "\u0626"),
      "\u0648" to listOf("\u0624"),
      "\u06CC" to listOf("\u0626", "\u06D2", "\u064A"),
      "\u06D2" to listOf("\u0626\u06D2", "\u0626", "\u06CC"),
      "\u0644" to listOf("\u0644\u0627"),
    )
  }

  private fun arabicAlternates(): Map<String, List<String>> {
    return mapOf(
      "urdu_comma" to listOf("emoji"),
      "urdu_qmark" to listOf("zabar", "zer", "pesh", "do_zabar", "marks"),
      "\u0627" to listOf("\u0623", "\u0625", "\u0622"),
      "\u062A" to listOf("\u062B", "\u0629"),
      "\u062D" to listOf("\u062E"),
      "\u062F" to listOf("\u0630"),
      "\u0633" to listOf("\u0634", "\u0635"),
      "\u0637" to listOf("\u0638"),
      "\u0647" to listOf("\u0629"),
      "\u0621" to listOf("\u0654", "\u0624", "\u0626"),
      "\u0648" to listOf("\u0624"),
      "\u064A" to listOf("\u0649", "\u0626"),
      "\u0644" to listOf("\u0644\u0627"),
    )
  }

  private fun insertSuggestion(suggestion: String) {
    val connection = currentInputConnection ?: return
    
    if (suggestion == typedBuffer) {
      NativeClipStore.addLearnedWord(this, suggestion)
    }

    lastSuggestionOriginal = typedBuffer.ifBlank { null }
    lastSuggestionApplied = suggestion

    if (typedBuffer.isNotEmpty()) {
      connection.deleteSurroundingText(typedBuffer.length, 0)
    }
    
    connection.commitText("$suggestion ", 1)
    registerWord(suggestion)
    typedBuffer = ""
    updateStatus("\u2713 accepted \u2014 \u232B to undo")
    updateSuggestionStrip()
  }

  private fun performAIFix() {
    val connection = currentInputConnection ?: return
    val selected = connection.getSelectedText(0)?.toString()?.trim().orEmpty()
    
    var text = selected
    if (text.isBlank()) {
      val before = connection.getTextBeforeCursor(2000, 0)?.toString() ?: ""
      val after = connection.getTextAfterCursor(2000, 0)?.toString() ?: ""
      text = (before + after).trim()
    }
    
    if (text.isBlank()) {
      val request = android.view.inputmethod.ExtractedTextRequest()
      text = connection.getExtractedText(request, 0)?.text?.toString()?.trim().orEmpty()
    }
    
    if (text.isBlank()) return

    val correctedText = localSmartFix(text)
    if (correctedText.isNotBlank() && correctedText != text) {
      if (selected.isNotBlank()) {
        connection.commitText(correctedText, 1)
      } else {
        val beforeLen = connection.getTextBeforeCursor(2000, 0)?.length ?: 0
        val afterLen = connection.getTextAfterCursor(2000, 0)?.length ?: 0
        if (beforeLen > 0 || afterLen > 0) {
            connection.deleteSurroundingText(beforeLen, afterLen)
            connection.commitText(correctedText, 1)
        } else {
            connection.deleteSurroundingText(text.length, 0)
            connection.commitText(correctedText, 1)
        }
      }
      updateStatus("✨ AI fix applied")
    } else {
      updateStatus("no fixes needed")
    }
    typedBuffer = ""
    updateSuggestionStrip()
  }

  private fun performAITranslation() {
    val connection = currentInputConnection ?: return
    val selected = connection.getSelectedText(0)?.toString()?.trim().orEmpty()
    
    var text = selected
    if (text.isBlank()) {
      val before = connection.getTextBeforeCursor(2000, 0)?.toString() ?: ""
      val after = connection.getTextAfterCursor(2000, 0)?.toString() ?: ""
      text = (before + after).trim()
    }
    
    if (text.isBlank()) {
      val request = android.view.inputmethod.ExtractedTextRequest()
      text = connection.getExtractedText(request, 0)?.text?.toString()?.trim().orEmpty()
    }
    
    if (text.isBlank()) return

    val translatedText = localSmartTranslate(text)
    if (translatedText.isNotBlank() && translatedText != text) {
      if (selected.isNotBlank()) {
        connection.commitText(translatedText, 1)
      } else {
        val beforeLen = connection.getTextBeforeCursor(2000, 0)?.length ?: 0
        val afterLen = connection.getTextAfterCursor(2000, 0)?.length ?: 0
        if (beforeLen > 0 || afterLen > 0) {
            connection.deleteSurroundingText(beforeLen, afterLen)
            connection.commitText(translatedText, 1)
        } else {
            connection.deleteSurroundingText(text.length, 0)
            connection.commitText(translatedText, 1)
        }
      }
      updateStatus("🌐 translated")
    } else {
      updateStatus("translation needs cloud AI")
    }
    typedBuffer = ""
    updateSuggestionStrip()
  }

  private fun localSmartFix(value: String): String {
    var out = value
      .replace(Regex("[ \\t]+"), " ")
      .replace(Regex("\\s+([,.!?؟،۔])"), "$1")
      .replace(Regex("\\n{3,}"), "\n\n")
      .trim()

    val replacements = mapOf(
      "teh" to "the",
      "adn" to "and",
      "recieve" to "receive",
      "adress" to "address",
      "mesage" to "message",
      "setings" to "settings",
      "clipbord" to "clipboard",
      "keybord" to "keyboard",
      "\u0627\u0645\u06CC\u0646" to "\u0622\u0645\u06CC\u0646",
      "\u0627\u067E" to "\u0622\u067E",
      "\u0627\u062C" to "\u0622\u062C",
      "\u0627\u0633\u0627\u0646" to "\u0622\u0633\u0627\u0646",
      "\u0627\u0648\u0627\u0632" to "\u0622\u0648\u0627\u0632",
    )

    replacements.forEach { (bad, good) ->
      out = out.replace(Regex("\\b${Regex.escape(bad)}\\b", RegexOption.IGNORE_CASE), good)
    }
    return out
  }

  private fun localSmartTranslate(value: String): String {
    val rtl = value.any { it.code in 0x0600..0x06FF }
    val lower = value.lowercase().trim()
    if (rtl) {
      val exact = mapOf(
        "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u06CC\u06A9\u0645" to "Hello",
        "\u0634\u06A9\u0631\u06CC\u06C1" to "Thank you",
        "\u0628\u0631\u0627\u06C1 \u06A9\u0631\u0645" to "Please",
        "\u062C\u06CC" to "Yes",
        "\u0646\u06C1\u06CC\u06BA" to "No",
        "\u0622\u062C" to "Today",
        "\u06A9\u0644" to "Tomorrow",
      )
      exact[value.trim()]?.let { return it }
      return value
        .replace("\u0634\u06A9\u0631\u06CC\u06C1", "thank you")
        .replace("\u0628\u0631\u0627\u06C1 \u06A9\u0631\u0645", "please")
        .replace("\u0622\u062C", "today")
        .replace("\u0622\u067E", "you")
    }

    val exactEn = mapOf(
      "hello" to "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u06CC\u06A9\u0645",
      "thank you" to "\u0634\u06A9\u0631\u06CC\u06C1",
      "please" to "\u0628\u0631\u0627\u06C1 \u06A9\u0631\u0645",
      "yes" to "\u062C\u06CC",
      "no" to "\u0646\u06C1\u06CC\u06BA",
      "today" to "\u0622\u062C",
      "tomorrow" to "\u06A9\u0644",
    )
    exactEn[lower]?.let { return it }
    return value
      .replace(Regex("\\bplease\\b", RegexOption.IGNORE_CASE), "\u0628\u0631\u0627\u06C1 \u06A9\u0631\u0645")
      .replace(Regex("\\bthank you\\b", RegexOption.IGNORE_CASE), "\u0634\u06A9\u0631\u06CC\u06C1")
      .replace(Regex("\\btoday\\b", RegexOption.IGNORE_CASE), "\u0622\u062C")
      .replace(Regex("\\byou\\b", RegexOption.IGNORE_CASE), "\u0622\u067E")
  }

  // ─────────────────────────────────────────────────────────────
  // Spell Checker Callbacks
  // ─────────────────────────────────────────────────────────────
  override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}

  override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
    val newSuggestions = mutableListOf<String>()
    results?.forEach { sentenceInfo ->
      for (i in 0 until sentenceInfo.suggestionsCount) {
        val info = sentenceInfo.getSuggestionsInfoAt(i)
        for (j in 0 until info.suggestionsCount) {
          newSuggestions.add(info.getSuggestionAt(j))
        }
      }
    }
    if (newSuggestions.isNotEmpty()) {
      nativeSuggestions = newSuggestions.distinct().take(3)
      mainHandler.post { updateSuggestionStrip() }
    }
  }

  // ─────────────────────────────────────────────────────────────
  // Simple Suggestion Fallback / Learning
  // ─────────────────────────────────────────────────────────────
  private fun suggestionsForBuffer(): List<String> {
    val typed = typedBuffer.trim()
    if (typed.isBlank()) return emptyList()

    // Math Calculator
    val mathRegex = Regex("""^(\d+(?:\.\d+)?)\s*([\+\-\*/])\s*(\d+(?:\.\d+)?)\s*=$""")
    val mathMatch = mathRegex.find(typed)
    if (mathMatch != null) {
      try {
        val num1 = mathMatch.groupValues[1].toDouble()
        val op = mathMatch.groupValues[2]
        val num2 = mathMatch.groupValues[3].toDouble()
        val result = when (op) {
          "+" -> num1 + num2
          "-" -> num1 - num2
          "*" -> num1 * num2
          "/" -> if (num2 != 0.0) num1 / num2 else Double.NaN
          else -> Double.NaN
        }
        if (!result.isNaN()) {
          val formatted = if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
          return listOf(formatted)
        }
      } catch (e: Exception) {}
    }

    if (isSearchingClips) {
      return NativeClipStore.search(this, typed, 5).map { it.replace("\n", " ").trim() }
    }

    val dictionary = dictionaryForLayout() + getPersonalDictionary() + NativeClipStore.getLearnedWords(this)
    val normalized = typed.lowercase()
    val correction = bestCorrectionFor(typed)?.takeIf { it != typed }
    val prefixMatches = dictionary
      .filter { it.lowercase().startsWith(normalized) && it != typed }
      .take(3)
    val fuzzyMatches = dictionary
      .map { it to editDistance(normalized, it.lowercase()) }
      .filter { (_, distance) -> distance <= allowedCorrectionDistance(normalized.length) }
      .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
      .map { it.first }
    val recommendations = dictionary
      .filter { it != typed && it !in prefixMatches && it !in fuzzyMatches }
      .filter { it.length >= typed.length }
      .take(3)

    val res = mutableListOf<String>()
    if (correction != null) {
      res.add(correction)
      res.add(typed) // Literal typed word is second if there's a correction
    } else {
      res.add(typed) // Literal typed word is first if no correction
    }
    res.addAll(prefixMatches)
    res.addAll(fuzzyMatches)
    res.addAll(recommendations)
    
    return res.distinct().take(3)
  }

  private fun bestCorrectionFor(value: String): String? {
    val typed = value.trim()
    if (typed.length < 3 || typed.any { it.isDigit() }) return null

    val normalized = typed.lowercase()
    // Check common corrections, skip if user rejected this pair before
    commonCorrectionFor(normalized)?.let { correction ->
      if ("$typed\u00B6$correction" !in rejectedCorrections) return correction
    }

    val exact = dictionaryForLayout().firstOrNull { it.lowercase() == normalized }
    if (exact != null) return null

    return dictionaryForLayout()
      .map { it to editDistance(normalized, it.lowercase()) }
      .filter { (candidate, distance) ->
        distance in 1..allowedCorrectionDistance(normalized.length) &&
        "$typed\u00B6$candidate" !in rejectedCorrections  // skip user-rejected
      }
      .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
      .firstOrNull()?.first
  }

  private fun commonCorrectionFor(value: String): String? {
    val english = mapOf(
      "teh" to "the",
      "adn" to "and",
      "recieve" to "receive",
      "adress" to "address",
      "mesage" to "message",
      "setings" to "settings",
      "clipbord" to "clipboard",
      "keybord" to "keyboard",
      "whatsapp" to "WhatsApp",
      "mindcrafts" to "MindCrafts_AI",
      "codehub" to "codehubb",
    )
    val urdu = mapOf(
      "\u0627\u0645\u06CC\u0646" to "\u0622\u0645\u06CC\u0646",
      "\u0627\u067E" to "\u0622\u067E",
      "\u0627\u062C" to "\u0622\u062C",
      "\u0627\u0648\u0627\u0632" to "\u0622\u0648\u0627\u0632",
      "\u0627\u0633\u0627\u0646" to "\u0622\u0633\u0627\u0646",
      "\u0627\u0632\u0627\u062F" to "\u0622\u0632\u0627\u062F",
    )

    return when (layoutMode) {
      LayoutMode.UR -> urdu[value]
      LayoutMode.AR -> null
      LayoutMode.EN, LayoutMode.SYMBOLS -> english[value]
    }
  }

  private fun allowedCorrectionDistance(length: Int): Int {
    return when {
      length <= 4 -> 1
      length <= 8 -> 2
      else -> 3
    }
  }

  private fun dictionaryForLayout(): List<String> {
    val base = when (layoutMode) {
      LayoutMode.EN, LayoutMode.SYMBOLS -> listOf(
        "about",
        "account",
        "address",
        "appointment",
        "available",
        "beautiful",
        "backup",
        "business",
        "clipboard",
        "confirm",
        "contact",
        "details",
        "document",
        "email",
        "hello",
        "important",
        "keyboard",
        "meeting",
        "message",
        "mindcrafts",
        "number",
        "payment",
        "please",
        "project",
        "reply",
        "restore",
        "saved",
        "search",
        "settings",
        "thanks",
        "today",
        "tomorrow",
        "update",
        "typing",
        "suggestion",
        "capture",
        "export",
        "category",
        "phone",
        "email",
        "number",
        "dashboard",
        "feature",
        "professional",
        "application",
        "automatic",
        "correction",
        "language",
        "privacy",
        "website",
      )
      LayoutMode.UR -> listOf(
        "\u0622\u062C",
        "\u0622\u067E",
        "\u0622\u0645\u06CC\u0646",
        "\u0627\u0644\u0633\u0644\u0627\u0645",
        "\u0627\u0646 \u0634\u0627\u0621 \u0627\u0644\u0644\u06C1",
        "\u0628\u0631\u0627\u06C1 \u06A9\u0631\u0645",
        "\u0628\u06C1\u062A\u0631\u06CC\u0646",
        "\u062C\u06CC",
        "\u062C\u0648\u0627\u0628",
        "\u0631\u0627\u0628\u0637\u06C1",
        "\u0633\u06CC\u0648",
        "\u0633\u06CC\u0679\u0646\u06AF\u0632",
        "\u0634\u06A9\u0631\u06CC\u06C1",
        "\u0633\u062C\u06CC\u0634\u0646",
        "\u06A9\u0631\u06CC\u06A9\u0634\u0646",
        "\u06A9\u06CC\u067E\u0686\u0631",
        "\u06A9\u06CC\u0679\u06CC\u06AF\u0631\u06CC",
        "\u0641\u06CC\u0686\u0631",
        "\u0632\u0628\u0627\u0646",
        "\u0679\u0627\u0626\u067E\u0646\u06AF",
        "\u06A9\u06CC \u0628\u0648\u0631\u0688",
        "\u06A9\u0644\u067E \u0628\u0648\u0631\u0688",
        "\u0645\u06CC\u0633\u062C",
        "\u0646\u0645\u0628\u0631",
        "\u0648\u06CC\u0628 \u0633\u0627\u0626\u0679",
        "\u06A9\u0644",
      )
      LayoutMode.AR -> listOf(
        "\u0627\u0644\u064A\u0648\u0645",
        "\u0627\u0644\u062D\u0633\u0627\u0628",
        "\u0627\u0644\u062F\u0641\u0639",
        "\u0627\u0644\u0631\u0633\u0627\u0644\u0629",
        "\u0631\u0627\u0628\u0637",
        "\u0631\u0642\u0645",
        "\u0634\u0643\u0631\u0627",
        "\u063A\u062F\u0627",
        "\u0645\u0648\u0639\u062F",
        "\u0645\u0646 \u0641\u0636\u0644\u0643",
        "\u0646\u0639\u0645",
      )
    }

    return (base + learnedWordsForLayout()).distinctBy { it.lowercase() }
  }

  // Returns cached learned words — never reads DB on main thread.
  // Cache is populated asynchronously in refreshCachedDataAsync().
  private fun learnedWordsForLayout(): List<String> = cachedLearnedWords

  private fun editDistance(first: String, second: String): Int {
    if (first == second) return 0
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length

    val previous = IntArray(second.length + 1) { it }
    val current = IntArray(second.length + 1)

    for (i in first.indices) {
      current[0] = i + 1
      for (j in second.indices) {
        val cost = if (first[i] == second[j]) 0 else 1
        current[j + 1] = minOf(
          current[j] + 1,
          previous[j + 1] + 1,
          previous[j + 1] + cost,
        )
      }
      for (j in previous.indices) previous[j] = current[j]
    }

    return previous[second.length]
  }

  private fun applySelectedTheme(value: String) {
    themeMode = runCatching { KeyboardTheme.valueOf(value) }.getOrDefault(themeMode)
    panelMode = PanelMode.KEYS
    themePanelScrollY = 0
    themeScrollViewRef = null
    saveKeyboardPrefs()
    refreshKeyboard()
  }

  private fun adjustKeyboardHeight(delta: Float) {
    keyboardHeightScale = (keyboardHeightScale + delta).coerceIn(0.84f, 1.22f)
    updateStatus("height ${(keyboardHeightScale * 100).toInt()}%")
    saveKeyboardPrefs()
    refreshKeyboard()
  }

  private fun adjustKeyTextScale(delta: Float) {
    keyTextScale = (keyTextScale + delta).coerceIn(0.88f, 1.18f)
    updateStatus("key text ${(keyTextScale * 100).toInt()}%")
    saveKeyboardPrefs()
    refreshKeyboard()
  }

  private fun triggerVibration() {
    try {
      val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(15, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(15)
      }
    } catch(e: Exception) {}
  }

  private fun toggleHaptics() {
    hapticsEnabled = !hapticsEnabled
    updateStatus(if (hapticsEnabled) "haptics on" else "haptics off")
    saveKeyboardPrefs()
    refreshKeyboard()
  }

  private fun toggleVoiceTyping() {
    if (isListening) {
      stopVoiceAutoStopTimer()
      voiceShouldKeepListening = false
      speechRecognizer?.cancel()
      finishVoiceTyping("mic off")
      return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
      checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
    ) {
      updateStatus("\u26A0\uFE0F mic permission needed")
      return
    }

    releaseSpeechRecognizer()
    voicePartialText = ""
    voiceEndedWithSilence = false
    voiceShouldKeepListening = true
    voiceSilenceCount = 0
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
      setRecognitionListener(createRecognitionListener())
    }

    isListening = true
    lastVoiceLevelStatusAt = 0L
    startVoicePulse()
    startVoiceAutoStopTimer()  // 4s absolute auto-off
    refreshKeyboard()           // mic button background = indicator
    startVoiceSession("Listening...")
  }

  private fun voiceIntent(): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, layoutMode.voiceLocale)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, layoutMode.voiceLocale)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      putExtra("android.speech.extra.PREFER_OFFLINE", true)
    }
  }

  private fun startVoiceSession(message: String) {
    if (!voiceShouldKeepListening || speechRecognizer == null) return
    currentInputConnection?.finishComposingText()
    voicePartialText = ""
    voiceEndedWithSilence = false
    updateStatus(message)
    startVoiceAutoStopTimer()  // Ensure auto-stop silence timer starts for every session
    speechRecognizer?.startListening(voiceIntent())
  }

  private fun restartVoiceSession(message: String) {
    updateStatus(message)
    repeatHandler.postDelayed({
      if (voiceShouldKeepListening && speechRecognizer != null) {
        startVoiceSession("Mic ON - listening for ${layoutMode.code}")
      }
    }, 260L)
  }

  private fun createRecognitionListener(): RecognitionListener {
    return object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        voiceSilenceCount = 0
        // Flash bright red on ready
        pulseMicBackground(bright = true)
      }

      override fun onBeginningOfSpeech() {
        voiceEndedWithSilence = false
        voiceSilenceCount = 0
        stopVoiceAutoStopTimer()  // speech started — cancel 4s silence timer
        stopVoiceLineBreakTimer() // user is speaking, don't insert line break
        pulseMicBackground(bright = true)
      }

      override fun onRmsChanged(rmsdB: Float) {
        val now = System.currentTimeMillis()
        if (!isListening || now - lastVoiceLevelStatusAt < 300L) return
        lastVoiceLevelStatusAt = now
        // Animate mic button background brightness with voice level
        val alpha = when {
          rmsdB > 8f -> 255
          rmsdB > 4f -> 210
          rmsdB > 1f -> 160
          else -> 100
        }
        (micButtonRef?.background as? GradientDrawable)?.setColor(
          Color.argb(alpha, 200, 30, 30)
        )
      }

      override fun onBufferReceived(buffer: ByteArray?) = Unit

      override fun onEndOfSpeech() {
        voiceEndedWithSilence = true
        updateStatus("Mic ON - processing voice")
      }

      override fun onError(error: Int) {
        if (voiceShouldKeepListening && error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
          voiceSilenceCount++
          if (voiceSilenceCount >= 3) {
            finishVoiceTyping("mic off (timeout)")
            return
          }
          // Resume listening without turning off the mic. 
          // The auto-stop (4s) and line break (3s) timers will handle actual silence logic.
          restartVoiceSession("Listening...")
          return
        }
        finishVoiceTyping(voiceErrorMessage(error))
      }

      override fun onResults(results: Bundle?) {
        val spokenText = results
          ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull()?.trim().orEmpty()

        if (spokenText.isNotBlank()) {
          commitVoiceFinal(spokenText)
          if (voiceShouldKeepListening) {
            startVoiceAutoStopTimer()  // restart 10s timer for next utterance
            startVoiceLineBreakTimer() // start 3s timer for line break if they pause
            restartVoiceSession("Listening...")
          } else {
            finishVoiceTyping("voice inserted")
          }
        } else {
          if (voiceShouldKeepListening) {
            voiceSilenceCount++
            if (voiceSilenceCount >= 3) {
              finishVoiceTyping("mic off (timeout)")
            } else {
              restartVoiceSession("Listening...")
            }
          } else {
            finishVoiceTyping("mic off (no speech)") // Turn off if empty result
          }
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val partialText = partialResults
          ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull()
          ?.trim()
          .orEmpty()

        if (partialText.isNotBlank()) {
          stopVoiceLineBreakTimer() // prevent line break while speaking
          commitVoicePartial(partialText)
          updateStatus("Mic ON - live voice typing")
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
  }

  private fun collapseSelectionToEnd(connection: android.view.inputmethod.InputConnection) {
    try {
      // 1. Use tracked selection from onUpdateSelection first (extremely fast & reliable)
      if (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd) {
        val endPos = maxOf(selectionStart, selectionEnd)
        connection.setSelection(endPos, endPos)
        selectionStart = endPos
        selectionEnd = endPos
        return
      }

      // 2. Fallback to getExtractedText
      val extracted = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
      if (extracted != null) {
        val selStart = extracted.selectionStart
        val selEnd = extracted.selectionEnd
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
          val endPos = maxOf(selStart, selEnd)
          connection.setSelection(endPos, endPos)
        }
      }
    } catch (e: Exception) {}
  }

  private fun commitVoicePartial(partialText: String) {
    val connection = currentInputConnection ?: return
    connection.beginBatchEdit()
    collapseSelectionToEnd(connection)
    var normalizedText = normalizeVoiceText(partialText)
    if (voicePartialText.isEmpty()) {
      val before = connection.getTextBeforeCursor(1, 0)
      if (!before.isNullOrEmpty() && !normalizedText.startsWith(" ")) {
        val lastChar = before[0]
        if (lastChar != ' ' && lastChar != '\n') {
          normalizedText = " $normalizedText"
        }
      }
    } else {
      if (voicePartialText.startsWith(" ") && !normalizedText.startsWith(" ")) {
        normalizedText = " $normalizedText"
      }
    }
    connection.setComposingText(normalizedText, 1)
    voicePartialText = normalizedText
    connection.endBatchEdit()
  }

  private fun commitVoiceFinal(finalText: String) {
    val connection = currentInputConnection ?: return
    connection.beginBatchEdit()
    collapseSelectionToEnd(connection)
    var normalizedText = normalizeVoiceText(finalText)
    if (voicePartialText.startsWith(" ") && !normalizedText.startsWith(" ")) {
      normalizedText = " $normalizedText"
    } else if (voicePartialText.isEmpty()) {
      val before = connection.getTextBeforeCursor(1, 0)
      if (!before.isNullOrEmpty() && !normalizedText.startsWith(" ")) {
        val lastChar = before[0]
        if (lastChar != ' ' && lastChar != '\n') {
          normalizedText = " $normalizedText"
        }
      }
    }
    val suffix = voiceFinalSuffixFor(normalizedText)
    connection.commitText("$normalizedText$suffix", 1)
    connection.finishComposingText()
    voicePartialText = ""
    typedBuffer = ""
    connection.endBatchEdit()
  }

  private fun voiceFinalSuffixFor(value: String): String {
    val trimmed = value.trimEnd()
    if (trimmed.isBlank() || trimmed.endsWith("\n")) return ""
    return " "
  }

  private fun normalizeVoiceText(value: String): String {
    if (layoutMode == LayoutMode.EN || layoutMode == LayoutMode.SYMBOLS) return normalizeEnglishVoiceText(value)
    if (layoutMode != LayoutMode.UR) return value.trim()

    var text = value.trim()

    // 1. Voice Punctuation Replacements
    val voicePunctuation = mapOf(
      "نئی لائن" to "\n",
      "لائن بریک" to "\n",
      "نیا پیراگراف" to "\n\n",
      "کاما" to "\u060C",
      "comma" to "\u060C",
      "فل اسٹاپ" to "\u06D4",
      "full stop" to "\u06D4",
      "period" to "\u06D4",
      "سوالیہ نشان" to "\u061F",
      "question mark" to "\u061F",
      "ndaiye nishan" to "!",
      "ndaiya nishan" to "!",
      "ندائیہ نشان" to "!",
      "exclamation mark" to "!",
      "exclamation point" to "!",
      "کولن" to ":",
      "colon" to ":",
      "سیمی کولن" to "\u061B",
      "semicolon" to "\u061B"
    )
    voicePunctuation.forEach { (from, to) ->
      val pattern = Regex("(?i)(^|[\\s\\u060C\\u06D4,.!?؛؟])${Regex.escape(from)}(?=$|[\\s\\u060C\\u06D4,.!?؛؟])")
      text = pattern.replace(text) { match -> "${match.groupValues[1]}$to" }
    }

    // 2. Spelling Corrections
    val replacements = mapOf(
      "\u0627\u0645\u06CC\u0646" to "\u0622\u0645\u06CC\u0646",
      "\u0627\u067E" to "\u0622\u067E",
      "\u0627\u062C" to "\u0622\u062C",
      "\u0627\u0645" to "\u0622\u0645",
      "\u0627\u0635\u0645\u0627\u0646" to "\u0622\u0633\u0645\u0627\u0646",
      "\u0627\u0648\u0627\u0632" to "\u0622\u0648\u0627\u0632",
      "\u0627\u0632\u0627\u062F" to "\u0622\u0632\u0627\u062F",
      "\u0627\u06AF\u06D2" to "\u0622\u06AF\u06D2",
      "\u0627\u062E\u0631" to "\u0622\u062E\u0631",
      "\u0627\u062E\u0631\u06CC" to "\u0622\u062E\u0631\u06CC",
      "\u0627\u0633\u0627\u0646" to "\u0622\u0633\u0627\u0646",
      "\u0627\u0628\u0627\u062F\u06CC" to "\u0622\u0628\u0627\u062F\u06CC",
      "\u0627\u0645\u062F" to "\u0622\u0645\u062F",
      "\u0627\u062F\u0645\u06CC" to "\u0622\u062F\u0645\u06CC"
    )
    replacements.forEach { (from, to) ->
      val pattern = Regex("(^|[\\s\\u060C\\u06D4,.!?؛؟])${Regex.escape(from)}(?=$|[\\s\\u060C\\u06D4,.!?؛؟])")
      text = pattern.replace(text) { match -> "${match.groupValues[1]}$to" }
    }

    // 3. Clean up spacing
    text = text
      .replace(Regex("\\s+([\u060C\u06D4\u061F\u061B,!:])"), "$1")
      .replace(Regex("([\u060C\u06D4\u061F\u061B,!:])(?=\\S)"), "$1 ")
      .replace(Regex("[ \\t]+"), " ")
      .replace(Regex(" *\\n *"), "\n")
      .trim()

    return text
  }

  private fun normalizeEnglishVoiceText(value: String): String {
    var text = value.trim()

    val replacements = listOf(
      "\\bnew paragraph\\b" to "\n\n",
      "\\bnew line\\b|\\bline break\\b" to "\n",
      "\\bcomma\\b" to ",",
      "\\bfull stop\\b|\\bperiod\\b" to ".",
      "\\bquestion mark\\b" to "?",
      "\\bexclamation mark\\b|\\bexclamation point\\b" to "!",
      "\\bcolon\\b" to ":",
      "\\bsemicolon\\b" to ";",
    )

    replacements.forEach { (pattern, replacement) ->
      text = Regex(pattern, RegexOption.IGNORE_CASE).replace(text, replacement)
    }

    text = text
      .replace(Regex("\\s+([,.;:!?])"), "$1")
      .replace(Regex("([,.;:!?])(?=\\S)"), "$1 ")
      .replace(Regex("[ \\t]+"), " ")
      .replace(Regex(" *\\n *"), "\n")
      .trim()

    return text.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
  }

  private fun finishVoiceTyping(message: String) {
    voiceShouldKeepListening = false
    currentInputConnection?.finishComposingText()
    stopVoicePulse()
    stopVoiceAutoStopTimer()
    stopVoiceLineBreakTimer()
    releaseSpeechRecognizer()
    statusMessage = message
    refreshKeyboard()  // restore normal keyboard (isListening=false)
  }

  private fun startVoiceLineBreakTimer() {
    stopVoiceLineBreakTimer()
    voiceLineBreakRunnable = Runnable {
      if (isListening && voiceShouldKeepListening) {
        val connection = currentInputConnection ?: return@Runnable
        connection.finishComposingText()
        val beforeCursor = connection.getTextBeforeCursor(1, 0)
        if (beforeCursor != null && beforeCursor.toString() == " ") {
            connection.deleteSurroundingText(1, 0)
        }
        connection.commitText("\n", 1)
        updateStatus("Inserted line break (pause)")
      }
    }
    repeatHandler.postDelayed(voiceLineBreakRunnable!!, 3000L)
  }

  private fun stopVoiceLineBreakTimer() {
    voiceLineBreakRunnable?.let { repeatHandler.removeCallbacks(it) }
    voiceLineBreakRunnable = null
  }

  private fun releaseSpeechRecognizer() {
    speechRecognizer?.destroy()
    speechRecognizer = null
    isListening = false
    voiceShouldKeepListening = false
    voiceEndedWithSilence = false
  }

  private fun voiceErrorMessage(error: Int): String {
    return when (error) {
      SpeechRecognizer.ERROR_AUDIO -> "voice audio error"
      SpeechRecognizer.ERROR_CLIENT -> "voice client error"
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission needed"
      SpeechRecognizer.ERROR_NETWORK -> "voice network error"
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "voice network timeout"
      SpeechRecognizer.ERROR_NO_MATCH -> "no voice match"
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "voice recognizer busy"
      SpeechRecognizer.ERROR_SERVER -> "voice server error"
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "voice timeout"
      else -> "voice error $error"
    }
  }

  private fun switchToNextKeyboard() {
    val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val switched = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        switchToNextInputMethod(false)
      } else {
        false
      }
    } catch (_: RuntimeException) {
      false
    }

    if (!switched) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.showInputMethodPicker()
      } else {
        @Suppress("DEPRECATION")
        manager.showInputMethodPicker()
      }
    }
  }

  private fun captureClipboard() {
    try {
      val text = readClipboardText().trim()
      if (text.isBlank() || text == lastClipContent) return

      lastClipContent = text
      if (NativeClipStore.save(this, text)) {
        savedCount += 1
        updateStatus("$savedCount clips saved")
      }
    } catch (e: Exception) {
      // Prevent clipboard manager or DB exceptions from crashing the service
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

  private fun updateStatus(message: String) {
    statusMessage = message
    // statusView?.text = statusText()
  }

  private fun statusText(): String {
    return "VaultBoard ${layoutMode.code} - $statusMessage"
  }

  private fun refreshKeyboard() {
    cancelPendingLongPress()
    dismissAlternatePopup()
    themeScrollViewRef?.let {
      if (it.isShown && it.height > 0) {
        themePanelScrollY = it.scrollY
      }
    }
    drawableCache.evictAll()
    setInputView(onCreateInputView())
  }

  private fun compactLabel(value: String, maxChars: Int): String {
    val cleaned = value.trim()
    return if (cleaned.length <= maxChars) cleaned else "${cleaned.take(maxChars - 1)}..."
  }

  private fun previewColorsForKey(key: String): ThemeColors? {
    if (!key.startsWith("theme_")) return null
    val theme = runCatching { KeyboardTheme.valueOf(key.removePrefix("theme_")) }.getOrNull() ?: return null
    return colorsForTheme(theme)
  }

  private fun themeColors(): ThemeColors {
    return colorsForTheme(themeMode)
  }

  private fun getThemesJson(): JSONObject {
    if (cachedThemesJson == null) {
      val jsonString = runCatching {
        applicationContext.assets.open("themes.json").bufferedReader().use { it.readText() }
      }.getOrDefault("{}")
      cachedThemesJson = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())
    }
    return cachedThemesJson!!
  }

  private fun colorsForTheme(theme: KeyboardTheme): ThemeColors {
    val themes = getThemesJson()
    val themeKey = theme.name
    if (themes.has(themeKey)) {
      val tObj = themes.getJSONObject(themeKey)
      if (tObj.has("keyboard")) {
        val k = tObj.getJSONObject("keyboard")
        return ThemeColors(
          background = Color.parseColor(k.getString("background")),
          surface = Color.parseColor(k.getString("surface")),
          key = Color.parseColor(k.getString("key")),
          specialKey = Color.parseColor(k.getString("specialKey")),
          text = Color.parseColor(k.getString("text")),
          muted = Color.parseColor(k.getString("muted")),
          accent = Color.parseColor(k.getString("accent")),
          stroke = Color.parseColor(k.getString("stroke")),
          radius = k.getInt("radius"),
          elevation = k.getDouble("elevation").toFloat()
        )
      }
    }
    // Fallback if missing
    return ThemeColors(
      background = Color.rgb(27, 27, 32),
      surface = Color.rgb(34, 35, 41),
      key = Color.rgb(55, 56, 64),
      specialKey = Color.rgb(74, 83, 107),
      text = Color.rgb(240, 242, 247),
      muted = Color.rgb(170, 174, 186),
      accent = Color.rgb(231, 236, 255),
      stroke = Color.rgb(60, 61, 70),
      radius = 8,
      elevation = dp(1).toFloat(),
    )
  }

  private fun loadKeyboardPrefs() {
    val prefs = getSharedPreferences("clipvault_keyboard", Context.MODE_PRIVATE)

    if (prefs.getInt("keyboardConfigVersion", 0) < 3) {
      layoutMode = LayoutMode.EN
      lastTextLayoutMode = LayoutMode.EN
      enabledLanguages = mutableSetOf(LayoutMode.EN, LayoutMode.UR)
      themeMode = KeyboardTheme.CLASSIC_DARK
      prefs.edit()
        .putInt("keyboardConfigVersion", 3)
        .putString("layoutMode", layoutMode.name)
        .putString("themeMode", themeMode.name)
        .putString("enabledLanguages", enabledLanguages.joinToString(",") { it.name })
        .putFloat("keyboardHeightScale", keyboardHeightScale)
        .putFloat("keyTextScale", keyTextScale)
        .putBoolean("hapticsEnabled", hapticsEnabled)
        .apply()
      return
    }

    layoutMode = runCatching {
      LayoutMode.valueOf(prefs.getString("layoutMode", LayoutMode.EN.name) ?: LayoutMode.EN.name)
    }.getOrDefault(LayoutMode.EN)

    val savedLanguages = prefs.getString("enabledLanguages", "${LayoutMode.EN.name},${LayoutMode.UR.name}").orEmpty()
    enabledLanguages = savedLanguages
      .split(",")
      .mapNotNull { runCatching { LayoutMode.valueOf(it) }.getOrNull() }
      .filter { it != LayoutMode.SYMBOLS }
      .toMutableSet()
      .apply {
        add(LayoutMode.EN)
        add(LayoutMode.UR)
      }

    if (!enabledLanguages.contains(layoutMode) && layoutMode != LayoutMode.SYMBOLS) {
      layoutMode = LayoutMode.EN
    }

    lastTextLayoutMode = if (layoutMode == LayoutMode.SYMBOLS) LayoutMode.EN else layoutMode

    themeMode = runCatching {
      KeyboardTheme.valueOf(prefs.getString("themeMode", KeyboardTheme.CLASSIC_DARK.name) ?: KeyboardTheme.CLASSIC_DARK.name)
    }.getOrDefault(KeyboardTheme.CLASSIC_DARK)

    keyboardHeightScale = prefs.getFloat("keyboardHeightScale", 1.0f).coerceIn(0.84f, 1.22f)
    keyTextScale = prefs.getFloat("keyTextScale", 1.0f).coerceIn(0.88f, 1.18f)
    hapticsEnabled = prefs.getBoolean("hapticsEnabled", true)

    loadRejectedCorrections()  // restore learned correction rejections
  }

  private fun saveKeyboardPrefs() {
    getSharedPreferences("clipvault_keyboard", Context.MODE_PRIVATE)
      .edit()
      .putInt("keyboardConfigVersion", 3)
      .putString("layoutMode", layoutMode.name)
      .putString("themeMode", themeMode.name)
      .putString("enabledLanguages", enabledLanguages.joinToString(",") { it.name })
      .putFloat("keyboardHeightScale", keyboardHeightScale)
      .putFloat("keyTextScale", keyTextScale)
      .putBoolean("hapticsEnabled", hapticsEnabled)
      .apply()
  }

  private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
  }

  // ── Async data refresh (called from onStartInputView — never blocks UI) ──────
  private fun refreshCachedDataAsync() {
    val now = System.currentTimeMillis()

    backgroundExecutor.execute {
      // 1. Always fetch recent clips instantly without throttling
      val clips = NativeClipStore.recent(this, 80)
      
      mainHandler.post { 
        cachedRecentClips = clips.take(10)
        // Auto-refresh UI if clipboard panel is currently active
        if (panelMode == PanelMode.CLIPS) {
           refreshKeyboard()
        }
      }

      // 2. Throttle learned words computation (heavy)
      if (now - lastLearnedWordsRefreshAt < 30_000L && cachedLearnedWords.isNotEmpty()) {
        return@execute
      }
      lastLearnedWordsRefreshAt = now

      // Compute learned words from clips
      val pattern = when (layoutMode) {
        LayoutMode.UR, LayoutMode.AR -> Regex("[\u0600-\u06FF]{2,24}")
        LayoutMode.EN, LayoutMode.SYMBOLS -> Regex("[A-Za-z][A-Za-z'-]{2,28}")
      }
      val learned = clips
        .flatMap { clip -> pattern.findAll(clip).map { it.value.trim() }.toList() }
        .filter { it.length in 2..28 }
        .groupingBy { it.lowercase() }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { entry ->
          clips.firstNotNullOfOrNull { clip ->
            pattern.findAll(clip).map { it.value }.firstOrNull { it.lowercase() == entry.key }
          } ?: entry.key
        }
        .take(90)

      mainHandler.post {
        cachedRecentClips = clips.take(10)
        cachedLearnedWords = learned
      }
    }
  }

  // ── Voice pulse: smooth red blink on mic BUTTON background ──────────────────
  // The mic icon (🎤) stays constant; only background pulses between bright/dark red.
  private fun startVoicePulse() {
    stopVoicePulse()
    var bright = true
    val r = object : Runnable {
      override fun run() {
        if (!isListening) return
        pulseMicBackground(bright)
        bright = !bright
        mainHandler.postDelayed(this, 500L)  // 0.5s blink cycle
      }
    }
    voicePulseRunnable = r
    mainHandler.postDelayed(r, 300L)
  }

  /** Animates mic button background: bright = vivid red, dark = muted red */
  private fun pulseMicBackground(bright: Boolean) {
    val btn = micButtonRef ?: return
    val bg = btn.background as? GradientDrawable ?: GradientDrawable().also { btn.background = it }
    val colors = themeColors()
    bg.shape = GradientDrawable.RECTANGLE
    bg.cornerRadius = dp(colors.radius).toFloat()
    if (bright) {
      bg.setColor(Color.argb(235, 220, 40, 40))   // bright red
      bg.setStroke(dp(2), Color.rgb(255, 80, 80))
    } else {
      bg.setColor(Color.argb(130, 160, 20, 20))   // dark muted red
      bg.setStroke(dp(1), Color.argb(160, 200, 60, 60))
    }
  }

  private fun stopVoicePulse() {
    voicePulseRunnable?.let { mainHandler.removeCallbacks(it) }
    voicePulseRunnable = null
  }

  // ── 4-second absolute mic auto-stop timer ────────────────────────────────────
  private fun startVoiceAutoStopTimer() {
    stopVoiceAutoStopTimer()
    voiceAutoStopRunnable = Runnable {
      if (isListening) finishVoiceTyping("mic off (10s)")
    }
    mainHandler.postDelayed(voiceAutoStopRunnable!!, 10000L)
  }

  private fun stopVoiceAutoStopTimer() {
    voiceAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
    voiceAutoStopRunnable = null
  }

  // ── Rejected corrections persistence ─────────────────────────────────────────────────
  private fun saveRejectedCorrections() {
    getSharedPreferences("clipvault_keyboard", Context.MODE_PRIVATE)
      .edit()
      .putString("rejectedCorrections", rejectedCorrections.joinToString("\u001E"))
      .apply()
  }

  private fun loadRejectedCorrections() {
    val raw = getSharedPreferences("clipvault_keyboard", Context.MODE_PRIVATE)
      .getString("rejectedCorrections", "") ?: ""
    rejectedCorrections.clear()
    if (raw.isNotBlank()) rejectedCorrections.addAll(raw.split("\u001E").filter { it.isNotBlank() })
    // Cap at 400 entries
    while (rejectedCorrections.size > 400) rejectedCorrections.remove(rejectedCorrections.first())
  }

  // ── Emoji categories ──────────────────────────────────────────────────────────
  private fun emojiCategories(): List<EmojiCategory> = listOf(
    EmojiCategory("\uD83D\uDE00", listOf( // Smileys & Emotion
      "\uD83D\uDE00", "\uD83D\uDE03", "\uD83D\uDE04", "\uD83D\uDE01", "\uD83D\uDE06", "\uD83D\uDE05", "\uD83E\uDD23", "\uD83D\uDE02",
      "\uD83D\uDE42", "\uD83D\uDE43", "\uD83D\uDE09", "\uD83D\uDE0A", "\uD83D\uDE07", "\uD83E\uDD70", "\uD83D\uDE0D", "\uD83E\uDD29",
      "\uD83D\uDE18", "\uD83D\uDE17", "\u263A\uFE0F", "\uD83D\uDE1A", "\uD83D\uDE19", "\uD83E\uDD72", "\uD83D\uDE0B", "\uD83D\uDE1B",
      "\uD83D\uDE1C", "\uD83E\uDD2A", "\uD83D\uDE1D", "\uD83E\uDD11", "\uD83E\uDD17", "\uD83E\uDD2D", "\uD83E\uDEE2", "\uD83E\uDEE3",
      "\uD83E\uDD14", "\uD83E\uDEE1", "\uD83E\uDD10", "\uD83E\uDD28", "\uD83D\uDE10", "\uD83D\uDE11", "\uD83D\uDE36", "\uD83D\uDE0F",
      "\uD83D\uDE12", "\uD83D\uDE44", "\uD83D\uDE2C", "\uD83E\uDD25", "\uD83D\uDE0C", "\uD83D\uDE14", "\uD83D\uDE2A", "\uD83E\uDD24",
      "\uD83D\uDE34", "\uD83D\uDE37", "\uD83E\uDD12", "\uD83E\uDD15", "\uD83E\uDD27", "\uD83E\uDD2E", "\uD83E\uDD22", "\uD83E\uDD2F",
      "\uD83E\uDD20", "\uD83E\uDD73", "\uD83E\uDD78", "\uD83D\uDE0E", "\uD83E\uDD13", "\uD83E\uDDD0", "\uD83D\uDE15", "\uD83E\uDEE4",
      "\uD83D\uDE1F", "\uD83D\uDE2E", "\uD83D\uDE2F", "\uD83D\uDE32", "\uD83D\uDE33", "\uD83E\uDD7A", "\uD83E\uDD79", "\uD83D\uDC7F",
      "\uD83D\uDC80", "\uD83D\uDC79", "\uD83D\uDC7E", "\uD83E\uDDD9", "\uD83E\uDDDF", "\uD83E\uDDDB", "\uD83D\uDE08", "\uD83D\uDC80",
      "\uD83D\uDE31", "\uD83D\uDE16", "\uD83D\uDE23", "\uD83D\uDE1E", "\uD83D\uDE13", "\uD83D\uDE29", "\uD83D\uDE2B", "\uD83E\uDD71",
      "\uD83D\uDE24", "\uD83D\uDE21", "\uD83D\uDE20", "\uD83E\uDD2C", "\uD83D\uDC7D", "\uD83D\uDC7C", "\uD83C\uDF83", "\uD83D\uDC7B",
      "\uD83D\uDE35", "\uD83D\uDE36", "\uD83D\uDE30", "\uD83D\uDE32", "\uD83D\uDE33", "\uD83D\uDE37", "\uD83E\uDD12", "\uD83E\uDD15",
      "\uD83E\uDEE0", "\uD83E\uDEE1", "\uD83E\uDEE2", "\uD83E\uDEE3", "\uD83E\uDEE5", "\uD83D\uDC79", "\uD83D\uDC7A", "\uD83D\uDC7F",
      "\uD83D\uDC8B", "\uD83D\uDC8C", "\uD83D\uDC8D", "\uD83D\uDC8E", "\uD83D\uDC8F", "\uD83D\uDC90", "\uD83D\uDC91", "\uD83D\uDC92"
    )),
    EmojiCategory("\u2764\uFE0F", listOf( // Hearts, Love & Gestures
      "\u2764\uFE0F", "\uD83E\uDDE1", "\uD83D\uDC9B", "\uD83D\uDC9A", "\uD83D\uDC99", "\uD83D\uDC9C", "\uD83E\uDD0E", "\uD83D\uDDA4",
      "\uD83E\uDD0D", "\uD83D\uDC94", "\u2763\uFE0F", "\uD83D\uDCAF", "\uD83D\uDCA2", "\uD83D\uDCA5", "\uD83D\uDCAB", "\uD83D\uDCA6",
      "\uD83D\uDCA8", "\uD83D\uDCA3", "\uD83D\uDCAC", "\uD83D\uDDE8\uFE0F", "\uD83D\uDDEF\uFE0F", "\uD83D\uDCA4", "\uD83C\uDF1F",
      "\u2728", "\u26A1", "\uD83D\uDD25", "\uD83C\uDF08", "\u2600\uFE0F", "\uD83C\uDF19", "\u2B50", "\uD83D\uDC4B", "\uD83E\uDD1A",
      "\uD83D\uDD90\uFE0F", "\u270B", "\uD83D\uDD96", "\uD83D\uDC4C", "\uD83E\uDD0C", "\uD83E\uDD0F", "\u270C\uFE0F", "\uD83E\uDD1E",
      "\uD83E\uDEE0", "\uD83E\uDD1F", "\uD83E\uDD18", "\uD83E\uDD19", "\uD83D\uDC48", "\uD83D\uDC49", "\uD83D\uDC46", "\uD83D\uDC47",
      "\u261D\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\u270A", "\uD83D\uDC4A", "\uD83E\uDD1B", "\uD83E\uDD1C", "\uD83D\uDC4F",
      "\uD83E\uDEB6", "\uD83D\uDE4C", "\uD83E\uDD32", "\uD83D\uDE4F", "\u270D\uFE0F", "\uD83D\uDC85", "\uD83D\uDC8B", "\uD83D\uDC8F",
      "\uD83D\uDC6A", "\uD83D\uDC8B", "\uD83D\uDC69\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68", "\uD83D\uDC9E", "\uD83D\uDC93", "\uD83D\uDC97",
      "\uD83D\uDC9C", "\uD83D\uDC9D", "\uD83D\uDC9F", "\uD83D\uDC8C", "\uD83D\uDC85", "\uD83D\uDC66", "\uD83D\uDC67", "\uD83E\uDDD1",
      "\uD83E\uDD1D", "\uD83E\uDFAF", "\uD83E\uDFB0", "\uD83D\uDCAA", "\uD83D\uDC42", "\uD83D\uDC43", "\uD83D\uDC41", "\uD83D\uDC40"
    )),
    EmojiCategory("\uD83D\uDC36", listOf( // Animals & Nature
      "\uD83D\uDC36", "\uD83D\uDC31", "\uD83D\uDC2D", "\uD83D\uDC39", "\uD83D\uDC30", "\uD83E\uDD8A", "\uD83D\uDC3B", "\uD83D\uDC3C",
      "\uD83D\uDC28", "\uD83D\uDC2F", "\uD83E\uDD81", "\uD83D\uDC2E", "\uD83D\uDC37", "\uD83D\uDC38", "\uD83D\uDC35", "\uD83D\uDE48",
      "\uD83D\uDE49", "\uD83D\uDE4A", "\uD83D\uDC14", "\uD83D\uDC27", "\uD83D\uDC26", "\uD83E\uDD86", "\uD83E\uDD85", "\uD83E\uDD89",
      "\uD83E\uDD87", "\uD83D\uDC3A", "\uD83D\uDC17", "\uD83D\uDC34", "\uD83E\uDD84", "\uD83D\uDC1D", "\uD83E\uDD8B", "\uD83D\uDC1E",
      "\uD83D\uDC0C", "\uD83D\uDC1C", "\uD83D\uDC22", "\uD83D\uDC0D", "\uD83E\uDD8E", "\uD83D\uDC19", "\uD83E\uDD91", "\uD83E\uDD90",
      "\uD83D\uDC1F", "\uD83D\uDC2C", "\uD83E\uDD95", "\uD83E\uDD96", "\uD83E\uDD9F", "\uD83E\uDEB2", "\uD83E\uDEB3", "\uD83E\uDEB0",
      "\uD83C\uDF32", "\uD83C\uDF33", "\uD83C\uDF34", "\uD83C\uDF35", "\uD83C\uDF3E", "\uD83C\uDF3F", "\uD83C\uDF40", "\uD83C\uDF41",
      "\uD83C\uDF39", "\uD83C\uDF3B", "\uD83C\uDF38", "\uD83C\uDF31", "\uD83C\uDF37", "\uD83C\uDF42", "\uD83C\uDF43", "\uD83C\uDF0B",
      "\uD83C\uDF0A", "\uD83C\uDF44", "\uD83C\uDF3C", "\uD83C\uDF3A", "\uD83E\uDD8F", "\uD83E\uDD94", "\uD83E\uDD9C", "\uD83E\uDDB9",
      "\uD83E\uDD8C", "\uD83E\uDD87", "\uD83E\uDD99", "\uD83E\uDDBE", "\uD83E\uDDB5", "\uD83E\uDD9B", "\uD83E\uDD92", "\uD83E\uDD93"
    )),
    EmojiCategory("\uD83C\uDF4E", listOf( // Food & Drink
      "\uD83C\uDF4F", "\uD83C\uDF4E", "\uD83C\uDF50", "\uD83C\uDF4A", "\uD83C\uDF4B", "\uD83C\uDF4C", "\uD83C\uDF49", "\uD83C\uDF47",
      "\uD83C\uDF53", "\uD83E\uDED0", "\uD83C\uDF48", "\uD83C\uDF52", "\uD83C\uDF51", "\uD83E\uDD6D", "\uD83C\uDF4D", "\uD83E\uDD65",
      "\uD83E\uDD5D", "\uD83C\uDF45", "\uD83E\uDD51", "\uD83E\uDD66", "\uD83E\uDD52", "\uD83C\uDF36\uFE0F", "\uD83E\uDED1", "\uD83C\uDF3D",
      "\uD83E\uDD55", "\uD83E\uDED2", "\uD83C\uDF5E", "\uD83E\uDD50", "\uD83E\uDD56", "\uD83E\uDD68", "\uD83E\uDD6F", "\uD83E\uDD5E",
      "\uD83C\uDF54", "\uD83C\uDF5F", "\uD83C\uDF55", "\uD83C\uDF2D", "\uD83E\uDD6A", "\uD83C\uDF2E", "\uD83C\uDF2F", "\uD83E\uDED4",
      "\uD83E\uDD57", "\uD83E\uDD58", "\uD83C\uDF5D", "\uD83C\uDF5C", "\uD83C\uDF72", "\uD83C\uDF5B", "\uD83C\uDF63", "\uD83C\uDF71",
      "\uD83E\uDD5F", "\uD83E\uDD60", "\uD83C\uDF66", "\uD83C\uDF67", "\uD83C\uDF70", "\uD83C\uDF82", "\uD83E\uDDC1", "\uD83C\uDF6B",
      "\u2615", "\uD83E\uDD64", "\uD83E\uDDC3", "\uD83C\uDF7A", "\uD83C\uDF77", "\uD83E\uDD42", "\uD83C\uDF79", "\uD83E\uDDC9",
      "\uD83E\uDD59", "\uD83E\uDDC2", "\uD83E\uDDC7", "\uD83E\uDDCB", "\uD83E\uDD61", "\uD83E\uDD5E", "\uD83E\uDD83", "\uD83E\uDD5C",
      "\uD83C\uDF75", "\uD83C\uDF76", "\uD83C\uDF78", "\uD83C\uDF7B", "\uD83C\uDF7C", "\uD83C\uDF7D", "\uD83C\uDF7E", "\uD83E\uDD69",
      "\uD83E\uDED6", "\uD83E\uDED2", "\uD83E\uDD6C", "\uD83E\uDDCA", "\uD83E\uDDC8", "\uD83E\uDDC9", "\uD83E\uDD5B", "\uD83E\uDD64"
    )),
    EmojiCategory("\u26BD", listOf( // Sports & Activities
      "\u26BD", "\uD83C\uDFC0", "\uD83C\uDFC8", "\u26BE", "\uD83C\uDFBE", "\uD83C\uDFB1", "\uD83C\uDFB3", "\uD83C\uDFAF",
      "\uD83C\uDFC6", "\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49", "\uD83C\uDFC5", "\uD83C\uDF96\uFE0F", "\uD83C\uDFAE", "\uD83C\uDFB9",
      "\uD83C\uDFA8", "\uD83C\uDFAD", "\uD83C\uDFB8", "\uD83C\uDFA4", "\uD83C\uDFA7", "\uD83C\uDFBC", "\uD83C\uDFB5", "\uD83C\uDFB6",
      "\uD83C\uDFAB", "\uD83C\uDF9F\uFE0F", "\uD83C\uDF97\uFE0F", "\uD83C\uDF9E\uFE0F", "\uD83D\uDCF8", "\uD83D\uDCF9", "\uD83D\uDCFA", "\uD83D\uDCBB",
      "\uD83E\uDD4B", "\uD83E\uDD4C", "\uD83E\uDD4D", "\uD83E\uDE80", "\uD83E\uDE81", "\uD83E\uDE82", "\uD83D\uDEF9", "\uD83D\uDED6",
      "\uD83C\uDFF9", "\uD83C\uDFFB", "\uD83E\uDE85", "\uD83E\uDE86", "\uD83D\uDCFE", "\uD83C\uDFB0", "\uD83C\uDFB2", "\uD83D\uDC05",
      "\uD83C\uDCCF", "\uD83E\uDD69", "\uD83E\uDDB8", "\uD83E\uDDB9", "\uD83E\uDDD7", "\uD83E\uDDD8", "\uD83E\uDDDC", "\uD83E\uDDDD",
      "\uD83C\uDFC2", "\uD83C\uDFC7", "\uD83C\uDFCD", "\uD83C\uDFCE", "\uD83D\uDEF4", "\uD83D\uDEF5", "\uD83D\uDEF6", "\uD83C\uDFAA",
      "\uD83E\uDE84", "\uD83E\uDDFF", "\uD83E\uDDD6", "\uD83E\uDDD9", "\uD83E\uDDDA", "\uD83E\uDDDB", "\uD83E\uDDDC", "\uD83E\uDDDD"
    )),
    EmojiCategory("\u2708\uFE0F", listOf( // Travel & Places
      "\uD83D\uDE97", "\uD83D\uDE95", "\uD83D\uDE99", "\uD83D\uDE8C", "\uD83D\uDE8E", "\uD83C\uDFCE\uFE0F", "\uD83D\uDE93", "\uD83D\uDE91",
      "\uD83D\uDE92", "\uD83D\uDE9A", "\uD83D\uDEB2", "\uD83D\uDEF5", "\uD83C\uDFCD\uFE0F", "\u2708\uFE0F", "\uD83D\uDE80", "\uD83D\uDEF8",
      "\uD83D\uDE81", "\uD83D\uDE86", "\uD83D\uDE87", "\uD83D\uDEA2", "\u26F5", "\uD83D\uDEA4", "\uD83D\uDDFD", "\uD83D\uDFC1",
      "\uD83C\uDFE0", "\uD83C\uDFE2", "\uD83C\uDFE5", "\uD83C\uDFE6", "\uD83C\uDFE8", "\uD83C\uDFEA", "\uD83D\uDCCD", "\uD83D\uDDFA\uFE0F",
      "\uD83C\uDFE1", "\uD83C\uDFEB", "\uD83C\uDFEC", "\uD83C\uDFEF", "\uD83C\uDFF0", "\u26EA", "\uD83D\uDDFC", "\uD83C\uDF04",
      "\uD83C\uDF05", "\uD83C\uDF07", "\uD83C\uDF09", "\uD83C\uDF0A", "\uD83C\uDF0C", "\uD83C\uDF0D", "\uD83C\uDF0E", "\uD83C\uDF10",
      "\uD83C\uDF11", "\uD83C\uDF13", "\uD83C\uDF15", "\uD83C\uDF1B", "\uD83C\uDF1F", "\u2B50", "\uD83C\uDF20", "\uD83C\uDF01",
      "\uD83C\uDF03", "\uD83C\uDF06", "\uD83C\uDFE3", "\uD83C\uDFE4", "\uD83C\uDFE7", "\uD83C\uDFE9", "\uD83C\uDFED", "\uD83D\uDFFF",
      "\uD83D\uDEFA", "\uD83D\uDEFC", "\uD83D\uDEFD", "\uD83D\uDEF3", "\u26F2", "\u26F3", "\u26F7", "\u26F9"
    )),
    EmojiCategory("\uD83D\uDCF1", listOf( // Objects
      "\uD83D\uDCF1", "\uD83D\uDCBB", "\u2328\uFE0F", "\uD83D\uDDA5\uFE0F", "\uD83D\uDDA8\uFE0F", "\uD83D\uDCF7", "\uD83C\uDFA5", "\uD83D\uDCFA",
      "\uD83D\uDCB0", "\uD83D\uDCB3", "\uD83D\uDC8E", "\u2696\uFE0F", "\uD83D\uDEE0\uFE0F", "\u2699\uFE0F", "\uD83D\uDD27", "\uD83D\uDD28",
      "\uD83D\uDCA1", "\uD83D\uDD26", "\uD83D\uDD0B", "\uD83E\uDDF2", "\uD83D\uDCD6", "\uD83D\uDCDD", "\uD83D\uDCCB", "\uD83D\uDCCC",
      "\uD83D\uDCE7", "\uD83D\uDCE8", "\uD83D\uDD12", "\uD83D\uDD11", "\uD83D\uDD0E", "\uD83D\uDD17", "\u2702\uFE0F", "\uD83D\uDDD1\uFE0F",
      "\uD83D\uDCBC", "\uD83D\uDCC1", "\uD83D\uDCC2", "\uD83D\uDCC5", "\uD83D\uDCC6", "\uD83D\uDCC7", "\uD83D\uDCC8", "\uD83D\uDCC9",
      "\uD83D\uDCCA", "\uD83D\uDCE0", "\uD83D\uDCE1", "\uD83D\uDCE2", "\uD83D\uDCE3", "\uD83D\uDCE4", "\uD83D\uDCE5", "\uD83D\uDCE6",
      "\uD83E\uDDF5", "\uD83E\uDDF6", "\uD83E\uDDF7", "\uD83E\uDDF8", "\uD83E\uDDF9", "\uD83E\uDE70", "\uD83E\uDE73", "\uD83E\uDE74",
      "\uD83D\uDDF3", "\uD83D\uDDDD", "\uD83D\uDDDE", "\uD83D\uDDF2", "\uD83D\uDD0A", "\uD83D\uDD09", "\uD83D\uDD08", "\uD83D\uDD07",
      "\uD83E\uDE90", "\uD83E\uDE91", "\uD83E\uDE92", "\uD83E\uDE93", "\uD83E\uDE94", "\uD83E\uDE95", "\uD83D\uDCB8", "\uD83D\uDCB5"
    )),
    EmojiCategory("\uD83D\uDCAF", listOf( // Symbols
      "\u2705", "\u2611\uFE0F", "\u2714\uFE0F", "\u274C", "\u274E", "\u2795", "\u2796", "\u2797",
      "\u27A1\uFE0F", "\u2B05\uFE0F", "\u2B06\uFE0F", "\u2B07\uFE0F", "\u2197\uFE0F", "\u2198\uFE0F", "\u21A9\uFE0F", "\u21AA\uFE0F",
      "\u2139\uFE0F", "\u2757", "\u2753", "\u2755", "\u2754", "\u267B\uFE0F", "\u267E\uFE0F", "\u269C\uFE0F",
      "\uD83D\uDD31", "\uD83D\uDD30", "\u2B55", "\uD83D\uDFE2", "\uD83D\uDD35", "\uD83D\uDFE3", "\uD83D\uDFE1", "\u26AB",
      "\u2716\uFE0F", "\u26A0\uFE0F", "\u26D4", "\uD83D\uDEAB", "\uD83D\uDEAC", "\uD83D\uDEAD", "\uD83D\uDEAE", "\uD83D\uDEAF",
      "\uD83D\uDD34", "\uD83D\uDFE0", "\uD83D\uDFE1", "\u2B1B", "\u2B1C", "\u25FC\uFE0F", "\u25FB\uFE0F", "\u25AA\uFE0F",
      "\uD83D\uDFE5", "\uD83D\uDFE7", "\uD83D\uDFE8", "\uD83D\uDFE9", "\uD83D\uDFE6", "\uD83D\uDFEA", "\uD83D\uDFEB", "\u2B55",
      "\uD83C\uDD92", "\uD83C\uDD93", "\uD83C\uDD95", "\uD83C\uDD97", "\uD83C\uDD98", "\uD83C\uDD99", "\uD83D\uDD3A", "\uD83D\uDD3B",
      "\u2764\uFE0F\u200D\uD83D\uDD25", "\u2764\uFE0F\u200D\uD83E\uDDB5", "\uD83D\uDCA2", "\uD83D\uDCAC", "\uD83D\uDCAD", "\uD83D\uDCAF", "\uD83D\uDD36", "\uD83D\uDD37"
    )),
  )
}
