package com.clipvault.mobile

import android.Manifest
import android.content.ClipData
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
import android.widget.EditText
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
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import android.content.ClipDescription
import java.io.File

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
    GIFS,
    STICKERS,
    TEXT_EDIT,
    SETTINGS
  }

  private var isIncognitoMode = false

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

  // ─── Gboard Parity State ───────────────────────────────────────
  private var keyPreviewPopup: PopupWindow? = null
  private var keyPreviewTextView: TextView? = null
  private var spaceSwipeStartX = 0f
  private var isSpaceSwiping = false

  // ─── Gboard Parity Phase 2 State ───────────────────────────────────────
  private var bigramDictionary = mutableMapOf<String, List<String>>()
  private var isEmojiSearchMode = false
  private var emojiSearchBuffer = ""
  private var isEmojiKitchenMode = false
  private var kitchenEmoji1: String? = null
  private var kitchenEmoji2: String? = null
  private var kitchenWorkbenchRef: LinearLayout? = null
  private var kitchenE1Ref: TextView? = null
  private var kitchenE2Ref: TextView? = null
  private var kitchenSendBtnRef: Button? = null
  private var kitchenHintRef: TextView? = null
  private var kitchenResetBtnRef: TextView? = null

  private fun updateKitchenUi() {
    val wb = kitchenWorkbenchRef ?: return
    if (!isEmojiKitchenMode) {
      wb.visibility = View.GONE
      return
    }
    wb.visibility = View.VISIBLE
    kitchenE1Ref?.text = kitchenEmoji1 ?: "❓"
    kitchenE2Ref?.text = kitchenEmoji2 ?: "❓"

    if (kitchenEmoji1 != null && kitchenEmoji2 != null) {
      val mashup = com.clipvault.mobile.keyboard.EmojiKitchenEngine.getMashup(kitchenEmoji1!!, kitchenEmoji2!!)
      kitchenSendBtnRef?.text = "🚀 ${mashup.title}"
      kitchenSendBtnRef?.visibility = View.VISIBLE
      kitchenHintRef?.visibility = View.GONE
    } else {
      kitchenSendBtnRef?.visibility = View.GONE
      kitchenHintRef?.visibility = View.VISIBLE
      kitchenHintRef?.text = if (kitchenEmoji1 == null) "نیچے سے پہلا ایموجی منتخب کریں" else "دوسرا ایموجی منتخب کریں"
    }

    kitchenResetBtnRef?.visibility = if (kitchenEmoji1 != null || kitchenEmoji2 != null) View.VISIBLE else View.GONE
  }
  private var emojiTags = mapOf(
      "smile" to listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇"),
      "sad" to listOf("🥲", "🥹", "🥺", "😢", "😭", "😞", "😔", "😟", "😕", "🙁"),
      "heart" to listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🤎", "🖤", "🤍", "💔", "❤️‍🔥", "💖", "💗"),
      "fire" to listOf("🔥"),
      "star" to listOf("⭐", "🌟", "✨", "💫", "🌠"),
      "food" to listOf("🍕", "🍔", "🍟", "🌭", "🍿", "🍳"),
      "animal" to listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼"),
      "vehicle" to listOf("🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒"),
      "flag" to listOf("🇵🇰", "🇸🇦", "🇹🇷", "🇦🇪", "🇵🇸", "🇺🇸", "🇬🇧")
  )
  private var lastTapTime = 0L

  // ── Gboard Parity: 10 fixes state variables ────────────────────────────
  private var lastSpaceTapTime = 0L       // Fix 1: double-space → period
  private var lastShiftTapTime = 0L       // Fix 2: double-tap → Caps Lock
  private var recentEmojis = mutableListOf<String>()  // Fix 4: recently used
  private var systemThemeAutoFollow = true // Fix 7: auto system theme
  private var autoCorrectEnabled = true    // Fix 10: toggle autocorrect
  private var doubleSpacePeriodEnabled = true // Fix 1 & 10: toggle double-space period

  // ─── Gboard Parity Phase 3 State ───────────────────────────────────────
  private var isGifSearchMode = false
  private var gifSearchQuery = "trending"
  private var cachedGifs = mutableListOf<String>()

  // ─── Swipe/Glide Typing State ───────────────────────────────────
  private data class SwipePoint(val x: Float, val y: Float, val timestamp: Long)
  private var isSwipeActive = false
  private var swipePath = mutableListOf<SwipePoint>()
  private var swipeStartTime = 0L
  private var swipeMinDistance = 0f
  private var keyHitBoxes = mutableMapOf<String, android.graphics.RectF>()
  private val keyButtons = mutableMapOf<String, View>()
  private var swipeTrailView: SwipeTrailView? = null
  private var lastSwipeKey = ""
  private var swipeKeySequence = mutableListOf<String>()
  private var lastSwipeAlternatives = listOf<String>()
  private var swipeDictionary = listOf<String>()

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
  private var vibrator: android.os.Vibrator? = null
  private var suggestionRequestId = 0L
  private var pendingSuggestionRunnable: Runnable? = null
  private var currentQuickSuggestions = listOf<String>()
  private var indexedSwipeDictionary = mapOf<Char, List<String>>()
  //  Background worker + caches (fixes hang on every keystroke) ──────────────
  private var cachedThemesJson: JSONObject? = null
  private val drawableCache = LruCache<String, Drawable>(100)
  private val backgroundExecutor = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())
  private var cachedLearnedWords: List<String> = emptyList()
  private var lastLearnedWordsRefreshAt = 0L
  private var cachedRecentClips: List<String> = emptyList()
  private var cachedFavoriteClips: List<String> = emptyList()
  private var cachedPersonalDictionary: List<String> = emptyList()
  // ─── Clips panel state ────────────────────────────────────────────────────────
  private var clipSearchQuery: String = ""
  private var selectedClipTab: String = "All"  // All, Fav, Pinned, URL, Phone, Email, Code
  private var clipSearchEditText: android.widget.EditText? = null
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
  private var voiceOverlayRef: View? = null
  private var voiceOverlayTextRef: TextView? = null

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
    com.clipvault.mobile.keyboard.VersionControlManager.enforceMigrations(this)
    loadKeyboardPrefs()
    loadRecentEmojis()
    clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator

    backgroundExecutor.execute {
        val words = mutableListOf<String>()
        val indexed = mutableMapOf<Char, MutableList<String>>()
        fun addWord(raw: String) {
          val w = raw.trim()
          if (w.length in 2..24) {
            words.add(w)
            val ch = w[0].lowercaseChar()
            indexed.getOrPut(ch) { mutableListOf() }.add(w)
          }
        }
        try {
            assets.open("dictionary_en.txt").bufferedReader().useLines { lines ->
                lines.forEach { addWord(it) }
            }
        } catch (e: Exception) {}
        try {
            assets.open("dictionary_ur.txt").bufferedReader().useLines { lines ->
                lines.forEach { addWord(it) }
            }
        } catch (e: Exception) {}
        mainHandler.post {
          swipeDictionary = words
          indexedSwipeDictionary = indexed
        }
        
        try {
            assets.open("bigrams.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(":")
                    if (parts.size == 2) {
                        bigramDictionary[parts[0].trim().lowercase()] = parts[1].split(",").map { it.trim() }
                    }
                }
            }
        } catch (e: Exception) {}
    }

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
  private fun showKeyPreview(view: View, key: String, label: String) {
    if (keyPreviewPopup == null) {
        keyPreviewTextView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 34f
            setTextColor(themeColors().text)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(themeColors().key)
                setStroke(dp(2), themeColors().accent)
            }
            elevation = dp(8).toFloat()
        }
        keyPreviewPopup = PopupWindow(this).apply {
            contentView = keyPreviewTextView
            width = dp(68)
            height = dp(76)
            isTouchable = false
            isClippingEnabled = false
            animationStyle = 0
        }
    }
    
    keyPreviewTextView?.text = label
    keyPreviewTextView?.setTextColor(themeColors().text)
    (keyPreviewTextView?.background as? GradientDrawable)?.apply {
        setColor(themeColors().key)
        setStroke(dp(2), themeColors().accent)
    }
    
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    
    val x = loc[0] + (view.width / 2) - dp(34)
    val y = loc[1] - dp(76) - dp(4)
    
    mainHandler.removeCallbacks(dismissPreviewRunnable)
    if (keyPreviewPopup?.isShowing == true) {
        keyPreviewPopup?.update(x, y, -1, -1)
    } else {
        keyPreviewPopup?.showAtLocation(view, Gravity.NO_GRAVITY, x, y)
    }
  }

  private val dismissPreviewRunnable = Runnable {
    try { keyPreviewPopup?.dismiss() } catch (e: Exception) {}
  }

  private fun dismissKeyPreview() {
    mainHandler.removeCallbacks(dismissPreviewRunnable)
    mainHandler.postDelayed(dismissPreviewRunnable, 45L)
  }

  override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    selectionStart = -1
    selectionEnd = -1
    updateShiftState()
    keyPreviewPopup?.dismiss()
    keyPreviewPopup = null

    if (!restarting) {
      if (panelMode != PanelMode.KEYS || layoutMode == LayoutMode.SYMBOLS) {
        panelMode = PanelMode.KEYS
        layoutMode = lastTextLayoutMode
        refreshKeyboard()
      }
    }

    try {
      if (systemThemeAutoFollow) {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isSystemDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val targetTheme = if (isSystemDark) KeyboardTheme.CLASSIC_DARK else KeyboardTheme.SOFT_IVORY
        if (targetTheme != themeMode) {
          themeMode = targetTheme
          refreshKeyboard()
        }
      } else {
        val themeFile = java.io.File(filesDir, "theme.txt")
        if (themeFile.exists()) {
          val savedTheme = themeFile.readText().trim()
          val newTheme = KeyboardTheme.valueOf(savedTheme)
          if (newTheme != themeMode) {
            themeMode = newTheme
            refreshKeyboard()
          }
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
      updateKeyLabelsForShift()
    } else if (!shouldShift && shiftState == ShiftState.SHIFTED && typedBuffer.isEmpty()) {
      shiftState = ShiftState.OFF
      updateKeyLabelsForShift()
    }
  }

  override fun onFinishInputView(finishingInput: Boolean) {
    stopRepeatingDelete()
    cancelPendingLongPress()
    dismissAlternatePopup()
    kitchenWorkbenchRef = null
    kitchenE1Ref = null
    kitchenE2Ref = null
    kitchenSendBtnRef = null
    kitchenHintRef = null
    kitchenResetBtnRef = null
    super.onFinishInputView(finishingInput)
  }

  private inner class SwipeTrailView(context: Context) : View(context) {
    private val trailPaint = android.graphics.Paint().apply {
        color = themeColors().accent
        strokeWidth = dp(3).toFloat()
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
        alpha = 180
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val trailPath = android.graphics.Path()

    fun updateTrail(points: List<SwipePoint>) {
        trailPath.reset()
        if (points.size < 2) { invalidate(); return }
        
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val offX = loc[0].toFloat()
        val offY = loc[1].toFloat()
        
        trailPath.moveTo(points[0].x - offX, points[0].y - offY)
        for (i in 1 until points.size) {
            trailPath.lineTo(points[i].x - offX, points[i].y - offY)
        }
        invalidate()
    }

    fun clearTrail() { trailPath.reset(); invalidate() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(trailPath, trailPaint)
    }
  }

  override fun onCreateInputView(): View {
    keyHitBoxes.clear()
    keyButtons.clear()
    val colors = themeColors()
    val textRowH = dp((48 * keyboardHeightScale).toInt().coerceIn(38, 60))
    swipeMinDistance = dp(40).toFloat()
    
    val isSymbolsDialpad = isDialpadMode && panelMode == PanelMode.KEYS && layoutMode == LayoutMode.SYMBOLS
    val showSuggestionsAndToolbar = panelMode != PanelMode.EMOJI && !isSymbolsDialpad
    val showBottomRow = !isSymbolsDialpad
    val actualPanelH = if (panelMode == PanelMode.EMOJI) {
      ((textRowH + dp(2)) * 4) + dp(36) + dp(32)
    } else if (isSymbolsDialpad) {
      ((textRowH + dp(2)) * 5) + dp(36) + dp(33)
    } else {
      (textRowH + dp(2)) * 4
    }

    val mainContainer = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(5), dp(4), dp(5), dp(6))
      setBackgroundColor(colors.background)

      if (showSuggestionsAndToolbar) {
        addSuggestionStrip(this, colors)  // row 1: suggestions at top
        addToolbarRow(this, colors)       // row 2: toolbar
      }

      // Fixed-height panel
      addView(LinearLayout(this@ClipVaultKeyboardService).apply {
        orientation = LinearLayout.VERTICAL
        when (panelMode) {
          PanelMode.KEYS -> {
            if (this@ClipVaultKeyboardService.layoutMode == LayoutMode.SYMBOLS) {
              val rowH = if (isSymbolsDialpad) (actualPanelH / 5) else textRowH
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
          PanelMode.TEXT_EDIT -> addTextEditPanel(this, colors)
          PanelMode.SETTINGS -> addSettingsPanel(this, colors)
          else -> {}
        }
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actualPanelH, 1f))

      if (showBottomRow) {
        addBottomRow(this, colors, textRowH)
      }
    }

    val rootLayout = android.widget.RelativeLayout(this)
    mainContainer.id = android.view.View.generateViewId()
    rootLayout.addView(mainContainer, android.widget.RelativeLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    
    swipeTrailView = SwipeTrailView(this).apply {
        isClickable = false
        isFocusable = false
    }
    rootLayout.addView(swipeTrailView, android.widget.RelativeLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
        addRule(android.widget.RelativeLayout.ALIGN_TOP, mainContainer.id)
        addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, mainContainer.id)
    })

    // --- Voice Overlay ---
    val voiceOverlay = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(colors.background)
        visibility = if (isListening) View.VISIBLE else View.GONE
        isClickable = true // Absorb touches
        
        // Voice language indicator pill
        val activeLocaleLabel = when (this@ClipVaultKeyboardService.layoutMode) {
          LayoutMode.UR -> "اردو (پاکستان)"
          LayoutMode.AR -> "العربية"
          else -> "English (US)"
        }
        addView(TextView(this@ClipVaultKeyboardService).apply {
            text = "🎙️ $activeLocaleLabel"
            textSize = 12f
            setTextColor(colors.accent)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(35, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent)))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        })

        voiceOverlayTextRef = TextView(this@ClipVaultKeyboardService).apply {
            text = if (this@ClipVaultKeyboardService.layoutMode == LayoutMode.UR) "سنا جا رہا ہے..." else "Listening..."
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.text)
            setPadding(0, 0, 0, dp(16))
        }
        addView(voiceOverlayTextRef)
        
        val micOverlayBtn = android.widget.ImageView(this@ClipVaultKeyboardService).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(colors.accent, android.graphics.PorterDuff.Mode.SRC_IN)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(45, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent)))
                setStroke(dp(2), colors.accent)
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setOnClickListener {
                toggleVoiceTyping()
            }
        }
        addView(micOverlayBtn, LinearLayout.LayoutParams(dp(72), dp(72)))
        
        addView(TextView(this@ClipVaultKeyboardService).apply {
            text = if (this@ClipVaultKeyboardService.layoutMode == LayoutMode.UR) "روکنے کے لیے دوبارہ ٹیپ کریں" else "Tap mic to stop listening"
            textSize = 12f
            setTextColor(colors.muted)
            setPadding(0, dp(12), 0, 0)
        })
    }
    voiceOverlayRef = voiceOverlay
    
    rootLayout.addView(voiceOverlay, android.widget.RelativeLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
        addRule(android.widget.RelativeLayout.ALIGN_TOP, mainContainer.id)
        addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, mainContainer.id)
    })

    mainContainer.post {
      ensureKeyHitBoxes()
    }

    return rootLayout
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
      listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
      listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"),
      listOf("*", "\"", "'", ":", ";", "!", "?", "\\", "_", "back"),
      listOf("sym_cycle", "~", "`", "|", "\u2022", "\u221A", "\u03C0", "\u00F7", "\u00D7", "sym_cycle")
    )
    val p2 = listOf(
      listOf("~", "\\", "|", "^", "\u00B0", "=", "{", "}", "[", "]"),
      listOf("\u00A3", "\u20AC", "\u00A5", "\u00A2", "\u20B9", "\u20A8", "\u00A9", "\u00AE", "\u2122", "\u2713"),
      listOf("\u00A7", "\u221E", "\u2260", "\u2248", "\u2264", "\u2265", "\u00B1", "\u2039", "\u203A", "back"),
      listOf("sym_cycle", "<", ">", "\u00AB", "\u00BB", "\u00B6", "\u2206", "\u221A", "\u03C0", "sym_cycle")
    )
    return listOf(p1, p2)
  }

  // Toolbar row: switch_system is first and prominently visible
  private fun addToolbarRow(parent: LinearLayout, colors: ThemeColors) {
    val keys = listOf("switch_system", "clips", "mic", "theme", "text_edit", "settings", "incognito", "ai_rewrite", "languages")

    // Wrap in HorizontalScrollView so it doesn't squish too many buttons
    val hsv = HorizontalScrollView(this).apply {
      isHorizontalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_NEVER
      
      val container = LinearLayout(this@ClipVaultKeyboardService).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(2), dp(4), dp(2))
        
        keys.forEach { key ->
          val btn = createKeyButton(labelFor(key), key, colors, isControlKey(key)).apply {
            bindKeyTouch(this, key)
            if (key == "mic") micButtonRef = this  // live pulse reference
          }
          addView(btn, LinearLayout.LayoutParams(dp(45), dp(32)).apply {
            setMargins(dp(2), 0, dp(2), 0)
          })
        }
      }
      addView(container)
    }
    
    parent.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  // Minimal status row (14dp) — hidden entirely when mic is active (mic button IS the indicator)
  private fun addStatusRow(parent: LinearLayout, colors: ThemeColors) {
    // Deprecated by user request, mic button pulse handles indicator now
  }

  private fun addSuggestionStrip(parent: LinearLayout, colors: ThemeColors) {
    suggestionButtons.clear()

    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(4), dp(2), dp(4), dp(3))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.argb(15, 255, 255, 255))
        setStroke(dp(1), Color.argb(40, Color.red(colors.stroke), Color.green(colors.stroke), Color.blue(colors.stroke)))
      }

      repeat(3) { index ->
        if (index > 0) {
          addView(View(this@ClipVaultKeyboardService).apply {
            setBackgroundColor(Color.argb(40, Color.red(colors.muted), Color.green(colors.muted), Color.blue(colors.muted)))
          }, LinearLayout.LayoutParams(dp(1), dp(18)))
        }

        addView(createKeyButton(" ", "", colors, true).apply {
          textSize = if (index == 1) 16.5f else 15f
          if (index == 1) typeface = Typeface.DEFAULT_BOLD
          setOnClickListener {
            val suggestion = tag as? String ?: ""
            if (suggestion == "CANCEL_SEARCH") {
              isSearchingClips = false
              typedBuffer = ""
              updateSuggestionStrip()
            } else if (suggestion.startsWith("PASTE_CLIP:")) {
              val clipText = suggestion.removePrefix("PASTE_CLIP:")
              currentInputConnection?.commitText(clipText, 1)
              updateStatus("📋 Pasted clip")
              updateSuggestionStrip()
            } else if (suggestion.startsWith("SMART_REPLY:")) {
              val replyText = suggestion.removePrefix("SMART_REPLY:")
              currentInputConnection?.commitText("$replyText ", 1)
              updateStatus("💬 Smart Reply sent")
              updateSuggestionStrip()
            } else if (suggestion.isNotBlank()) {
              if (isSearchingClips) isSearchingClips = false
              insertSuggestion(suggestion)
            }
          }
          // Fix 9: Long-press suggestion to add to dictionary
          setOnLongClickListener {
            val suggestion = tag as? String ?: ""
            if (suggestion.isNotBlank() && suggestion != "CANCEL_SEARCH" && !suggestion.startsWith("PASTE_CLIP:")) {
              triggerVibration()
              NativeClipStore.addLearnedWord(this@ClipVaultKeyboardService, suggestion)
              cachedLearnedWords = (cachedLearnedWords + suggestion).distinct()
              updateStatus("✓ Saved: $suggestion")
              true
            } else {
              false
            }
          }
          suggestionButtons.add(this)
        }, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
          setMargins(dp(2), 0, dp(2), 0)
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
      LayoutMode.EN -> listOf("symbols", "lang", ",", "space", ".", "enter")
      LayoutMode.UR, LayoutMode.AR -> listOf("symbols", "lang", "urdu_comma", "space", "urdu_period", "enter")
      LayoutMode.SYMBOLS -> {
        if (isDialpadMode) {
          listOf("abc", "dialpad_toggle", "space", "back", "enter")
        } else {
          listOf("abc", "dialpad_toggle", ",", "space", ".", "enter")
        }
      }
    }
    addKeyRow(parent, keys, colors, height)
  }

  // Emoji panel: Gboard style with continuous vertical scrolling across all categories,
  // real-time scroll synchronization with top category bar, and comprehensive search.
  private fun addEmojiPanel(parent: LinearLayout, colors: ThemeColors) {
    val searchContainer = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    val searchBox = EditText(this).apply {
      hint = if (this@ClipVaultKeyboardService.layoutMode == LayoutMode.UR) "🔍 1,800+ ایموجیز تلاش کریں..." else "🔍 Search 1,800+ Emojis..."
      setHintTextColor(colors.muted)
      setTextColor(colors.text)
      textSize = 13.5f
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(colors.surface)
        setStroke(dp(1), colors.stroke)
      }
      setPadding(dp(10), dp(6), dp(10), dp(6))
      setSingleLine(true)
      isFocusable = true
      isFocusableInTouchMode = true
      setText(emojiSearchBuffer)
      setSelection(emojiSearchBuffer.length)
      addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
          val query = s?.toString() ?: ""
          if (query != emojiSearchBuffer) {
            emojiSearchBuffer = query
            isEmojiSearchMode = query.isNotBlank()
            refreshKeyboard()
          }
        }
      })
    }
    searchContainer.addView(searchBox, LinearLayout.LayoutParams(0, dp(36), 1f))

    if (emojiSearchBuffer.isNotEmpty()) {
      val clearBtn = TextView(this).apply {
        text = "✕"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(colors.muted)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener {
          triggerVibration()
          emojiSearchBuffer = ""
          isEmojiSearchMode = false
          refreshKeyboard()
        }
      }
      searchContainer.addView(clearBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
    }

    val kitchenToggleBtn = Button(this).apply {
      text = if (isEmojiKitchenMode) "🍳 Kitchen ON" else "🍳 Kitchen"
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(if (isEmojiKitchenMode) Color.WHITE else colors.accent)
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(if (isEmojiKitchenMode) colors.accent else Color.argb(35, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent)))
        setStroke(dp(1), colors.accent)
      }
      setPadding(dp(8), dp(4), dp(8), dp(4))
      setOnClickListener {
        triggerVibration()
        isEmojiKitchenMode = !isEmojiKitchenMode
        if (!isEmojiKitchenMode) {
          kitchenEmoji1 = null
          kitchenEmoji2 = null
        }
        text = if (isEmojiKitchenMode) "🍳 Kitchen ON" else "🍳 Kitchen"
        setTextColor(if (isEmojiKitchenMode) Color.WHITE else colors.accent)
        background = GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = dp(8).toFloat()
          setColor(if (isEmojiKitchenMode) colors.accent else Color.argb(35, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent)))
          setStroke(dp(1), colors.accent)
        }
        updateKitchenUi()
      }
    }
    searchContainer.addView(kitchenToggleBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
      setMargins(dp(4), 0, 0, 0)
    })

    parent.addView(searchContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val kitchenWorkbench = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(8), dp(4), dp(8), dp(4))
      visibility = if (isEmojiKitchenMode) View.VISIBLE else View.GONE
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(Color.argb(40, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent)))
        setStroke(dp(1), colors.accent)
      }
    }
    kitchenWorkbenchRef = kitchenWorkbench

    val e1Text = TextView(this).apply {
      text = kitchenEmoji1 ?: "❓"
      textSize = 22f
      gravity = Gravity.CENTER
      setPadding(dp(6), dp(2), dp(6), dp(2))
    }
    kitchenE1Ref = e1Text
    kitchenWorkbench.addView(e1Text)

    kitchenWorkbench.addView(TextView(this).apply {
      text = "➕"
      textSize = 14f
      gravity = Gravity.CENTER
      setTextColor(colors.muted)
      setPadding(dp(2), 0, dp(2), 0)
    })

    val e2Text = TextView(this).apply {
      text = kitchenEmoji2 ?: "❓"
      textSize = 22f
      gravity = Gravity.CENTER
      setPadding(dp(6), dp(2), dp(6), dp(2))
    }
    kitchenE2Ref = e2Text
    kitchenWorkbench.addView(e2Text)

    kitchenWorkbench.addView(TextView(this).apply {
      text = "➔"
      textSize = 14f
      gravity = Gravity.CENTER
      setTextColor(colors.accent)
      setPadding(dp(2), 0, dp(2), 0)
    })

    val hintView = TextView(this).apply {
      text = if (kitchenEmoji1 == null) "نیچے سے پہلا ایموجی منتخب کریں" else "دوسرا ایموجی منتخب کریں"
      textSize = 12f
      setTextColor(colors.muted)
      gravity = Gravity.CENTER_VERTICAL
      visibility = if (kitchenEmoji1 != null && kitchenEmoji2 != null) View.GONE else View.VISIBLE
    }
    kitchenHintRef = hintView
    kitchenWorkbench.addView(hintView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
      setMargins(dp(6), 0, dp(6), 0)
    })

    val sendBtn = Button(this).apply {
      text = if (kitchenEmoji1 != null && kitchenEmoji2 != null) {
        "🚀 " + com.clipvault.mobile.keyboard.EmojiKitchenEngine.getMashup(kitchenEmoji1!!, kitchenEmoji2!!).title
      } else "🚀 Send"
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setColor(colors.accent)
      }
      setPadding(dp(8), dp(4), dp(8), dp(4))
      visibility = if (kitchenEmoji1 != null && kitchenEmoji2 != null) View.VISIBLE else View.GONE
      setOnClickListener {
        triggerVibration()
        if (kitchenEmoji1 != null && kitchenEmoji2 != null) {
          sendEmojiKitchenSticker(kitchenEmoji1!!, kitchenEmoji2!!)
        }
      }
    }
    kitchenSendBtnRef = sendBtn
    kitchenWorkbench.addView(sendBtn, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
      setMargins(dp(4), 0, dp(4), 0)
    })

    val resetBtn = TextView(this).apply {
      text = "✕"
      textSize = 14f
      gravity = Gravity.CENTER
      setTextColor(colors.muted)
      setPadding(dp(8), dp(4), dp(8), dp(4))
      visibility = if (kitchenEmoji1 != null || kitchenEmoji2 != null) View.VISIBLE else View.GONE
      setOnClickListener {
        triggerVibration()
        kitchenEmoji1 = null
        kitchenEmoji2 = null
        updateKitchenUi()
      }
    }
    kitchenResetBtnRef = resetBtn
    kitchenWorkbench.addView(resetBtn)

    parent.addView(kitchenWorkbench, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      setMargins(dp(4), dp(2), dp(4), dp(4))
    })

    // Search results view
    if (isEmojiSearchMode && emojiSearchBuffer.isNotEmpty()) {
      val foundEmojis = com.clipvault.mobile.keyboard.EmojiCatalog.search(emojiSearchBuffer)
      val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), 0, dp(2), dp(4))
        if (foundEmojis.isEmpty()) {
          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = "No emojis found for '$emojiSearchBuffer'"
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextColor(colors.muted)
          })
        } else {
          foundEmojis.take(120).chunked(8).forEach { row -> addEmojiRow(this, row, colors, dp(40)) }
        }
      }
      parent.addView(ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content)
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      return
    }

    val cats = com.clipvault.mobile.keyboard.EmojiCatalog.allCategories(recentEmojis)
    val categoryHeaders = mutableListOf<View>()
    val categoryTabButtons = mutableListOf<Button>()

    var activeCatIndex = emojiCategoryIndex.coerceIn(0, cats.size - 1)

    val categoryBar = HorizontalScrollView(this).apply {
      isHorizontalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_NEVER
    }

    val emojiScrollView = ScrollView(this).apply {
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    // 1. Top Category Horizontal Bar
    val tabsLayout = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    // 2. Continuous Vertical Scrolling Grid with Instant Progressive Category Loading
    val allContent = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(2), 0, dp(2), dp(10))
    }

    var loadedCategoryCount = 0

    fun appendCategoryAt(idx: Int) {
      if (idx < 0 || idx >= cats.size) return
      val cat = cats[idx]
      val headerView = LinearLayout(this@ClipVaultKeyboardService).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(4))

        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = "${cat.icon}  ${cat.title}"
          textSize = 13f
          typeface = Typeface.DEFAULT_BOLD
          setTextColor(colors.accent)
        })
      }
      categoryHeaders.add(headerView)
      allContent.addView(headerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

      cat.emojis.chunked(8).forEach { row ->
        addEmojiRow(allContent, row, colors, dp(40))
      }
    }

    // Render the initial category immediately for sub-millisecond response (< 5ms)
    appendCategoryAt(0)
    loadedCategoryCount = 1

    cats.forEachIndexed { idx, cat ->
      val isSelected = idx == activeCatIndex
      val btn = Button(this@ClipVaultKeyboardService).apply {
        text = cat.icon
        textSize = 14f
        setPadding(dp(4), dp(1), dp(4), dp(1))
        setBackgroundColor(if (isSelected) Color.argb(60, 255, 255, 255) else Color.TRANSPARENT)
        setOnClickListener {
          triggerVibration()
          activeCatIndex = idx
          emojiCategoryIndex = idx
          categoryTabButtons.forEachIndexed { bIdx, b ->
            b.setBackgroundColor(if (bIdx == idx) Color.argb(60, 255, 255, 255) else Color.TRANSPARENT)
          }
          while (loadedCategoryCount <= idx && loadedCategoryCount < cats.size) {
            appendCategoryAt(loadedCategoryCount)
            loadedCategoryCount++
          }
          if (idx < categoryHeaders.size) {
            val targetY = categoryHeaders[idx].top
            emojiScrollView.smoothScrollTo(0, targetY)
          }
        }
      }
      categoryTabButtons.add(btn)
      tabsLayout.addView(btn, LinearLayout.LayoutParams(dp(34), dp(32)).apply {
        setMargins(dp(2), 0, dp(2), 0)
      })
    }
    categoryBar.addView(tabsLayout)
    parent.addView(categoryBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))

    emojiScrollView.addView(allContent)

    // Incrementally load the remaining categories on next animation frame
    fun scheduleRemainingCategories() {
      if (loadedCategoryCount < cats.size && panelMode == PanelMode.EMOJI) {
        mainHandler.post {
          if (loadedCategoryCount < cats.size && panelMode == PanelMode.EMOJI) {
            appendCategoryAt(loadedCategoryCount)
            loadedCategoryCount++
            scheduleRemainingCategories()
          }
        }
      }
    }
    scheduleRemainingCategories()

    // Real-time bidirectional scroll tracking
    emojiScrollView.viewTreeObserver.addOnScrollChangedListener {
      val scrollY = emojiScrollView.scrollY
      if (loadedCategoryCount < cats.size) {
        val contentH = allContent.height
        if (contentH > 0 && scrollY + emojiScrollView.height >= contentH - dp(350)) {
          appendCategoryAt(loadedCategoryCount)
          loadedCategoryCount++
        }
      }
      var newActiveIdx = 0
      for (i in categoryHeaders.indices) {
        if (categoryHeaders[i].top <= scrollY + dp(30)) {
          newActiveIdx = i
        }
      }
      if (newActiveIdx != activeCatIndex && newActiveIdx in categoryTabButtons.indices) {
        activeCatIndex = newActiveIdx
        emojiCategoryIndex = newActiveIdx
        categoryTabButtons.forEachIndexed { bIdx, b ->
          b.setBackgroundColor(if (bIdx == newActiveIdx) Color.argb(60, 255, 255, 255) else Color.TRANSPARENT)
        }
        val targetButton = categoryTabButtons[newActiveIdx]
        categoryBar.smoothScrollTo(targetButton.left - dp(40), 0)
      }
    }

    parent.addView(emojiScrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  private fun addEmojiRow(parent: LinearLayout, emojis: List<String>, colors: ThemeColors, height: Int) {
    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(0, dp(4), 0, dp(4))

      emojis.forEach { emoji ->
        val btn = createKeyButton(emoji, emoji, colors, false)

        // Fix 4: record recently used on tap or Kitchen selection
        btn.setOnClickListener {
          triggerVibration()
          if (isEmojiKitchenMode) {
            if (kitchenEmoji1 == null) {
              kitchenEmoji1 = emoji
            } else if (kitchenEmoji2 == null) {
              kitchenEmoji2 = emoji
            } else {
              kitchenEmoji1 = kitchenEmoji2
              kitchenEmoji2 = emoji
            }
            updateKitchenUi()
          } else {
            currentInputConnection?.commitText(emoji, 1)
            typedBuffer = ""
            recordRecentEmoji(emoji)
          }
        }

        // Fix 8: skin tone long-press for supported emojis
        val skinTones = skinTonesFor(emoji)
        if (skinTones.isNotEmpty()) {
          btn.setOnLongClickListener {
            triggerVibration()
            showAlternatesPopup(btn, skinTones)
            true
          }
        } else {
          btn.setOnLongClickListener(null)
        }

        addView(btn, LinearLayout.LayoutParams(0, height, 1f).apply {
          setMargins(dp(4), 0, dp(4), 0)
        })
      }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  /** Returns skin-tone variants for supported emojis (fitzpatrick modifiers U+1F3FB–U+1F3FF) */
  private fun skinTonesFor(emoji: String): List<String> {
    val skinnable = setOf(
      "👋","🤚","🖐","✋","🖖","👌","🤌","🤏","✌","🤞","🤟","🤘","🤙","👈","👉","👆","🖕","👇",
      "☝","👍","👎","✊","👊","🤛","🤜","👏","🙌","🤲","🤝","🙏","✍","💅","🤳","💪","🦵","🦶",
      "👂","🦻","👃","👶","🧒","👦","👧","🧑","👱","👨","🧔","👩","🧓","👴","👵","🫅"
    )
    if (emoji !in skinnable) return emptyList()
    val modifiers = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")
    return modifiers.map { "$emoji$it" }
  }

  /** Save emoji to recently-used list in SharedPrefs (max 24). */
  private fun recordRecentEmoji(emoji: String) {
    recentEmojis.remove(emoji)
    recentEmojis.add(0, emoji)
    if (recentEmojis.size > 24) recentEmojis = recentEmojis.take(24).toMutableList()
    try {
      val prefs = getSharedPreferences("clipvault_keyboard", Context.MODE_PRIVATE)
      prefs.edit().putString("recentEmojis", recentEmojis.joinToString("\u001F")).apply()
    } catch (_: Exception) {}
  }

  /** Load recently-used emojis from SharedPrefs. */
  private fun loadRecentEmojis() {
    try {
      val raw = getSharedPreferences("clipvault_keyboard", Context.MODE_PRIVATE)
        .getString("recentEmojis", "") ?: ""
      recentEmojis = if (raw.isBlank()) mutableListOf()
      else raw.split("\u001F").filter { it.isNotBlank() }.toMutableList()
    } catch (_: Exception) {}
  }

  /**
   * Generates and transmits high-res Emoji Kitchen mashup sticker to active input application.
   */
  private fun sendEmojiKitchenSticker(emoji1: String, emoji2: String) {
    updateStatus("🍳 Making Kitchen Sticker...")
    backgroundExecutor.execute {
      try {
        val file = com.clipvault.mobile.keyboard.EmojiKitchenEngine.createCompositeStickerFile(
          this@ClipVaultKeyboardService, emoji1, emoji2
        )
        mainHandler.post {
          sendStickerFile(file, "$emoji1 + $emoji2 Sticker", "$emoji1$emoji2")
        }
      } catch (e: Exception) {
        mainHandler.post {
          currentInputConnection?.commitText("$emoji1$emoji2", 1)
          updateStatus("Inserted $emoji1$emoji2")
        }
      }
    }
  }

  private fun sendStickerFile(file: File, descriptionText: String, fallbackText: String) {
    val ic = currentInputConnection ?: return
    val ei = currentInputEditorInfo ?: return
    try {
      val contentUri = FileProvider.getUriForFile(
        this, "${packageName}.fileprovider", file
      )

      // Universal clipboard copy: enables pasting sticker into all apps even if direct commitContent is unsupported
      try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newUri(contentResolver, descriptionText, contentUri)
        grantUriPermission("com.clipvault.mobile", contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        cm?.setPrimaryClip(clip)
      } catch (_: Exception) {}

      val description = ClipDescription(descriptionText, arrayOf("image/png"))
      val contentInfo = InputContentInfoCompat(
        contentUri, description, null
      )
      var flags = 0
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        flags = flags or InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
      }
      val committed = InputConnectionCompat.commitContent(
        ic, ei, contentInfo, flags, null
      )
      if (committed) {
        updateStatus("✓ Sticker Sent!")
        kitchenEmoji1?.let { recordRecentEmoji(it) }
        kitchenEmoji2?.let { recordRecentEmoji(it) }
      } else {
        ic.commitText(fallbackText, 1)
        updateStatus("✓ Sticker in clipboard! ($fallbackText)")
      }
    } catch (e: Exception) {
      ic.commitText(fallbackText, 1)
      updateStatus("✓ Sticker created ($fallbackText)")
    }
  }

  // ─── Gboard Parity Phase 3: GIFs ───────────────────────────────────────
  private fun fetchGifs(query: String) {
    backgroundExecutor.execute {
        try {
            val urlString = if (query.isEmpty() || query == "trending") {
                "https://api.tenor.com/v1/trending?key=LIVDSRZULELA&limit=20"
            } else {
                "https://api.tenor.com/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&key=LIVDSRZULELA&limit=20"
            }
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            
            val urls = mutableListOf<String>()
            val regex = Regex(""""url":"([^"]+\.gif)"""")
            val matches = regex.findAll(response)
            for (match in matches) {
                val gifUrl = match.groupValues[1]
                if (!urls.contains(gifUrl)) {
                    urls.add(gifUrl)
                }
            }
            cachedGifs = urls.take(20).toMutableList()
            mainHandler.post { refreshKeyboard() }
        } catch (e: Exception) {}
    }
  }

  private fun sendGifToApp(gifUrl: String) {
    backgroundExecutor.execute {
        try {
            val gifDir = java.io.File(cacheDir, "gifs")
            if (!gifDir.exists()) gifDir.mkdirs()
            val file = java.io.File(gifDir, "shared_gif_${System.currentTimeMillis()}.gif")
            
            java.net.URL(gifUrl).openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val clipDescription = android.content.ClipDescription("GIF", arrayOf("image/gif"))
            
            val info = androidx.core.view.inputmethod.InputContentInfoCompat(uri, clipDescription, null)
            val ic = currentInputConnection
            if (ic != null && currentInputEditorInfo != null) {
                androidx.core.view.inputmethod.InputConnectionCompat.commitContent(
                    ic, currentInputEditorInfo, info, 
                    androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
                )
            }
            mainHandler.post { updateStatus("\u2713 GIF Sent") }
        } catch (e: Exception) {
            mainHandler.post { updateStatus("\u274C Failed to send") }
        }
    }
  }

  private fun addGifPanel(parent: LinearLayout, colors: ThemeColors) {
    val searchBar = TextView(this).apply {
        text = if (isGifSearchMode) "Search: $gifSearchQuery" + "_" else "🔍 Search GIFs..."
        setBackgroundColor(Color.parseColor("#22000000"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextColor(colors.text)
        textSize = 16f
        setOnClickListener {
            triggerVibration()
            isGifSearchMode = !isGifSearchMode
            if (isGifSearchMode) gifSearchQuery = "" else {
                gifSearchQuery = "trending"
                fetchGifs(gifSearchQuery)
            }
            refreshKeyboard()
        }
    }
    parent.addView(searchBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(2), dp(4))

        if (cachedGifs.isEmpty()) {
            addView(TextView(this@ClipVaultKeyboardService).apply {
                text = "Loading GIFs..."
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setTextColor(colors.muted)
            })
            if (!isGifSearchMode) fetchGifs(gifSearchQuery)
        } else {
            cachedGifs.chunked(3).forEach { rowUrls ->
                addView(LinearLayout(this@ClipVaultKeyboardService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(4))

                    rowUrls.forEach { url ->
                        addView(Button(this@ClipVaultKeyboardService).apply {
                            text = "GIF"
                            textSize = 14f
                            setTextColor(colors.text)
                            setBackgroundColor(Color.parseColor("#33000000"))
                            setOnClickListener {
                                triggerVibration()
                                sendGifToApp(url)
                            }
                        }, LinearLayout.LayoutParams(0, dp(80), 1f).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        })
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    parent.addView(ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
    val isUrdu = layoutMode == LayoutMode.UR || layoutMode == LayoutMode.AR
    val lSearch = if (isUrdu) "\uD83D\uDD0D تلاش کریں..." else "\uD83D\uDD0D Search clips..."
    val lPinned = if (isUrdu) "\uD83D\uDCCC پن کردہ" else "\uD83D\uDCCC Pinned"
    val lFav    = if (isUrdu) "\u2B50 پسندیدہ" else "\u2B50 Favorites"
    val lRecent = if (isUrdu) "\uD83D\uDD51 حالیہ" else "\uD83D\uDD51 Recent"
    val lEmpty  = if (isUrdu) "ابھی تک کوئی کلپ نہیں" else "No clips yet"

    // ─── Tab definitions ─────────────────────────────────────────────────────
    data class Tab(val id: String, val label: String)
    val tabs = listOf(
      Tab("All",    if (isUrdu) "سب" else "All"),
      Tab("Fav",    "\u2B50"),
      Tab("Pinned", "\uD83D\uDCCC"),
      Tab("Template","\uD83D\uDCCB"),
      Tab("URL",    "\uD83D\uDD17"),
      Tab("Phone",  "\uD83D\uDCDE"),
      Tab("Email",  "\u2709"),
      Tab("Code",   "\uD83D\uDCBB"),
    )

    // ─── Helper: compute clips for current tab + search ──────────────────────
    fun resolveClips(): List<String> {
      val q = clipSearchQuery.trim()
      return when (selectedClipTab) {
        "Fav"    -> NativeClipStore.searchByCategory(this, q, "",      favoritesOnly = true,  limit = 100)
        "Pinned" -> NativeClipStore.getKeyboardPins(this).let { pins ->
          if (q.isBlank()) pins else pins.filter { it.contains(q, ignoreCase = true) }
        }
        "URL", "Phone", "Email", "Code", "Template" ->
          NativeClipStore.searchByCategory(this, q, selectedClipTab, favoritesOnly = false, limit = 100)
        else /* All */ -> if (q.isBlank()) cachedRecentClips else NativeClipStore.search(this, q, 100)
      }
    }

    // ─── Outer vertical layout ────────────────────────────────────────────────
    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(4), dp(4), dp(4), dp(2))

      // ── Row 1: Inline search field ────────────────────────────────────────
      val searchBox = android.widget.EditText(this@ClipVaultKeyboardService).apply {
        hint = lSearch
        setHintTextColor(colors.muted)
        setTextColor(colors.text)
        textSize = 13f
        background = GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = dp(8).toFloat()
          setColor(colors.surface)
          setStroke(dp(1), colors.stroke)
        }
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setSingleLine(true)
        isFocusable = true
        isFocusableInTouchMode = true
        // Pre-populate with current search query (preserve across refreshes)
        setText(clipSearchQuery)
        setSelection(clipSearchQuery.length)
        addTextChangedListener(object : android.text.TextWatcher {
          override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
          override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
          override fun afterTextChanged(s: android.text.Editable?) {
            clipSearchQuery = s?.toString() ?: ""
            refreshKeyboard()
          }
        })
      }
      clipSearchEditText = searchBox
      addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
        setMargins(dp(2), dp(2), dp(2), dp(4))
      })

      // ── Row 2: Category tabs ─────────────────────────────────────────────
      val tabBar = HorizontalScrollView(this@ClipVaultKeyboardService).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        val tabRow = LinearLayout(this@ClipVaultKeyboardService).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(2), 0, dp(2), 0)
          tabs.forEach { tab ->
            val isActive = tab.id == selectedClipTab
            val btn = TextView(this@ClipVaultKeyboardService).apply {
              text = tab.label
              textSize = 12f
              gravity = Gravity.CENTER
              setPadding(dp(10), dp(4), dp(10), dp(4))
              setTextColor(if (isActive) colors.background else colors.accent)
              background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (isActive) colors.accent else android.graphics.Color.TRANSPARENT)
                if (!isActive) setStroke(dp(1), colors.stroke)
              }
              setOnClickListener {
                if (hapticsEnabled) triggerVibration()
                selectedClipTab = tab.id
                clipSearchQuery = ""
                refreshKeyboard()
              }
            }
            addView(btn, LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)
            ).apply { setMargins(dp(2), 0, dp(2), 0) })
          }
        }
        addView(tabRow)
      }
      addView(tabBar, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(34)
      ).apply { setMargins(0, 0, 0, dp(4)) })

      // ── Row 3: Clips list (scrollable) ───────────────────────────────────
      val clipsToShow = resolveClips()
      val pinnedInAll = if (selectedClipTab == "All" && clipSearchQuery.isBlank())
        NativeClipStore.getKeyboardPins(this@ClipVaultKeyboardService) else emptyList()
      val favoritesInAll = if (selectedClipTab == "All" && clipSearchQuery.isBlank())
        cachedFavoriteClips else emptyList()

      val clipContent = LinearLayout(this@ClipVaultKeyboardService).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(4))

        if (clipsToShow.isEmpty() && pinnedInAll.isEmpty() && favoritesInAll.isEmpty()) {
          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = lEmpty
            setTextColor(colors.muted)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
          }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
          return@apply
        }

        // Pinned section (only in "All" tab without search)
        if (pinnedInAll.isNotEmpty()) {
          addSectionLabel(this, lPinned, colors)
          pinnedInAll.forEach { clip ->
            addClipRow(this, clip, colors, isPinned = true, isFavorite = favoritesInAll.contains(clip))
          }
        }

        // Favorites section (only in "All" tab without search, exclude already pinned)
        val favNotPinned = favoritesInAll.filter { !pinnedInAll.contains(it) }
        if (favNotPinned.isNotEmpty()) {
          addSectionLabel(this, lFav, colors)
          favNotPinned.forEach { clip ->
            addClipRow(this, clip, colors, isPinned = false, isFavorite = true)
          }
        }

        // Recent / filtered clips (exclude already shown)
        val alreadyShown = (pinnedInAll + favoritesInAll).toSet()
        val filtered = if (selectedClipTab == "All" && clipSearchQuery.isBlank())
          clipsToShow.filter { !alreadyShown.contains(it) } else clipsToShow

        if (filtered.isNotEmpty()) {
          if (selectedClipTab == "All" && clipSearchQuery.isBlank() && alreadyShown.isNotEmpty()) {
            addSectionLabel(this, lRecent, colors)
          }
          filtered.forEach { clip ->
            addClipRow(this, clip, colors, isPinned = false,
              isFavorite = cachedFavoriteClips.contains(clip))
          }
        }
      }

      addView(ScrollView(this@ClipVaultKeyboardService).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(clipContent)
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    parent.addView(container, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
  }

  // ─── Section label helper ─────────────────────────────────────────────────────
  private fun addSectionLabel(parent: LinearLayout, text: String, colors: ThemeColors) {
    parent.addView(TextView(this).apply {
      this.text = text
      textSize = 11f
      typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
      setTextColor(colors.accent)
      setPadding(dp(4), dp(4), dp(4), dp(2))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  // ─── Long press clip action menu (PopupWindow) ───────────────────────────────
  private fun showClipActionMenu(anchor: View, clip: String, colors: ThemeColors, isPinned: Boolean, isFavorite: Boolean) {
    dismissAlternatePopup()
    val isUrdu = layoutMode == LayoutMode.UR || layoutMode == LayoutMode.AR

    data class Action(val label: String, val color: Int, val action: () -> Unit)
    val actions = listOf(
      Action(if (isUrdu) "\uD83D\uDCCB کاپی" else "\uD83D\uDCCB Copy",
        colors.accent) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", clip))
        updateStatus(if (isUrdu) "✓ کاپی" else "✓ copied")
      },
      Action(if (isUrdu) "\u2B50 ${if (isFavorite) "ہٹائیں" else "پسندیدہ"}" else "\u2B50 ${if (isFavorite) "Unfav" else "Fav"}",
        if (isFavorite) android.graphics.Color.rgb(251, 191, 36) else colors.muted) {
        backgroundExecutor.execute {
          NativeClipStore.toggleFavoriteByText(this@ClipVaultKeyboardService, clip)
          mainHandler.post {
            cachedFavoriteClips = NativeClipStore.favorites(this@ClipVaultKeyboardService, 15)
            updateStatus(if (isFavorite) "\u2B50 removed" else "\u2B50 favorited")
            refreshKeyboard()
          }
        }
      },
      Action(if (isPinned) (if (isUrdu) "\uD83D\uDCCB ان پن" else "\uD83D\uDCCB Unpin")
             else (if (isUrdu) "\uD83D\uDCCC پن" else "\uD83D\uDCCC Pin"),
        colors.accent) {
        val nowPinned = NativeClipStore.toggleKeyboardPin(this@ClipVaultKeyboardService, clip)
        updateStatus(if (nowPinned) "\uD83D\uDCCC pinned" else "unpinned")
        refreshCachedDataAsync()
        refreshKeyboard()
      },
      Action(if (isUrdu) "\uD83D\uDE80 پیسٹ" else "\uD83D\uDE80 Paste",
        android.graphics.Color.rgb(99, 215, 125)) {
        currentInputConnection?.commitText(clip, 1)
        typedBuffer = ""
        panelMode = PanelMode.KEYS
        clipSearchQuery = ""
        updateStatus(if (isUrdu) "✓ چسپاں" else "✓ pasted")
        refreshKeyboard()
      },
    )

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(dp(6), dp(6), dp(6), dp(6))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(colors.surface)
        setStroke(dp(1), colors.stroke)
      }
      elevation = dp(8).toFloat()

      actions.forEach { act ->
        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = act.label
          textSize = 12f
          gravity = Gravity.CENTER
          setTextColor(act.color)
          setPadding(dp(10), dp(8), dp(10), dp(8))
          background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(android.graphics.Color.TRANSPARENT)
          }
          setOnClickListener {
            alternatePopup?.dismiss()
            alternatePopup = null
            act.action()
          }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
          setMargins(dp(2), 0, dp(2), 0)
        })
      }
    }

    alternatePopup = PopupWindow(
      container,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      false,
    ).apply {
      isOutsideTouchable = true
      isClippingEnabled = true
      elevation = dp(10).toFloat()
      showAsDropDown(anchor, 0, -dp(56))
    }
  }

  private fun addClipRow(
    parent: LinearLayout,
    clip: String,
    colors: ThemeColors,
    isPinned: Boolean,
    isFavorite: Boolean = false,
  ) {
    val PREVIEW_LIMIT = 120
    val displayText = clip.trim()
    val isLong = displayText.length > PREVIEW_LIMIT
    val previewText = if (isLong) "${displayText.take(PREVIEW_LIMIT)}..." else displayText
    val isRtl = containsRtlText("", displayText)
    val tf = if (isRtl) rtlTypeface ?: buildRtlTypeface().also { rtlTypeface = it }
             else android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)

    val rowBg = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(8).toFloat()
      setColor(if (isPinned) colors.specialKey else colors.key)
      setStroke(dp(1), if (isFavorite) android.graphics.Color.argb(120, 251, 191, 36) else colors.stroke)
    }

    parent.addView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      background = rowBg
      setPadding(dp(10), dp(7), dp(8), dp(7))

      // ── Top: clip text ────────────────────────────────────────────────────
      val textView = TextView(this@ClipVaultKeyboardService).apply {
        text = previewText
        textSize = 13f
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(colors.text)
        typeface = tf
        textDirection = if (isRtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
      }

      // Long-press triggers action menu; tap pastes immediately
      val longPressRunnable: Runnable = Runnable {
        if (hapticsEnabled) triggerVibration()
        showClipActionMenu(textView, clip, colors, isPinned, isFavorite)
      }
      textView.setOnTouchListener { v, event ->
        when (event.action) {
          MotionEvent.ACTION_DOWN -> {
            repeatHandler.postDelayed(longPressRunnable, 400L)
            false
          }
          MotionEvent.ACTION_UP -> {
            repeatHandler.removeCallbacks(longPressRunnable)
            false
          }
          MotionEvent.ACTION_CANCEL -> {
            repeatHandler.removeCallbacks(longPressRunnable)
            false
          }
          else -> false
        }
      }
      textView.setOnClickListener {
        repeatHandler.removeCallbacks(longPressRunnable)
        currentInputConnection?.commitText(clip, 1)
        typedBuffer = ""
        panelMode = PanelMode.KEYS
        clipSearchQuery = ""
        selectedClipTab = "All"
        updateStatus(if (this@ClipVaultKeyboardService.layoutMode == LayoutMode.UR || this@ClipVaultKeyboardService.layoutMode == LayoutMode.AR) "✓ چسپاں" else "✓ pasted")
        refreshKeyboard()
      }
      addView(textView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

      // ── Bottom: meta row (favorite star | expand | pin badge) ────────────
      val metaRow = LinearLayout(this@ClipVaultKeyboardService).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, 0)

        // Favorite toggle
        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = if (isFavorite) "\u2B50" else "\u2606"
          textSize = 14f
          setTextColor(if (isFavorite) android.graphics.Color.rgb(251, 191, 36) else colors.muted)
          setPadding(0, 0, dp(8), 0)
          setOnClickListener {
            backgroundExecutor.execute {
              NativeClipStore.toggleFavoriteByText(this@ClipVaultKeyboardService, clip)
              mainHandler.post {
                cachedFavoriteClips = NativeClipStore.favorites(this@ClipVaultKeyboardService, 15)
                refreshKeyboard()
              }
            }
          }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))

        // Pin badge
        if (isPinned) {
          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = "\uD83D\uDCCC"
            textSize = 12f
            setPadding(0, 0, dp(8), 0)
            setTextColor(colors.accent)
          }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        }

        // Smart Actions
        val cleanClip = clip.trim()
        val isUrl = android.util.Patterns.WEB_URL.matcher(cleanClip).matches() || cleanClip.startsWith("http://") || cleanClip.startsWith("https://")
        val isPhone = cleanClip.length in 7..15 && android.util.Patterns.PHONE.matcher(cleanClip).matches() && cleanClip.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
        
        if (isUrl) {
          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = "↗ Open"
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
              shape = GradientDrawable.RECTANGLE
              cornerRadius = dp(4).toFloat()
              setColor(android.graphics.Color.rgb(59, 130, 246)) // Blue
            }
            setOnClickListener {
              try {
                val url = if (!cleanClip.startsWith("http")) "https://$cleanClip" else cleanClip
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
              } catch (e: Exception) { }
            }
          }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) })
        } else if (isPhone) {
          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = "📞 Call"
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
              shape = GradientDrawable.RECTANGLE
              cornerRadius = dp(4).toFloat()
              setColor(android.graphics.Color.rgb(16, 185, 129)) // Green
            }
            setOnClickListener {
              try {
                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$cleanClip")).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
              } catch (e: Exception) { }
            }
          }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) })
        }

        // Spacer
        addView(View(this@ClipVaultKeyboardService),
          LinearLayout.LayoutParams(0, 1, 1f))

        // "▼ More" expand button for long text
        if (isLong) {
          addView(TextView(this@ClipVaultKeyboardService).apply {
            text = if (this@ClipVaultKeyboardService.layoutMode == LayoutMode.UR || this@ClipVaultKeyboardService.layoutMode == LayoutMode.AR) "▼ مزید" else "▼ More"
            textSize = 11f
            setTextColor(colors.muted)
            setPadding(dp(4), 0, 0, 0)
            setOnClickListener { showFullTextPopup(clip, colors) }
          }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        }

        // Pin toggle button
        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = if (isPinned) "\uD83D\uDCCB" else "\uD83D\uDCCC"
          textSize = 14f
          gravity = Gravity.CENTER
          setPadding(dp(8), 0, 0, 0)
          setTextColor(colors.muted)
          setOnClickListener {
            val nowPinned = NativeClipStore.toggleKeyboardPin(this@ClipVaultKeyboardService, clip)
            updateStatus(if (nowPinned) "\uD83D\uDCCC pinned" else "unpinned")
            refreshCachedDataAsync()
            refreshKeyboard()
          }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
      }
      addView(metaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      setMargins(0, dp(2), 0, dp(2))
    })
  }

  // ─── Full text preview popup (for long clips) ─────────────────────────────────
  private fun showFullTextPopup(clip: String, colors: ThemeColors) {
    dismissAlternatePopup()
    val isUrdu = layoutMode == LayoutMode.UR || layoutMode == LayoutMode.AR
    val isRtl = containsRtlText("", clip)
    val tf = if (isRtl) rtlTypeface ?: buildRtlTypeface().also { rtlTypeface = it }
             else android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(12), dp(10), dp(12), dp(10))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(colors.surface)
        setStroke(dp(1), colors.stroke)
      }
      elevation = dp(12).toFloat()

      // Scrollable text
      addView(ScrollView(this@ClipVaultKeyboardService).apply {
        isVerticalScrollBarEnabled = true
        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = clip
          textSize = 13.5f
          setTextColor(colors.text)
          typeface = tf
          textDirection = if (isRtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
          setPadding(0, 0, 0, dp(8))
        })
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)))

      // Action buttons: Paste + Close
      addView(LinearLayout(this@ClipVaultKeyboardService).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER

        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = if (isUrdu) "\uD83D\uDE80 چسپاں" else "\uD83D\uDE80 Paste"
          textSize = 13f
          gravity = Gravity.CENTER
          setTextColor(android.graphics.Color.rgb(99, 215, 125))
          setPadding(dp(16), dp(8), dp(16), dp(8))
          background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat()
            setColor(android.graphics.Color.argb(30, 99, 215, 125))
            setStroke(dp(1), android.graphics.Color.argb(80, 99, 215, 125))
          }
          setOnClickListener {
            alternatePopup?.dismiss(); alternatePopup = null
            currentInputConnection?.commitText(clip, 1)
            typedBuffer = ""
            panelMode = PanelMode.KEYS
            clipSearchQuery = ""
            updateStatus(if (isUrdu) "✓ چسپاں" else "✓ pasted")
            refreshKeyboard()
          }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(0, 0, dp(8), 0) })

        addView(TextView(this@ClipVaultKeyboardService).apply {
          text = if (isUrdu) "✕ بند" else "✕ Close"
          textSize = 13f
          gravity = Gravity.CENTER
          setTextColor(colors.muted)
          setPadding(dp(16), dp(8), dp(16), dp(8))
          setOnClickListener { alternatePopup?.dismiss(); alternatePopup = null }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val screenW = resources.displayMetrics.widthPixels
    alternatePopup = PopupWindow(
      container,
      (screenW * 0.92).toInt(),
      ViewGroup.LayoutParams.WRAP_CONTENT,
      false,
    ).apply {
      isOutsideTouchable = true
      isClippingEnabled = false
      elevation = dp(12).toFloat()
      showAtLocation(container, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(60))
    }
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

  private fun addTextEditPanel(parent: LinearLayout, colors: ThemeColors) {
    val rows = listOf(
      listOf("select_all", "cut", "copy", "paste"),
      listOf("undo", "redo", "home", "end", "keys")
    )
    rows.forEach { row -> addKeyRow(parent, row, colors, dp(44), weight = 1f) }
  }

  private fun addSettingsPanel(parent: LinearLayout, colors: ThemeColors) {
    val scroll = ScrollView(this).apply {
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      isFillViewport = true
      isVerticalScrollBarEnabled = false
    }
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(6), dp(4), dp(6), dp(4))
    }

    val header = TextView(this).apply {
      text = "⚙️ Keyboard Settings\n${com.clipvault.mobile.keyboard.VersionControlManager.getFormattedVersionInfo()}"
      textSize = 14f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(colors.accent)
      setPadding(0, dp(4), 0, dp(8))
      gravity = Gravity.CENTER
    }
    content.addView(header)

    val rows = listOf(
      listOf("toggle_autocorrect", "toggle_doublespace"),
      listOf("toggle_offline_voice", "toggle_system_theme"),
      listOf("haptics_toggle", "keys"),
      listOf("height_minus", "height_plus", "keysize_minus", "keysize_plus"),
      listOf("theme", "languages")
    )
    rows.forEach { row -> addKeyRow(content, row, colors, dp(44)) }

    scroll.addView(content)
    parent.addView(scroll)
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
        val btn = createKeyButton(labelFor(key), key, colors, isControlKey(key)).apply {
          bindKeyTouch(this, key)
        }
        addView(btn, LinearLayout.LayoutParams(0, keyHeight, keyWeight(key)).apply {
          setMargins(dp(3), dp(2), dp(3), dp(2))
        })
        
        keyButtons[key] = btn
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
          setMargins(dp(3), dp(2), dp(3), dp(2))
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
      tag = key
      text = displayText
      setSingleLine(!allowsTwoLines)
      maxLines = if (allowsTwoLines) 2 else 1
      ellipsize = android.text.TextUtils.TruncateAt.END
      val isActionEnter = key == "enter" && (currentInputEditorInfo?.imeOptions?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION) in listOf(
          android.view.inputmethod.EditorInfo.IME_ACTION_SEND,
          android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH,
          android.view.inputmethod.EditorInfo.IME_ACTION_GO,
          android.view.inputmethod.EditorInfo.IME_ACTION_DONE
      ))

      setTextColor(when {
        isActionEnter -> Color.WHITE
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
      } else if (isActionEnter) {
        GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = dp(colors.radius).toFloat()
          setColor(colors.accent)
          setStroke(dp(1), colors.stroke)
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

  private fun ensureKeyHitBoxes() {
    if (keyHitBoxes.isEmpty() && keyButtons.isNotEmpty()) {
      val loc = IntArray(2)
      for ((k, b) in keyButtons) {
        if (!b.isShown || b.width == 0) continue
        b.getLocationOnScreen(loc)
        keyHitBoxes[k] = android.graphics.RectF(
          loc[0].toFloat(), loc[1].toFloat(),
          (loc[0] + b.width).toFloat(), (loc[1] + b.height).toFloat()
        )
      }
    }
  }

  private fun handleResponsiveKeyTouch(view: View, key: String, event: MotionEvent): Boolean {
    when (event.action) {
      MotionEvent.ACTION_DOWN -> {
        // --- Swipe typing start detection (instantaneous, no synchronous layout query) ---
        if (layoutMode == LayoutMode.EN && !isControlKey(key) && key.length == 1 && key[0].isLetter() && panelMode == PanelMode.KEYS) {
            swipePath.clear()
            swipeKeySequence.clear()
            lastSwipeKey = key
            swipeStartTime = System.currentTimeMillis()
            swipePath.add(SwipePoint(event.rawX, event.rawY, swipeStartTime))
            swipeKeySequence.add(key)
            isSwipeActive = false
        }
        // ------------------------------------

        if (key.length == 1 && panelMode == PanelMode.KEYS && layoutMode != LayoutMode.SYMBOLS && !isControlKey(key)) {
            showKeyPreview(view, key, labelFor(key))
        }
        if (key == "space") {
            spaceSwipeStartX = event.rawX
            isSpaceSwiping = false
        }

        view.isPressed = true
        if (hapticsEnabled) triggerVibration(view)
        touchConsumed = false
        slideHighlightedIndex = -1
        dismissAlternatePopup()
        cancelPendingLongPress()
        longPressTriggered = false

        val alternates = alternatesForKey(key)
        when {
          key == "," || key == "urdu_comma" || key == "emoji" || key == "emoji_comma" -> {
            pendingLongPressRunnable = Runnable {
              touchConsumed = true
              longPressTriggered = true
              if (hapticsEnabled) triggerVibration(view)
              panelMode = PanelMode.EMOJI
              refreshKeyboard()
            }
            repeatHandler.postDelayed(pendingLongPressRunnable!!, 150L)
          }
          key == "lang" -> {
            pendingLongPressRunnable = Runnable {
              touchConsumed = true
              longPressTriggered = true
              panelMode = PanelMode.LANGUAGES
              refreshKeyboard()
            }
            repeatHandler.postDelayed(pendingLongPressRunnable!!, 300L)
          }
          key == "shift" -> {
            pendingLongPressRunnable = Runnable {
              touchConsumed = true
              longPressTriggered = true
              shiftState = ShiftState.CAPS_LOCK
              updateKeyLabelsForShift()
            }
            repeatHandler.postDelayed(pendingLongPressRunnable!!, 300L)
          }
          alternates.isNotEmpty() -> {
            pendingLongPressRunnable = Runnable {
              longPressTriggered = true
              touchConsumed = true
              if (hapticsEnabled) triggerVibration(view)
              showAlternatesPopup(view, alternates)
            }
            repeatHandler.postDelayed(pendingLongPressRunnable!!, 260L)
          }
          else -> {
            touchConsumed = false
          }
        }
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (key == "space") {
            val dx = event.rawX - spaceSwipeStartX
            if (Math.abs(dx) > dp(20)) {
                isSpaceSwiping = true
                touchConsumed = true
                val cursorMovement = (dx / dp(15)).toInt()
                if (cursorMovement != 0) {
                    val ic = currentInputConnection
                    if (ic != null) {
                        val currentSelection = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
                        val newPos = Math.max(0, currentSelection + cursorMovement)
                        ic.setSelection(newPos, newPos)
                    }
                    spaceSwipeStartX = event.rawX
                    if (hapticsEnabled) triggerVibration(view)
                }
            }
            if (isSpaceSwiping) return true
        }

        // --- Swipe detection & tracing (English only, requires deliberate drag) ---
        if (layoutMode == LayoutMode.EN && swipePath.isNotEmpty() && panelMode == PanelMode.KEYS) {
            val rawX = event.rawX
            val rawY = event.rawY
            val dx = rawX - swipePath.first().x
            val dy = rawY - swipePath.first().y
            val totalDistance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            if (totalDistance > dp(45) && !isSwipeActive) {
                ensureKeyHitBoxes()
                isSwipeActive = true
                cancelPendingLongPress()
                touchConsumed = true
                dismissAlternatePopup()
            }

            if (isSwipeActive) {
                swipePath.add(SwipePoint(rawX, rawY, System.currentTimeMillis()))
                swipeTrailView?.updateTrail(swipePath)

                val hitKey = findKeyAtPosition(rawX, rawY)
                if (hitKey != null && hitKey != lastSwipeKey) {
                    lastSwipeKey = hitKey
                    swipeKeySequence.add(hitKey)
                    if (hapticsEnabled) triggerVibration(view)
                }
                return true
            }
        }
        // ---------------------------------

        if (alternatePopup?.isShowing == true && slideAlternateButtons.isNotEmpty()) {
          val rawX = event.rawX
          val rawY = event.rawY
          var bestIdx = -1
          var bestDist = Float.MAX_VALUE
          val loc = IntArray(2)
          for (idx in slideAlternateButtons.indices) {
            val btn = slideAlternateButtons[idx]
            btn.getLocationOnScreen(loc)
            val btnCenterX = loc[0] + btn.width / 2f
            val btnCenterY = loc[1] + btn.height / 2f
            val dx = rawX - btnCenterX
            val dy = rawY - btnCenterY
            val dist = if (slideAlternateButtons.size > 8) (dx * dx + dy * dy * 1.5f) else Math.abs(dx)
            if (dist < bestDist) {
              bestDist = dist
              bestIdx = idx
            }
          }
          if (bestIdx >= 0 && slideHighlightedIndex != bestIdx) {
            slideHighlightedIndex = bestIdx
            if (hapticsEnabled) triggerVibration(view)
            val c = themeColors()
            slideAlternateButtons.forEachIndexed { i, b ->
              b.background = keyBackground(c, i == bestIdx)
              b.alpha = if (i == bestIdx) 1f else 0.55f
            }
          }
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        dismissKeyPreview()
        view.isPressed = false

        if (key == "space" && isSpaceSwiping) {
            isSpaceSwiping = false
            return true
        }

        // --- Swipe recognition (English only) ---
        if (layoutMode == LayoutMode.EN && isSwipeActive && swipeKeySequence.size >= 2) {
            val recognizedWord = recognizeSwipeWord(swipeKeySequence)
            if (recognizedWord != null) {
                if (typedBuffer.isNotEmpty()) {
                    currentInputConnection?.deleteSurroundingText(typedBuffer.length, 0)
                }
                currentInputConnection?.commitText("$recognizedWord ", 1)
                typedBuffer = ""
                if (shiftState == ShiftState.SHIFTED) {
                    shiftState = ShiftState.OFF
                    updateKeyLabelsForShift()
                }
                updateSuggestionStrip()
            }
            swipePath.clear()
            swipeKeySequence.clear()
            swipeTrailView?.clearTrail()
            isSwipeActive = false
            lastSwipeKey = ""
            cancelPendingLongPress()
            return true
        }
        isSwipeActive = false
        swipePath.clear()
        swipeTrailView?.clearTrail()
        // -------------------------

        cancelPendingLongPress()
        if (alternatePopup?.isShowing == true) {
          val idx = if (slideHighlightedIndex >= 0) slideHighlightedIndex else 0
          if (idx >= 0 && idx < slideAlternateButtons.size) {
            val alt = slideAlternateButtons[idx].tag as? String
            if (alt != null) { handleKey(alt); updateStatus("\u2713 alternate") }
          }
          dismissAlternatePopup()
          slideHighlightedIndex = -1
        } else if (!longPressTriggered) {
            val currentTime = System.currentTimeMillis()
            if (isEmojiSearchMode && (key.length == 1 || key == "space")) {
                emojiSearchBuffer += if (key == "space") " " else key
                refreshKeyboard()
            } else if (isEmojiSearchMode && (key == "delete" || key == "back")) {
                if (emojiSearchBuffer.isNotEmpty()) {
                    emojiSearchBuffer = emojiSearchBuffer.dropLast(1)
                    if (emojiSearchBuffer.isEmpty()) isEmojiSearchMode = false
                    refreshKeyboard()
                } else {
                    isEmojiSearchMode = false
                    refreshKeyboard()
                }
            } else {
                handleKey(key)
            }
            lastTapTime = currentTime
        }
        touchConsumed = false
        longPressTriggered = false
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        dismissKeyPreview()
        view.isPressed = false
        cancelPendingLongPress()
        touchConsumed = false
        longPressTriggered = false
        return true
      }
    }
    return true
  }

  private fun findKeyAtPosition(rawX: Float, rawY: Float): String? {
      for ((key, rect) in keyHitBoxes) {
          if (key.length == 1 && key[0].isLetter() && rect.contains(rawX, rawY)) {
              return key
          }
      }
      var closest: String? = null
      var minDist = Float.MAX_VALUE
      val tolerance = dp(12).toFloat()
      for ((key, rect) in keyHitBoxes) {
          if (key.length != 1 || !key[0].isLetter()) continue
          val cx = rect.centerX()
          val cy = rect.centerY()
          val dist = Math.sqrt(((rawX - cx) * (rawX - cx) + (rawY - cy) * (rawY - cy)).toDouble()).toFloat()
          if (dist < minDist && dist < tolerance + rect.width() / 2) {
              minDist = dist
              closest = key
          }
      }
      return closest
  }

  private fun recognizeSwipeWord(keySequence: List<String>): String? {
      if (keySequence.size < 2) return null
      val path = keySequence.joinToString("").lowercase()
      val firstKey = keySequence.first().lowercase()
      val lastKey = keySequence.last().lowercase()

      val candidates = (dictionaryForLayout() + swipeDictionary + getPersonalDictionary() + cachedLearnedWords)
          .filter { word ->
              val w = word.lowercase()
              w.length >= 2 &&
              w.startsWith(firstKey) &&
              w.endsWith(lastKey) &&
              w.length in (keySequence.size - 3)..(keySequence.size + 3)
          }

      if (candidates.isEmpty()) return null

      val scored = candidates.map { word ->
          word to swipeMatchScore(word.lowercase(), keySequence.map { it.lowercase() })
      }.filter { it.second < Int.MAX_VALUE }
       .sortedBy { it.second }

      val best = scored.firstOrNull() ?: return null
      
      lastSwipeAlternatives = scored.map { it.first }

      return if (shiftState != ShiftState.OFF) {
          best.first.replaceFirstChar { it.uppercase() }
      } else {
          best.first
      }
  }

  private fun swipeMatchScore(word: String, swipedKeys: List<String>): Int {
      // Advanced Probabilistic Scoring (ML-Simulated Gesture Decoder)
      val m = word.length
      val n = swipedKeys.size
      if (m == 0 || n == 0) return Int.MAX_VALUE

      val dp = Array(m + 1) { IntArray(n + 1) }

      for (i in 0..m) dp[i][0] = i * 10 // Penalty for missing a required letter
      for (j in 0..n) dp[0][j] = j * 2  // Small penalty for sliding over extra keys

      for (i in 1..m) {
          for (j in 1..n) {
              val isMatch = word[i - 1].toString() == swipedKeys[j - 1]
              val cost = if (isMatch) 0 else 5
              
              dp[i][j] = minOf(
                  dp[i - 1][j] + 10,        // Deletion (missed a letter in word)
                  dp[i][j - 1] + 2,         // Insertion (slid over an extra key)
                  dp[i - 1][j - 1] + cost   // Match or Substitution
              )
          }
      }
      
      // Bonus: if start and end keys match exactly, reduce the score
      var finalScore = dp[m][n]
      if (word.first().toString() == swipedKeys.first()) finalScore -= 5
      if (word.last().toString() == swipedKeys.last()) finalScore -= 5

      return maxOf(0, finalScore)
  }

  private fun keyBackground(colors: ThemeColors, special: Boolean): StateListDrawable {
    val baseColor = if (special) colors.specialKey else colors.key
    val cacheKey = "key_${baseColor}_${colors.stroke}_${colors.radius}"
    
    val cached = drawableCache.get(cacheKey)
    if (cached != null) {
      return (cached.constantState?.newDrawable()?.mutate() as? StateListDrawable) ?: cached as StateListDrawable
    }

    // Normal state — clean, soft, luxury rounded rectangle matching Gboard
    val normalBg = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(colors.radius).toFloat()
      setColor(baseColor)
      setStroke(dp(1), colors.stroke)
    }

    // Pressed state — responsive subtle accent glow / pressed tone
    val pressedColor = Color.argb(
      255,
      (Color.red(baseColor) * 0.82f + Color.red(colors.accent) * 0.18f).toInt().coerceIn(0, 255),
      (Color.green(baseColor) * 0.82f + Color.green(colors.accent) * 0.18f).toInt().coerceIn(0, 255),
      (Color.blue(baseColor) * 0.82f + Color.blue(colors.accent) * 0.18f).toInt().coerceIn(0, 255)
    )
    val pressedBg = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(colors.radius).toFloat()
      setColor(pressedColor)
      setStroke(dp(1), colors.accent)
    }

    val stateList = StateListDrawable().apply {
      addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
      addState(intArrayOf(), normalBg)
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

  private fun applySuggestionsToStrip(suggestionsList: List<String>) {
    try {
      val suggestions = (suggestionsList + listOf("", "", "")).take(3)

      // If no typed buffer and all suggestions blank → hide strip
      if (typedBuffer.isEmpty() && suggestions.all { it.isBlank() }) {
        suggestionButtons.forEach { it.text = " "; it.isEnabled = false; it.alpha = 0f }
        return
      }

      suggestionButtons.forEachIndexed { index, button ->
        val suggestion = suggestions.getOrNull(index).orEmpty()
        val isCenter = index == 1
        // The center always shows the typed word; enable it even when alternatives are empty
        val isTypedWord = isCenter && suggestion.isNotBlank()
        button.text = if (suggestion.isBlank()) " " else compactLabel(suggestion, 18)
        button.tag = suggestion
        button.isEnabled = suggestion.isNotBlank()
        button.alpha = when {
          isTypedWord -> 1f          // Center typed word: always fully visible
          suggestion.isBlank() -> 0.3f
          else -> 1f
        }
      }
    } catch (e: Exception) {
      suggestionButtons.forEach { it.text = " "; it.isEnabled = false }
    }
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

      val buffer = typedBuffer
      if (buffer.isEmpty()) {
        suggestionRequestId++
        pendingSuggestionRunnable?.let { mainHandler.removeCallbacks(it) }
        currentQuickSuggestions = emptyList()

        val ic = currentInputConnection
        val textBefore = ic?.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val contextNextWords = com.clipvault.mobile.keyboard.SentenceContextEngine.predictNextWords(textBefore, 3)

        val latestClip = cachedRecentClips.firstOrNull()?.trim()
        val showClip = !latestClip.isNullOrBlank() && !isIncognitoMode && (textBefore.isBlank() || textBefore.endsWith("\n"))

        val smartReplies = if (showClip && latestClip != null) com.clipvault.mobile.keyboard.SmartReplyEngine.generateReplies(latestClip) else null
        if (smartReplies != null) {
          // AI Smart Reply Chips for incoming messages
          val replies = smartReplies.toList()
          for (i in 0..2) {
            val reply = replies[i]
            suggestionButtons[i].text = "💬 " + compactLabel(reply, 14)
            suggestionButtons[i].tag = "SMART_REPLY:$reply"
            suggestionButtons[i].isEnabled = true
            suggestionButtons[i].alpha = 1f
          }
        } else if (showClip) {
          // Gboard-style clipboard chip on fresh line or empty text
          suggestionButtons[0].text = "📋 " + compactLabel(latestClip!!, 16)
          suggestionButtons[0].tag = "PASTE_CLIP:$latestClip"
          suggestionButtons[0].isEnabled = true
          suggestionButtons[0].alpha = 1f

          // If next words exist, use slots 1 & 2 for contextual predictions
          if (contextNextWords.isNotEmpty()) {
            val w1 = contextNextWords.getOrNull(0) ?: ""
            suggestionButtons[1].text = compactLabel(w1, 14)
            suggestionButtons[1].tag = w1
            suggestionButtons[1].isEnabled = w1.isNotBlank()
            suggestionButtons[1].alpha = if (w1.isNotBlank()) 0.85f else 0f

            val w2 = contextNextWords.getOrNull(1) ?: ""
            suggestionButtons[2].text = compactLabel(w2, 14)
            suggestionButtons[2].tag = w2
            suggestionButtons[2].isEnabled = w2.isNotBlank()
            suggestionButtons[2].alpha = if (w2.isNotBlank()) 0.85f else 0f
          } else {
            suggestionButtons[1].text = " "
            suggestionButtons[1].tag = ""
            suggestionButtons[1].isEnabled = false
            suggestionButtons[1].alpha = 0f

            suggestionButtons[2].text = " "
            suggestionButtons[2].tag = ""
            suggestionButtons[2].isEnabled = false
            suggestionButtons[2].alpha = 0f
          }
        } else if (contextNextWords.isNotEmpty()) {
          // Gboard-level Context-Aware Next-Word Predictions
          val list = if (contextNextWords.size < 3) {
            val fillers = if (layoutMode == LayoutMode.UR || layoutMode == LayoutMode.AR) {
              listOf("کیا", "ہے", "نہیں", "اور", "میں", "آپ", "کا", "کی", "کو", "سب")
            } else {
              listOf("the", "to", "you", "and", "is", "in", "it", "for")
            }
            (contextNextWords + fillers.filter { !contextNextWords.contains(it) }).take(3)
          } else {
            contextNextWords.take(3)
          }
          applySuggestionsToStrip(list)
        } else {
          val fillers = if (layoutMode == LayoutMode.UR || layoutMode == LayoutMode.AR) {
            listOf("السلام علیکم", "کیا", "آپ")
          } else {
            listOf("I", "How", "Thank")
          }
          applySuggestionsToStrip(fillers)
        }
        return
      }

      val reqId = ++suggestionRequestId
      pendingSuggestionRunnable?.let { mainHandler.removeCallbacks(it) }

      // ── Immediate preview: typed word in center, prefix completions on sides ──
      val ch = buffer[0].lowercaseChar()
      val baseBucket = indexedBaseDictionary(layoutMode)[ch] ?: emptyList()
      val pool = cachedLearnedWords + cachedPersonalDictionary + baseBucket
      val quickAlternatives = mutableListOf<String>()
      for (w in pool) {
        if (w.startsWith(buffer, ignoreCase = true) && !w.equals(buffer, ignoreCase = true) && !quickAlternatives.contains(w)) {
          quickAlternatives.add(w)
          if (quickAlternatives.size >= 2) break
        }
      }

      // Center = user's own word, left/right = alternatives
      val immediateStrip = buildSuggestionStrip(buffer, quickAlternatives)
      currentQuickSuggestions = immediateStrip
      applySuggestionsToStrip(immediateStrip)

      // ── Async deep evaluation (25ms debounce) ─────────────────────────────────
      val task = Runnable {
        backgroundExecutor.execute {
          if (reqId != suggestionRequestId) return@execute
          val deep = computeDeepSuggestions(buffer)
          if (reqId != suggestionRequestId) return@execute
          mainHandler.post {
            if (reqId == suggestionRequestId) {
              val alts = deep.filter { !it.equals(buffer, ignoreCase = true) }
              val deepStrip = if (alts.isNotEmpty()) {
                listOf(alts.getOrNull(0) ?: "", buffer, alts.getOrNull(1) ?: (alts.getOrNull(2) ?: ""))
              } else {
                buildSuggestionStrip(buffer, emptyList())
              }
              currentQuickSuggestions = deepStrip
              applySuggestionsToStrip(deepStrip)
            }
          }
        }
      }
      pendingSuggestionRunnable = task
      mainHandler.postDelayed(task, 25L)
    } catch (e: Exception) {
      suggestionButtons.forEach { it.text = " "; it.isEnabled = false }
    }
  }

  /**
   * Builds a 3-slot suggestion list: left alternative, center typed word, right alternative.
   */
  private fun buildSuggestionStrip(typed: String, alternatives: List<String>): List<String> {
    val left = alternatives.getOrNull(0) ?: ""
    val right = alternatives.getOrNull(1) ?: ""
    return listOf(left, typed, right)
  }

  private fun updateKeyLabelsForShift() {
    if (layoutMode != LayoutMode.EN) return
    val isUpper = shiftState != ShiftState.OFF
    for ((key, view) in keyButtons) {
      if (key.length == 1 && key[0].isLetter() && view is Button) {
        view.text = if (isUpper) key.uppercase() else key.lowercase()
      } else if (key == "shift" && view is Button) {
        view.text = labelFor("shift")
      }
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
      "next_keyboard", "switch_system" -> "\u2328"
      "urdu_qmark" -> "\u061F"  // Urdu question mark ؟
      "clips" -> "\uD83D\uDCCB"
      "gifs" -> "GIF"
      "stickers" -> "\uD83D\uDE00" // Smiley face as sticker icon
      "emoji", "emoji_comma" -> "\u263A"
      "emoji_prev" -> "\u2039"
      "emoji_next" -> "\u203A"
      "emoji_page" -> "${emojiPageIndex + 1}/${emojiPages().size}"
      "keys" -> "ABC"
      "languages" -> "\u22EE"
      "marks" -> "اعراب"
      "," -> ","
      "urdu_comma" -> "\u060C"
      "urdu_period" -> "\u06D4"
      "symbols" -> "123"
      "sym_prev" -> "\u25C4 Prev"
      "sym_next" -> "Next \u25BA"
      "sym_cycle" -> "<>"
      "abc" -> when (lastTextLayoutMode) {
        LayoutMode.UR -> "\u0627\u0631\u062F\u0648"
        LayoutMode.AR -> "\u0639\u0631\u0628\u064A"
        else -> "ABC"
      }
      "dialpad_toggle" -> if (isDialpadMode) "Sym" else "\uD83D\uDD22"
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
      "text_edit" -> "✂️"
      "incognito" -> if (isIncognitoMode) "🕶️" else "👓"
      "select_all" -> "Select All"
      "cut" -> "✂️ Cut"
      "copy" -> "⎘ Copy"
      "paste" -> "📋 Paste"
      "undo" -> "↩️ Undo"
      "redo" -> "↪️ Redo"
      "home" -> "↖ Home"
      "end" -> "↘ End"
      "settings" -> "⚙️"
      "toggle_autocorrect" -> if (autoCorrectEnabled) "💡 Auto-Correct: ON" else "💡 Auto-Correct: OFF"
      "toggle_doublespace" -> if (doubleSpacePeriodEnabled) "␣ Double-Space: ON" else "␣ Double-Space: OFF"
      "toggle_system_theme" -> if (systemThemeAutoFollow) "🌓 Auto Theme: ON" else "🌓 Auto Theme: OFF"
      "toggle_offline_voice" -> if (com.clipvault.mobile.keyboard.OfflineSpeechEngine.isOfflinePreferred(this)) "🎙️ Offline: ON" else "☁️ Cloud: ON"
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
      "symbols", "abc", "clips", "gifs", "stickers", "emoji", "emoji_comma", "emoji_prev", "emoji_next",
      "emoji_page", "keys", "languages", "marks", "space", ",", ".", "urdu_comma",
      "urdu_period", "urdu_qmark", "sym_prev", "sym_next", "sym_cycle", "!", "dialpad_toggle",
      "height_minus", "height_plus", "keysize_minus", "keysize_plus", "haptics_toggle", "ai_rewrite",
      "text_edit", "incognito", "select_all", "cut", "copy", "paste", "undo", "redo", "home", "end",
      "settings", "toggle_autocorrect", "toggle_doublespace", "toggle_system_theme", "toggle_offline_voice"
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
        "space" -> 4.0f
        "enter" -> 1.45f
        "abc" -> 1.35f
        "dialpad_toggle" -> 1.2f
        ",", "." -> 1.0f
        "sym_cycle" -> 1.2f
        else -> 1f
      }
    }
    return when (key) {
      "space" -> 4.0f
      "enter" -> 1.45f
      "shift", "back", "symbols", "abc" -> 1.35f
      "emoji_comma", "lang", "marks", "settings" -> 1.2f
      ",", ".", "urdu_comma", "urdu_period" -> 1.0f
      "emoji_prev", "emoji_next", "emoji_page", "keys" -> 1.25f
      "ai_rewrite" -> 2f
      "toggle_autocorrect", "toggle_doublespace", "toggle_system_theme", "toggle_offline_voice" -> 2.5f
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
      key.startsWith("toggle_") -> 13f
      key == "space" -> 18f
      key in setOf(",", ".", "urdu_comma", "urdu_period") -> 22f
      key in setOf("theme", "mic", "next_keyboard", "switch_system", "clips", "emoji", "emoji_comma", "enter", "marks", "lang", "languages", "ai_rewrite", "settings") -> 22f
      key in setOf("symbols", "abc", "shift", "back", "sym_cycle", "dialpad_toggle") -> 16f
      key.startsWith("theme_") -> 13f
      key in setOf("height_minus", "height_plus", "keysize_minus", "keysize_plus", "haptics_toggle") -> 14f
      key.startsWith("lang_") || key.startsWith("download_") || key.startsWith("remove_") || key == "add_more_languages" || key == "switch_system" -> 14f
      diacriticValues.containsKey(key) -> 24f
      key in setOf("emoji_prev", "emoji_next") -> 23f
      key == "emoji_page" || key == "keys" -> 14f
      layoutMode == LayoutMode.SYMBOLS && key.length == 1 -> 20f
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
      "settings" -> {
        panelMode = if (panelMode == PanelMode.SETTINGS) PanelMode.KEYS else PanelMode.SETTINGS
        refreshKeyboard()
        return
      }
      "toggle_autocorrect" -> {
        autoCorrectEnabled = !autoCorrectEnabled
        saveKeyboardPrefs()
        updateStatus(if (autoCorrectEnabled) "💡 Auto-Correct: ON" else "💡 Auto-Correct: OFF")
        refreshKeyboard()
        return
      }
      "toggle_doublespace" -> {
        doubleSpacePeriodEnabled = !doubleSpacePeriodEnabled
        saveKeyboardPrefs()
        updateStatus(if (doubleSpacePeriodEnabled) "␣ Double-Space: ON" else "␣ Double-Space: OFF")
        refreshKeyboard()
        return
      }
      "toggle_system_theme" -> {
        systemThemeAutoFollow = !systemThemeAutoFollow
        saveKeyboardPrefs()
        updateStatus(if (systemThemeAutoFollow) "🌓 Auto Theme: ON" else "🌓 Auto Theme: OFF")
        refreshKeyboard()
        return
      }
      "toggle_offline_voice" -> {
        val current = com.clipvault.mobile.keyboard.OfflineSpeechEngine.isOfflinePreferred(this)
        com.clipvault.mobile.keyboard.OfflineSpeechEngine.setOfflinePreferred(this, !current)
        updateStatus(if (!current) "🎙️ Offline Voice ON" else "☁️ Cloud Voice ON")
        refreshKeyboard()
        return
      }
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
      "text_edit" -> {
        panelMode = if (panelMode == PanelMode.TEXT_EDIT) PanelMode.KEYS else PanelMode.TEXT_EDIT
        refreshKeyboard()
        return
      }
      "incognito" -> {
        isIncognitoMode = !isIncognitoMode
        updateStatus(if (isIncognitoMode) "🕶️ Incognito ON" else "👓 Incognito OFF")
        refreshKeyboard()
        return
      }
      "select_all" -> {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        updateStatus("✓ Selected All")
        return
      }
      "cut" -> {
        currentInputConnection?.performContextMenuAction(android.R.id.cut)
        updateStatus("✓ Cut")
        return
      }
      "copy" -> {
        currentInputConnection?.performContextMenuAction(android.R.id.copy)
        updateStatus("✓ Copied")
        return
      }
      "paste" -> {
        currentInputConnection?.performContextMenuAction(android.R.id.paste)
        updateStatus("✓ Pasted")
        return
      }
      "undo" -> {
        currentInputConnection?.performContextMenuAction(android.R.id.undo)
        updateStatus("✓ Undo")
        return
      }
      "redo" -> {
        currentInputConnection?.performContextMenuAction(android.R.id.redo)
        updateStatus("✓ Redo")
        return
      }
      "home" -> {
        currentInputConnection?.setSelection(0, 0)
        return
      }
      "end" -> {
        val len = currentInputConnection?.getTextBeforeCursor(10000, 0)?.length ?: 0
        currentInputConnection?.setSelection(len, len)
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
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastShiftTapTime) < 400L && shiftState == ShiftState.SHIFTED
        shiftState = when {
          isDoubleTap -> {
            updateStatus("🔒 CAPS LOCK")
            ShiftState.CAPS_LOCK
          }
          shiftState == ShiftState.OFF -> ShiftState.SHIFTED
          shiftState == ShiftState.SHIFTED -> ShiftState.OFF
          else -> ShiftState.OFF  // CAPS_LOCK -> OFF
        }
        lastShiftTapTime = now
        updateKeyLabelsForShift()
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
      "stickers" -> {
        panelMode = if (panelMode == PanelMode.EMOJI && isEmojiKitchenMode) PanelMode.KEYS else PanelMode.EMOJI
        isEmojiKitchenMode = (panelMode == PanelMode.EMOJI)
        alternateKeys = emptyList()
        refreshKeyboard()
      }
      "emoji", "emoji_comma", "gifs" -> {
        panelMode = if (panelMode == PanelMode.EMOJI && !isEmojiKitchenMode) PanelMode.KEYS else PanelMode.EMOJI
        if (panelMode == PanelMode.EMOJI) isEmojiKitchenMode = false
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

    com.clipvault.mobile.keyboard.GeminiAiHelper.rewriteText(textToRewrite) { result ->
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
      "space" -> {
        val now = System.currentTimeMillis()
        val isDoubleSpace = doubleSpacePeriodEnabled
            && typedBuffer.isEmpty()
            && (now - lastSpaceTapTime) < 500L
            && (now - lastSpaceTapTime) > 80L // debounce accidental double-tap
        if (isDoubleSpace) {
          // Double-space → replace trailing space with ". " and auto-shift
          val textBefore = connection.getTextBeforeCursor(1, 0)?.toString() ?: ""
          if (textBefore == " ") {
            connection.deleteSurroundingText(1, 0)
            val period = if (layoutMode == LayoutMode.UR || layoutMode == LayoutMode.AR) "۔ " else ". "
            connection.commitText(period, 1)
            // Auto-shift next letter
            if (shiftState == ShiftState.OFF) {
              shiftState = ShiftState.SHIFTED
              updateKeyLabelsForShift()
            }
            lastSpaceTapTime = 0L // reset so triple-space doesn't loop
          } else {
            commitSpaceWithAutoCorrect()
            lastSpaceTapTime = now
          }
        } else {
          commitSpaceWithAutoCorrect()
          lastSpaceTapTime = now
        }
      }
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
        updateKeyLabelsForShift()
      }
      updateSuggestionStrip()
    } catch (e: Exception) {
      // Suppress unhandled exceptions that hang the keyboard service
    }
  }

  private fun commitSpaceWithAutoCorrect() {
    val connection = currentInputConnection ?: return

    val typed = typedBuffer.trim()

    // ── Safe-autocorrect guard ─────────────────────────────────────────────────
    // Only apply autocorrect from the KNOWN common-corrections map (e.g. "teh"→"the").
    // We deliberately do NOT apply fuzzy/prefix/native-spellcheck suggestions on space,
    // because those produce false positives (e.g. replacing a valid Urdu word with an
    // unrelated English word) and destroy the user's intended text silently.
    val safeCorrection: String? = if (autoCorrectEnabled
        && typed.isNotBlank()
        && typed.length >= 3
        && !cachedLearnedWords.contains(typed)
        && !cachedPersonalDictionary.contains(typed)) {

      val normalised = typed.lowercase()
      val candidate = commonCorrectionFor(normalised)
      // Only apply if candidate exists, is not identical, and was not previously rejected
      if (candidate != null && "$typed\u00B6$candidate" !in rejectedCorrections && !candidate.equals(typed, ignoreCase = true)) candidate else null
    } else null

    if (safeCorrection != null) {
      // Replace typed word with the safe correction
      connection.deleteSurroundingText(typed.length, 0)
      connection.commitText(safeCorrection, 1)
      lastSuggestionOriginal = typed
      lastSuggestionApplied = safeCorrection
    } else {
      // No replacement — just learn the word the user typed
      if (typed.isNotBlank()) {
        registerWord(typed)
        NativeClipStore.addLearnedWord(this, typed)
      }
      lastSuggestionOriginal = null
      lastSuggestionApplied = null
    }

    // Dynamic n-gram learning: record consecutive word sequence
    val committedWord = safeCorrection ?: typed
    if (committedWord.isNotBlank()) {
      val textBefore = connection.getTextBeforeCursor(150, 0)?.toString() ?: ""
      val prefixText = if (textBefore.endsWith(typed)) {
        textBefore.dropLast(typed.length).trimEnd()
      } else {
        textBefore.trimEnd()
      }
      val prevWord = prefixText.split(Regex("\\s+")).lastOrNull { it.isNotBlank() }
      if (!prevWord.isNullOrBlank() && prevWord != committedWord) {
        com.clipvault.mobile.keyboard.SentenceContextEngine.recordWordCommit(prevWord, committedWord)
      }
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
        val wasUndo = deleteOneCharacter(refresh = true)
        if (!wasUndo) {
          beginRepeatingDelete()
        }
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
    repeatHandler.postDelayed(runnable, 350L)
  }

  private fun stopRepeatingDelete() {
    repeatDeleteRunnable?.let { repeatHandler.removeCallbacks(it) }
    repeatDeleteRunnable = null
  }

  private fun cancelPendingLongPress() {
    pendingLongPressRunnable?.let { repeatHandler.removeCallbacks(it) }
    pendingLongPressRunnable = null
  }

  private fun deleteOneCharacter(refresh: Boolean = true): Boolean {
    try {
      val ic = currentInputConnection ?: return false

      // ─ Undo last accepted suggestion on first back-press ──────────────────────
      val orig = lastSuggestionOriginal
      val applied = lastSuggestionApplied
      if (applied != null) {
        val textBefore = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val appliedTrimmed = applied.trim()
        val textTrimmed = textBefore.trimEnd {
          it.isWhitespace() || it == '\u200F' || it == '\u200E' || it == '\u200B' || it == '\u00A0' || it == '\u061C'
        }
        val trailingWhitespaceCount = textBefore.length - textTrimmed.length

        var matched = false
        var deleteCount = 0

        // Case 1: Exact suffix check (with or without trailing space)
        if (textBefore.endsWith("$applied ")) {
          deleteCount = applied.length + 1
          matched = true
        } else if (textBefore.endsWith(applied)) {
          deleteCount = applied.length
          matched = true
        } else if (textTrimmed.endsWith(appliedTrimmed)) {
          deleteCount = appliedTrimmed.length + trailingWhitespaceCount
          matched = true
        } else {
          // Case 2: Unicode NFC normalization check
          val normText = java.text.Normalizer.normalize(textTrimmed, java.text.Normalizer.Form.NFC)
          val normApplied = java.text.Normalizer.normalize(appliedTrimmed, java.text.Normalizer.Form.NFC)
          if (normText.endsWith(normApplied)) {
            val targetLen = appliedTrimmed.length + trailingWhitespaceCount
            deleteCount = targetLen.coerceAtMost(textBefore.length)
            matched = true
          }
        }

        if (matched && deleteCount > 0) {
          ic.deleteSurroundingText(deleteCount, 0)
          val restoreText = orig ?: ""
          if (restoreText.isNotEmpty()) {
            ic.commitText(restoreText, 1)
            typedBuffer = restoreText
            rejectedCorrections.add("$restoreText\u00B6$applied")
            NativeClipStore.addLearnedWord(this, restoreText)
            if (rejectedCorrections.size > 400) rejectedCorrections.remove(rejectedCorrections.first())
            saveRejectedCorrections()
            updateStatus("\u21A9 restored: $restoreText")
          } else {
            typedBuffer = ""
            updateStatus("\u21A9 undone")
          }
          lastSuggestionOriginal = null
          lastSuggestionApplied = null
          updateSuggestionStrip()
          return true
        } else {
          lastSuggestionOriginal = null
          lastSuggestionApplied = null
        }
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
      return false
    } catch (e: Exception) {
      return false
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
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(colors.surface)
        setStroke(dp(1), colors.stroke)
      }
      elevation = dp(8).toFloat()
    }

    val anchorKey = anchor.tag as? String ?: ""
    val isUrduPeriod = anchorKey == "urdu_period" || (alternates.contains("urdu_qmark") && alternates.contains("!"))
    val isTopRowNumberKey = (layoutMode == LayoutMode.UR && topRowNumberAlternatesMapUr.containsKey(anchorKey)) ||
                            (layoutMode == LayoutMode.AR && topRowNumberAlternatesMapAr.containsKey(anchorKey))

    val rows = if (isUrduPeriod && alternates.contains("zabar")) {
      val splitIdx = alternates.indexOf("zabar").takeIf { it > 0 } ?: (alternates.size / 2)
      listOf(alternates.take(splitIdx), alternates.drop(splitIdx))
    } else if (alternates.size > 8) {
      val mid = (alternates.size + 1) / 2
      listOf(alternates.take(mid), alternates.drop(mid))
    } else {
      listOf(alternates)
    }

    val btnW = if (alternates.size > 8) dp(36) else dp(42)
    val btnH = if (alternates.size > 8) dp(42) else dp(46)
    val fontSz = if (alternates.size > 8) 18f else 21f

    rows.forEach { rowKeys ->
      val rowLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(1), 0, dp(1))
      }
      rowKeys.forEach { alternate ->
        val btn = createKeyButton(labelFor(alternate), alternate, colors, false).apply {
          textSize = if (alternate.length > 2) 13f else fontSz
          tag = alternate   // stored for slide-to-select
          setOnClickListener {
            handleKey(alternate)
            dismissAlternatePopup()
            updateStatus("\u2713 alternate")
          }
        }
        buttons.add(btn)
        rowLayout.addView(btn, LinearLayout.LayoutParams(btnW, btnH).apply {
          setMargins(dp(1), 0, dp(1), 0)
        })
      }
      container.addView(rowLayout)
    }

    slideAlternateButtons = buttons

    val defaultInitialIdx = if (isUrduPeriod) {
      buttons.indexOfFirst { it.tag == "urdu_qmark" }.takeIf { it >= 0 } ?: (buttons.size / 2).coerceIn(0, buttons.size - 1)
    } else if (isTopRowNumberKey) {
      0
    } else {
      (buttons.size / 2).coerceIn(0, buttons.size - 1)
    }
    slideHighlightedIndex = defaultInitialIdx

    val maxRowSize = rows.maxOfOrNull { it.size } ?: 1
    val estimatedPopupWidth = dp(8) + maxRowSize * (btnW + dp(2))
    
    // Popup height: container top/bottom padding dp(8) + rows * (btnH + dp(2))
    val popupHeight = dp(8) + rows.size * (btnH + dp(2))
    val anchorHeight = if (anchor.height > 0) anchor.height else dp(48)
    // To ensure the popup sits cleanly on the row ABOVE the pressed key (never under the finger),
    // offset so popup bottom floats 4dp above anchor top.
    val yOffset = -(anchorHeight + popupHeight + dp(4))

    // Calculate clamped xOffset so popup stays within screen bounds
    val screenWidth = resources.displayMetrics.widthPixels
    val anchorLoc = IntArray(2)
    anchor.getLocationOnScreen(anchorLoc)
    val anchorCenterX = anchorLoc[0] + anchor.width / 2

    val desiredX = if (isUrduPeriod && alternates.contains("urdu_qmark")) {
      // In bottom row, urdu_qmark is aligned directly with anchor key center
      val bottomRow = rows.lastOrNull() ?: emptyList()
      val qmarkPosInRow = bottomRow.indexOf("urdu_qmark").coerceAtLeast(0)
      val qmarkOffsetInPopup = dp(4) + qmarkPosInRow * (btnW + dp(2)) + btnW / 2
      anchorCenterX - qmarkOffsetInPopup
    } else {
      anchorLoc[0] + (anchor.width - estimatedPopupWidth) / 2
    }
    val clampedX = desiredX.coerceIn(dp(4), (screenWidth - estimatedPopupWidth - dp(4)).coerceAtLeast(dp(4)))
    val xOffset = clampedX - anchorLoc[0]

    alternatePopup = PopupWindow(
      container,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      false,
    ).apply {
      isOutsideTouchable = true
      isClippingEnabled = false
      elevation = dp(10).toFloat()
      showAsDropDown(anchor, xOffset, yOffset)
    }

    // Default highlight until layout completes
    buttons.getOrNull(defaultInitialIdx)?.let {
      it.background = keyBackground(colors, true)
      it.alpha = 1f
    }

    // Post layout: find the exact button whose center is closest to anchor center on screen
    container.post {
      if (alternatePopup?.isShowing == true && slideAlternateButtons.isNotEmpty()) {
        if (isUrduPeriod) {
          val qmarkIdx = slideAlternateButtons.indexOfFirst { it.tag == "urdu_qmark" }
          if (qmarkIdx >= 0) {
            slideHighlightedIndex = qmarkIdx
            val c = themeColors()
            slideAlternateButtons.forEachIndexed { i, b ->
              b.background = keyBackground(c, i == qmarkIdx)
              b.alpha = if (i == qmarkIdx) 1f else 0.55f
            }
            return@post
          }
        }
        if (isTopRowNumberKey) {
          slideHighlightedIndex = 0
          val c = themeColors()
          slideAlternateButtons.forEachIndexed { i, b ->
            b.background = keyBackground(c, i == 0)
            b.alpha = if (i == 0) 1f else 0.55f
          }
          return@post
        }
        val curALoc = IntArray(2)
        anchor.getLocationOnScreen(curALoc)
        val curACenterX = curALoc[0] + anchor.width / 2f
        val bLoc = IntArray(2)
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        slideAlternateButtons.forEachIndexed { i, b ->
          b.getLocationOnScreen(bLoc)
          val bCenterX = bLoc[0] + b.width / 2f
          val dist = Math.abs(bCenterX - curACenterX)
          if (dist < bestDist) {
            bestDist = dist
            bestIdx = i
          }
        }
        slideHighlightedIndex = bestIdx
        val c = themeColors()
        slideAlternateButtons.forEachIndexed { i, b ->
          b.background = keyBackground(c, i == bestIdx)
          b.alpha = if (i == bestIdx) 1f else 0.55f
        }
      }
    }
  }

  private fun dismissAlternatePopup() {
    alternatePopup?.dismiss()
    alternatePopup = null
    slideAlternateButtons = emptyList()
    slideHighlightedIndex = -1
  }

  private fun alternatesForKey(key: String): List<String> {
    val numberAlternates = when (layoutMode) {
      LayoutMode.UR -> topRowNumberAlternatesMapUr[key].orEmpty()
      LayoutMode.AR -> topRowNumberAlternatesMapAr[key].orEmpty()
      else -> emptyList()
    }
    val letterAlternates = when (layoutMode) {
      LayoutMode.UR -> urduAlternatesMap[key].orEmpty()
      LayoutMode.AR -> arabicAlternatesMap[key].orEmpty()
      LayoutMode.EN, LayoutMode.SYMBOLS -> englishAlternatesMap[key].orEmpty()
    }

    return (numberAlternates + letterAlternates).distinct().filter { it != key }
  }

  private fun emojiPages(): List<List<String>> {
    return com.clipvault.mobile.keyboard.EmojiCatalog.allCategories(recentEmojis).map { it.emojis }
  }

  private fun insertSuggestion(suggestion: String) {
    val connection = currentInputConnection ?: return

    val textBefore = connection.getTextBeforeCursor(150, 0)?.toString() ?: ""
    val bufLen = typedBuffer.length

    val wordBeingReplaced = if (bufLen > 0 && textBefore.endsWith(typedBuffer)) {
      typedBuffer
    } else {
      textBefore.takeLastWhile {
        it.isLetterOrDigit() || it.code in 0x0600..0x06FF || it == '_' || it == '\''
      }
    }

    val toDeleteLen = wordBeingReplaced.length

    // Dynamic n-gram learning: learn consecutive words
    val prefixText = if (toDeleteLen > 0 && textBefore.endsWith(wordBeingReplaced)) {
      textBefore.dropLast(toDeleteLen).trimEnd()
    } else {
      textBefore.trimEnd()
    }
    val prevWord = prefixText.split(Regex("\\s+")).lastOrNull { it.isNotBlank() }
    if (!prevWord.isNullOrBlank() && prevWord != suggestion) {
      com.clipvault.mobile.keyboard.SentenceContextEngine.recordWordCommit(prevWord, suggestion)
    }

    // Learn word if user explicitly taps their exact typed word
    if (wordBeingReplaced.isNotEmpty() && suggestion.equals(wordBeingReplaced, ignoreCase = true)) {
      NativeClipStore.addLearnedWord(this, suggestion)
      registerWord(suggestion)
    }

    // Store undo information BEFORE making any changes:
    // If a word was replaced, orig is wordBeingReplaced. If no word replaced, orig is "" (so delete erases suggestion).
    lastSuggestionOriginal = wordBeingReplaced
    lastSuggestionApplied = suggestion

    // Delete typed buffer then insert suggestion + space
    if (toDeleteLen > 0) {
      connection.deleteSurroundingText(toDeleteLen, 0)
    }

    connection.commitText("$suggestion ", 1)
    if (wordBeingReplaced.isNotEmpty() && !suggestion.equals(wordBeingReplaced, ignoreCase = true)) {
      registerWord(suggestion)
    }
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
  private fun computeDeepSuggestions(typed: String): List<String> {
    val trimmed = typed.trim()
    val ic = currentInputConnection
    val textBefore = ic?.getTextBeforeCursor(250, 0)?.toString() ?: ""

    if (trimmed.isBlank()) {
        if (lastSwipeAlternatives.isNotEmpty()) {
            return lastSwipeAlternatives.take(3)
        }
        // Gboard-grade Context-Aware Sentence Prediction Engine
        val predictions = com.clipvault.mobile.keyboard.SentenceContextEngine.predictNextWords(textBefore, 3)
        if (predictions.isNotEmpty()) {
            return predictions
        }
        return emptyList()
    }
    lastSwipeAlternatives = emptyList()

    // Math Calculator
    val mathRegex = Regex("""^(\d+(?:\.\d+)?)\s*([\+\-\*/])\s*(\d+(?:\.\d+)?)\s*=$""")
    val mathMatch = mathRegex.find(trimmed)
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
      return NativeClipStore.search(this, trimmed, 5).map { it.replace("\n", " ").trim() }
    }

    val normalized = trimmed.lowercase()
    val firstChar = normalized[0]
    val baseBucket = indexedBaseDictionary(layoutMode)[firstChar].orEmpty()
    val swipeBucket = indexedSwipeDictionary[firstChar].orEmpty()
    val personalList = cachedPersonalDictionary + cachedLearnedWords

    // 1. Roman Urdu transliteration (instant phonetics)
    val romanUrduMatches = if (layoutMode == LayoutMode.EN || layoutMode == LayoutMode.SYMBOLS) {
      com.clipvault.mobile.keyboard.RomanUrduEngine.transliterate(trimmed)
    } else emptyList()

    // 2. Exact match check
    val hasExact = baseBucket.any { it.equals(normalized, ignoreCase = true) } ||
                   personalList.any { it.equals(trimmed, ignoreCase = true) }

    // 3. Context-Boosted Prefix Matches: prioritizing words that fit the preceding sentence
    val rawDictionaryCandidates = (baseBucket + personalList + swipeBucket)
    val prefixMatches = com.clipvault.mobile.keyboard.SentenceContextEngine.boostWithContext(
      textBefore, normalized, rawDictionaryCandidates, 4
    )

    // 4. Contextual Emoji Suggestion (e.g. love -> ❤️, tea -> ☕, fire -> 🔥)
    val contextEmojis = com.clipvault.mobile.keyboard.EmojiCatalog.suggestContextEmojis(trimmed)

    // 5. Correction & Fuzzy matches (only if not exact and length >= 3)
    var correction: String? = null
    val fuzzyMatches = mutableListOf<String>()
    if (!hasExact && trimmed.length >= 3) {
      val common = commonCorrectionFor(normalized)
      if (common != null && "$trimmed\u00B6$common" !in rejectedCorrections) {
        correction = common
      }

      val maxDist = allowedCorrectionDistance(trimmed.length)
      val candidates = (baseBucket + personalList).filter {
        Math.abs(it.length - trimmed.length) <= maxDist &&
        "$trimmed\u00B6$it" !in rejectedCorrections
      }
      val scored = candidates.map { it to editDistance(normalized, it.lowercase(), maxDist) }
        .filter { it.second in 1..maxDist }
        .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })

      if (correction == null && scored.isNotEmpty()) {
        correction = scored.first().first
      }
      for (item in scored.take(3)) {
        if (item.first != correction) fuzzyMatches.add(item.first)
      }
    }

    val res = mutableListOf<String>()
    if (romanUrduMatches.isNotEmpty()) {
      res.addAll(romanUrduMatches)
    }
    if (correction != null) {
      res.add(correction)
      res.add(trimmed)
    } else {
      res.add(trimmed)
    }
    // Contextual emoji inline suggestion
    if (contextEmojis.isNotEmpty()) {
      res.add(contextEmojis.first())
    }
    res.addAll(prefixMatches)
    res.addAll(fuzzyMatches)

    return res.distinct().take(3)
  }

  private fun suggestionsForBuffer(): List<String> {
    val typed = typedBuffer.trim()
    if (typed.isBlank()) return emptyList()
    return computeDeepSuggestions(typed)
  }

  private fun bestCorrectionFor(value: String): String? {
    val typed = value.trim()
    if (typed.length < 3 || typed.any { it.isDigit() }) return null

    val normalized = typed.lowercase()
    commonCorrectionFor(normalized)?.let { correction ->
      if ("$typed\u00B6$correction" !in rejectedCorrections) return correction
    }

    val exact = dictionaryForLayout().firstOrNull { it.lowercase() == normalized }
    if (exact != null) return null

    val maxDist = allowedCorrectionDistance(normalized.length)
    val firstChar = normalized[0]
    val pool = (indexedBaseDictionary(layoutMode)[firstChar].orEmpty() + cachedPersonalDictionary + cachedLearnedWords)
      .filter { Math.abs(it.length - normalized.length) <= maxDist && "$typed\u00B6$it" !in rejectedCorrections }

    return pool
      .map { it to editDistance(normalized, it.lowercase(), maxDist) }
      .filter { it.second in 1..maxDist }
      .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
      .firstOrNull()?.first
  }

  private fun commonCorrectionFor(value: String): String? {
    return when (layoutMode) {
      LayoutMode.UR -> urduCommonCorrections[value]
      LayoutMode.AR -> null
      LayoutMode.EN, LayoutMode.SYMBOLS -> englishCommonCorrections[value]
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
    return when (layoutMode) {
      LayoutMode.EN, LayoutMode.SYMBOLS -> baseDictionaryEn
      LayoutMode.UR -> baseDictionaryUr
      LayoutMode.AR -> baseDictionaryAr
    }
  }

  private fun indexedBaseDictionary(mode: LayoutMode): Map<Char, List<String>> {
    return when (mode) {
      LayoutMode.EN, LayoutMode.SYMBOLS -> indexedBaseDictionaryEn
      LayoutMode.UR -> indexedBaseDictionaryUr
      LayoutMode.AR -> indexedBaseDictionaryAr
    }
  }

  // Returns cached learned words — never reads DB on main thread.
  // Cache is populated asynchronously in refreshCachedDataAsync().
  private fun learnedWordsForLayout(): List<String> = cachedLearnedWords

  private fun editDistance(first: String, second: String, maxDistance: Int = 3): Int {
    if (first == second) return 0
    val len1 = first.length
    val len2 = second.length
    if (Math.abs(len1 - len2) > maxDistance) return maxDistance + 1
    if (len1 == 0) return len2
    if (len2 == 0) return len1

    var previous = IntArray(len2 + 1) { it }
    var current = IntArray(len2 + 1)

    for (i in 0 until len1) {
      current[0] = i + 1
      var minInRow = current[0]
      for (j in 0 until len2) {
        val cost = if (first[i] == second[j]) 0 else 1
        current[j + 1] = minOf(
          current[j] + 1,
          previous[j + 1] + 1,
          previous[j + 1] + cost,
        )
        if (current[j + 1] < minInRow) minInRow = current[j + 1]
      }
      if (minInRow > maxDistance) return maxDistance + 1
      val temp = previous
      previous = current
      current = temp
    }

    return previous[len2]
  }

  companion object {
    private val topRowNumberAlternatesMapUr = listOf("ض", "ص", "غ", "ڑ", "ٹ", "ث", "ح", "ئ", "ظ", "ط")
      .zip(listOf(
        listOf("1", "\u06F1"), listOf("2", "\u06F2"), listOf("3", "\u06F3"), listOf("4", "\u06F4"), listOf("5", "\u06F5"),
        listOf("6", "\u06F6"), listOf("7", "\u06F7"), listOf("8", "\u06F8"), listOf("9", "\u06F9"), listOf("0", "\u06F0")
      ))
      .toMap()

    private val topRowNumberAlternatesMapAr = listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ج")
      .zip(listOf(
        listOf("1", "\u0661"), listOf("2", "\u0662"), listOf("3", "\u0663"), listOf("4", "\u0664"), listOf("5", "\u0665"),
        listOf("6", "\u0666"), listOf("7", "\u0667"), listOf("8", "\u0668"), listOf("9", "\u0669"), listOf("0", "\u0660")
      ))
      .toMap()

    private val englishAlternatesMap = mapOf(
      "a" to listOf("@", "\u00E1", "\u00E0"),
      "e" to listOf("\u00E9", "\u00E8"),
      "i" to listOf("\u00ED", "\u00EC"),
      "o" to listOf("\u00F3", "\u00F2"),
      "u" to listOf("\u00FA", "\u00F9"),
      "." to listOf(",", "?", "!"),
      "," to listOf("emoji"),
    )

    private val urduAlternatesMap = mapOf(
      "urdu_comma" to listOf("emoji"),
      "urdu_period" to listOf("do_zer", "do_pesh", "khari_zabar", "khari_zer", "ulta_pesh", "maddah", "hamza_above", "zabar", "zer", "pesh", "sukun", "shadda", "do_zabar", "urdu_qmark", "!"),
      "urdu_qmark" to listOf("!", "zabar", "zer", "pesh", "sukun", "shadda", "do_zabar", "do_zer", "do_pesh"),
      "\u0627" to listOf("\u0622", "\u0623", "\u0625"),
      "\u0628" to listOf("\u067E"),
      "\u062A" to listOf("\u0679", "\u062B"),
      "\u062C" to listOf("\u0686"),
      "\u062D" to listOf("\u062E"),
      "\u062F" to listOf("\u0688", "\u0630"),
      "\u0631" to listOf("\u0691"),
      "\u0632" to listOf("\u0698"),
      "\u0633" to listOf("\u0634", "\u0635"),
      "\u0637" to listOf("0", "\u06F0"),
      "\u06A9" to listOf("\u06AF", "\u0643"),
      "\u0646" to listOf("\u06BA"),
      "\u06C1" to listOf("\u06BE", "\u06C2", "\u06C3", "\u0629"),
      "\u0621" to listOf("\u0654", "\u0624", "\u0626"),
      "\u0648" to listOf("\u0624"),
      "\u06CC" to listOf("\u0626", "\u06D2", "\u064A"),
      "\u06D2" to listOf("\u0626\u06D2", "\u0626", "\u06CC"),
      "\u0644" to listOf("\u0644\u0627"),
    )

    private val arabicAlternatesMap = mapOf(
      "urdu_comma" to listOf("emoji"),
      "urdu_period" to listOf("urdu_qmark", "!", "zabar", "zer", "pesh", "sukun", "shadda", "do_zabar", "do_zer", "do_pesh"),
      "urdu_qmark" to listOf("!", "zabar", "zer", "pesh", "sukun", "shadda"),
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

    private val baseDictionaryEn = listOf(
      "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
      "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there",
      "their", "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can", "like", "time", "no",
      "just", "him", "know", "take", "people", "into", "year", "your", "good", "some", "could", "them", "see", "other", "than", "then",
      "now", "look", "only", "come", "its", "over", "think", "also", "back", "after", "use", "two", "how", "our", "work", "first",
      "well", "way", "even", "new", "want", "because", "any", "these", "give", "day", "most", "us", "is", "are", "was", "were", "been",
      "being", "doing", "did", "does", "hello", "hi", "hey", "thanks", "thank", "please", "sorry", "welcome",
      "yes", "yeah", "ok", "okay", "fine", "great", "awesome", "perfect", "morning", "night", "evening", "afternoon", "today",
      "tomorrow", "yesterday", "later", "soon", "always", "never", "sometimes", "maybe", "sure", "really", "very", "much", "more",
      "many", "few", "little", "big", "small", "long", "short", "high", "low", "old", "young", "next", "last", "right", "left",
      "true", "false", "happy", "sad", "love", "need", "help", "send", "call", "text", "message", "email", "chat",
      "meet", "talk", "hear", "listen", "read", "write", "type", "keyboard", "phone", "mobile", "app", "application",
      "device", "system", "setting", "settings", "option", "account", "profile", "user", "name", "password", "security", "privacy",
      "data", "file", "folder", "document", "image", "photo", "video", "audio", "music", "song", "link", "url", "website", "code",
      "project", "job", "business", "company", "office", "team", "client", "customer", "service", "support",
      "order", "payment", "money", "price", "cost", "total", "amount", "number", "card", "bank", "check", "confirm", "done",
      "complete", "finish", "start", "begin", "stop", "cancel", "delete", "remove", "save", "share", "copy", "paste", "clipboard",
      "vault", "clipvault", "mindcrafts", "google", "android", "ios", "update", "version", "download", "upload", "install",
      "beautiful", "important", "available", "appointment", "details", "dashboard", "feature", "professional", "automatic",
      "correction", "suggestion", "language"
    )

    private val baseDictionaryUr = listOf(
      "\u0622\u062C", "\u0622\u067E", "\u0622\u0645\u06CC\u0646", "\u0627\u0644\u0633\u0644\u0627\u0645", "\u0627\u0646 \u0634\u0627\u0621 \u0627\u0644\u0644\u06C1",
      "\u0628\u0631\u0627\u06C1 \u06A9\u0631\u0645", "\u0628\u06C1\u062A\u0631\u06CC\u0646", "\u062C\u06CC", "\u062C\u0648\u0627\u0628",
      "\u0631\u0627\u0628\u0637\u06C1", "\u0633\u06CC\u0648", "\u0633\u06CC\u0679\u0646\u06AF\u0632", "\u0634\u06A9\u0631\u06CC\u06C1",
      "\u0633\u062C\u06CC\u0634\u0646", "\u06A9\u0631\u06CC\u06A9\u0634\u0646", "\u06A9\u06CC\u067E\u0686\u0631", "\u06A9\u06CC\u0679\u06CC\u06AF\u0631\u06CC",
      "\u0641\u06CC\u0686\u0631", "\u0632\u0628\u0627\u0646", "\u0679\u0627\u0626\u067E\u0646\u06AF", "\u06A9\u06CC \u0628\u0648\u0631\u0688",
      "\u06A9\u0644\u067E \u0628\u0648\u0631\u0688", "\u0645\u06CC\u0633\u062C", "\u0646\u0645\u0628\u0631", "\u0648\u06CC\u0628 \u0633\u0627\u0626\u0679",
      "\u06A9\u0644", "\u06A9\u06CC\u0627", "\u06A9\u06CC\u0648\u06BA", "\u06A9\u0628", "\u06A9\u06C1\u0627\u06BA", "\u06A9\u06CC\u0633\u06D2",
      "\u06A9\u0648\u0646", "\u06A9\u0686\u06BE", "\u0633\u0628", "\u0628\u06C1\u062A", "\u0632\u06CC\u0627\u062F\u06C1", "\u06A9\u0645",
      "\u0627\u0686\u06BE\u0627", "\u0628\u0631\u0627", "\u06C1\u0627\u06BA", "\u0646\u06C1\u06CC\u06BA", "\u062E\u062F\u0627",
      "\u0627\u0644\u0644\u06C1", "\u062D\u0627\u0641\u0638", "\u062E\u06CC\u0631\u06CC\u062A", "\u06A9\u0627\u0645", "\u0648\u0642\u062A",
      "\u062F\u0646", "\u0631\u0627\u062A", "\u0635\u0628\u062D", "\u0634\u0627\u0645", "\u06AF\u06BE\u0631", "\u062F\u0641\u062A\u0631",
      "\u062F\u0648\u0633\u062A", "\u0628\u06BE\u0627\u0626\u06CC", "\u0628\u06C1\u0646", "\u0627\u0628\u0648", "\u0627\u0645\u06CC",
      "\u062E\u0627\u0646\u062F\u0627\u0646", "\u067E\u06CC\u0627\u0631", "\u062E\u0648\u0634", "\u063A\u0645", "\u062F\u0644",
      "\u0628\u0627\u062A", "\u0633\u0648\u0686", "\u0633\u0645\u062C\u06BE", "\u0639\u0644\u0645", "\u067E\u0691\u06BE\u0646\u0627",
      "\u0644\u06A9\u06BE\u0646\u0627", "\u0633\u0646\u0646\u0627", "\u062F\u06CC\u06A9\u06BE\u0646\u0627", "\u062C\u0627\u0646\u0627",
      "\u0622\u0646\u0627", "\u062E\u0631\u06CC\u062F\u0646\u0627", "\u0628\u06CC\u0686\u0646\u0627", "\u067E\u06CC\u0633\u06D2",
      "\u0642\u06CC\u0645\u062A", "\u0645\u062F\u062F", "\u0636\u0631\u0648\u0631\u062A", "\u0627\u06C1\u0645", "\u067E\u06C1\u0644\u06D2",
      "\u0628\u0639\u062F", "\u0633\u0627\u0620\u062A", "\u0645\u0646\u0679", "\u06AF\u06BE\u0646\u0679\u06C1", "\u06C1\u0641\u062A\u06C1",
      "\u0645\u06C1\u06CC\u0646\u06C1", "\u0633\u0627\u0644", "\u0627\u0644\u062D\u0645\u062F\u0644\u0644\u06C1", "\u0633\u0628\u062D\u0627\u0646 \u0627\u0644\u0644\u06C1",
      "\u062C\u0632\u0627\u06A9 \u0627\u0644\u0644\u06C1", "\u062E\u0648\u0634 \u0622\u0645\u062F\u06CC\u062F", "\u0645\u06C1\u0631\u0628\u0627\u0646\u06CC"
    )

    private val baseDictionaryAr = listOf(
      "\u0627\u0644\u064A\u0648\u0645", "\u0627\u0644\u062D\u0633\u0627\u0628", "\u0627\u0644\u062F\u0641\u0639",
      "\u0627\u0644\u0631\u0633\u0627\u0644\u0629", "\u0631\u0627\u0628\u0637", "\u0631\u0642\u0645",
      "\u0634\u0643\u0631\u0627", "\u063A\u062F\u0627", "\u0645\u0648\u0639\u062F", "\u0645\u0646 \u0641\u0636\u0644\u0643", "\u0646\u0639\u0645"
    )

    private val indexedBaseDictionaryEn = baseDictionaryEn.groupBy { it[0].lowercaseChar() }
    private val indexedBaseDictionaryUr = baseDictionaryUr.groupBy { it[0] }
    private val indexedBaseDictionaryAr = baseDictionaryAr.groupBy { it[0] }

    private val englishCommonCorrections = mapOf(
      "teh" to "the", "adn" to "and", "dont" to "don't", "cant" to "can't", "wont" to "won't",
      "isnt" to "isn't", "arent" to "aren't", "havent" to "haven't", "hasnt" to "hasn't",
      "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
      "im" to "I'm", "youre" to "you're", "theyre" to "they're", "weve" to "we've",
      "didnt" to "didn't", "doesnt" to "doesn't", "wasnt" to "wasn't", "werent" to "weren't",
      "ill" to "I'll", "youll" to "you'll", "theyll" to "they'll", "well" to "we'll",
      "idk" to "I don't know", "btw" to "by the way", "pls" to "please", "plz" to "please",
      "thx" to "thanks", "ty" to "thank you", "omg" to "Oh my God", "rly" to "really",
      "alot" to "a lot", "tothe" to "to the", "inther" to "in the", "brb" to "be right back",
      "asap" to "as soon as possible", "definately" to "definitely", "seperate" to "separate",
      "tomo" to "tomorrow", "nxt" to "next", "abt" to "about", "msg" to "message",
      "txt" to "text", "pic" to "picture", "phn" to "phone", "sec" to "second",
      "min" to "minute", "yr" to "year", "bday" to "birthday", "recieve" to "receive",
      "adress" to "address", "mesage" to "message", "setings" to "settings",
      "clipbord" to "clipboard", "keybord" to "keyboard", "whatsapp" to "WhatsApp",
      "mindcrafts" to "MindCrafts_AI", "codehub" to "codehubb"
    )

    private val urduCommonCorrections = mapOf(
      "\u0627\u0645\u06CC\u0646" to "\u0622\u0645\u06CC\u0646",
      "\u0627\u067E" to "\u0622\u067E",
      "\u0627\u062C" to "\u0622\u062C",
      "\u0627\u0648\u0627\u0632" to "\u0622\u0648\u0627\u0632",
      "\u0627\u0633\u0627\u0646" to "\u0622\u0633\u0627\u0646",
      "\u0627\u0632\u0627\u062F" to "\u0622\u0632\u0627\u062F",
      "\u0634\u06A9\u0631\u06CC\u0627" to "\u0634\u06A9\u0631\u06CC\u06C1",
      "\u0634\u06A9\u0631\u0626\u06CC\u06C1" to "\u0634\u06A9\u0631\u06CC\u06C1",
      "\u0627\u0646\u0634\u0627\u0621\u0627\u0644\u0644\u06C1" to "\u0627\u0646 \u0634\u0627\u0621 \u0627\u0644\u0644\u06C1",
      "\u0627\u0646\u0634\u0627\u0621 \u0627\u0644\u0644\u06C1" to "\u0627\u0646 \u0634\u0627\u0621 \u0627\u0644\u0644\u06C1",
      "\u0627\u0633\u0644\u0627\u0645 \u0639\u0644\u06CC\u06A9\u0645" to "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u06CC\u06A9\u0645",
      "\u0627\u0633\u0644\u0627\u0645\u0639\u0644\u06CC\u06A9\u0645" to "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u06CC\u06A9\u0645",
      "\u0645\u06CC\u0647\u0631\u0628\u0627\u0646\u06CC" to "\u0645\u06C1\u0631\u0628\u0627\u0646\u06CC"
    )
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

  private fun triggerVibration(view: View? = null) {
    if (!hapticsEnabled) return
    try {
      if (view != null) {
        val ok = view.performHapticFeedback(
          android.view.HapticFeedbackConstants.KEYBOARD_TAP,
          android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        if (ok) return
      }
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(4, 45))
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(4)
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

    currentInputConnection?.finishComposingText()
    voicePartialText = ""
    voiceEndedWithSilence = false
    voiceShouldKeepListening = true
    voiceSilenceCount = 0

    isListening = true
    lastVoiceLevelStatusAt = 0L
    startVoicePulse()
    refreshKeyboard()           // mic button background = indicator
    startVoiceSession("Listening...")
  }

  private fun voiceIntent(): Intent {
    val forceOffline = com.clipvault.mobile.keyboard.OfflineSpeechEngine.isOfflinePreferred(this)
    return com.clipvault.mobile.keyboard.OfflineSpeechEngine.createVoiceIntent(
      packageName,
      layoutMode.voiceLocale,
      forceOffline
    )
  }

  private fun startVoiceSession(message: String) {
    if (!voiceShouldKeepListening) return
    
    releaseSpeechRecognizer()
    try {
      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
        setRecognitionListener(createRecognitionListener())
      }
    } catch (e: Exception) {
      finishVoiceTyping("voice init failed")
      return
    }

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
        val rawText = results
          ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull()?.trim().orEmpty()

        val isUrdu = layoutMode == LayoutMode.UR || layoutMode == LayoutMode.AR
        val spokenText = com.clipvault.mobile.keyboard.OfflineSpeechEngine.normalizeOfflineText(rawText, isUrdu)

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
    if (voicePartialText.isNotEmpty()) {
      connection.deleteSurroundingText(voicePartialText.length, 0)
    }
    connection.commitText(normalizedText, 1)
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
    if (voicePartialText.isNotEmpty()) {
      connection.deleteSurroundingText(voicePartialText.length, 0)
    }
    val suffix = voiceFinalSuffixFor(normalizedText)
    connection.commitText("$normalizedText$suffix", 1)
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
    isListening = false
    voiceEndedWithSilence = false
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
        val beforeCursor = connection.getTextBeforeCursor(1, 0)
        if (beforeCursor != null && beforeCursor.toString() == " ") {
            connection.deleteSurroundingText(1, 0)
        }
        connection.commitText("\n", 1)
        updateStatus("Inserted line break (pause)")
      }
    }
    repeatHandler.postDelayed(voiceLineBreakRunnable!!, 5000L)
  }

  private fun stopVoiceLineBreakTimer() {
    voiceLineBreakRunnable?.let { repeatHandler.removeCallbacks(it) }
    voiceLineBreakRunnable = null
  }

  private fun releaseSpeechRecognizer() {
    try {
      speechRecognizer?.destroy()
    } catch (e: Exception) {}
    speechRecognizer = null
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
    if (isIncognitoMode) return
    try {
      val text = readClipboardText().trim()
      if (text.isBlank() || text == lastClipContent) return

      lastClipContent = text
      if (NativeClipStore.save(this, text)) {
        savedCount += 1
        updateStatus("$savedCount clips saved")

        // ── Real-time clips panel refresh ──────────────────────────────────────
        // Update cached clips immediately so the panel shows the new clip at once.
        // We use the background executor to avoid blocking the main thread.
        backgroundExecutor.execute {
          val updatedClips = NativeClipStore.recent(this, 100)
          val updatedFavs  = NativeClipStore.favorites(this, 30)
          mainHandler.post {
            cachedRecentClips    = updatedClips.take(100)
            cachedFavoriteClips  = updatedFavs
            // Refresh the clips panel immediately if it is currently visible
            if (panelMode == PanelMode.CLIPS) {
              refreshKeyboard()
            }
          }
        }
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
    voiceOverlayTextRef?.text = message
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
      background = Color.parseColor("#080810"),
      surface = Color.parseColor("#0d0d1a"),
      key = Color.parseColor("#181828"),
      specialKey = Color.parseColor("#222238"),
      text = Color.parseColor("#e2e2f0"),
      muted = Color.parseColor("#757599"),
      accent = Color.parseColor("#6366f1"),
      stroke = Color.parseColor("#262640"),
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
    autoCorrectEnabled = prefs.getBoolean("autoCorrectEnabled", true)
    doubleSpacePeriodEnabled = prefs.getBoolean("doubleSpacePeriodEnabled", true)
    systemThemeAutoFollow = prefs.getBoolean("systemThemeAutoFollow", true)

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
      .putBoolean("autoCorrectEnabled", autoCorrectEnabled)
      .putBoolean("doubleSpacePeriodEnabled", doubleSpacePeriodEnabled)
      .putBoolean("systemThemeAutoFollow", systemThemeAutoFollow)
      .apply()
  }

  private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
  }

  // ── Async data refresh (called from onStartInputView — never blocks UI) ──────
  private fun refreshCachedDataAsync() {
    val now = System.currentTimeMillis()

    backgroundExecutor.execute {
      // 1. Always fetch recent + favorites clips instantly without throttling
      val clips = NativeClipStore.recent(this, 100)
      val favs = NativeClipStore.favorites(this, 30)

      mainHandler.post {
        cachedRecentClips = clips.take(100)
        cachedFavoriteClips = favs
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
        
      val pd = try {
        val prefs = getSharedPreferences("clipvault_personal_dict", android.content.Context.MODE_PRIVATE)
        prefs.all.mapNotNull { entry ->
          val count = entry.value as? Int ?: 0
          if (count >= 2) entry.key else null
        }
      } catch (e: Exception) { emptyList() }

      mainHandler.post {
        cachedRecentClips = clips.take(100)
        cachedFavoriteClips = favs
        cachedLearnedWords = learned
        cachedPersonalDictionary = pd
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
      if (isListening) finishVoiceTyping("mic off (15s)")
    }
    mainHandler.postDelayed(voiceAutoStopRunnable!!, 15000L)
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
  private fun emojiCategories(): List<EmojiCategory> {
    return com.clipvault.mobile.keyboard.EmojiCatalog.allCategories(recentEmojis).map {
      EmojiCategory(it.icon, it.emojis)
    }
  }
}


