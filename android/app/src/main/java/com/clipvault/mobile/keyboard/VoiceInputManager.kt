package com.clipvault.mobile.keyboard

import android.speech.SpeechRecognizer

/**
 * VoiceInputManager — Handles voice punctuation normalization, spelling adjustments,
 * and error translation for English, Urdu, and Arabic.
 */
object VoiceInputManager {

    private val urduVoicePunctuation = mapOf(
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

    private val urduCommonVoiceSpelling = mapOf(
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

    private val englishVoicePunctuation = mapOf(
        "comma" to ",",
        "full stop" to ".",
        "period" to ".",
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "new line" to "\n",
        "newline" to "\n",
        "next line" to "\n",
        "line break" to "\n",
        "new paragraph" to "\n\n",
        "colon" to ":",
        "semicolon" to ";"
    )

    fun normalizeUrduVoiceText(value: String): String {
        var text = value.trim()

        // 1. Voice Punctuation Replacements
        urduVoicePunctuation.forEach { (from, to) ->
            val pattern = Regex("(?i)(^|[\\s\\u060C\\u06D4,.!?؛؟])${Regex.escape(from)}(?=$|[\\s\\u060C\\u06D4,.!?؛؟])")
            text = pattern.replace(text) { match -> "${match.groupValues[1]}$to" }
        }

        // 2. Spelling Corrections
        urduCommonVoiceSpelling.forEach { (bad, good) ->
            val pattern = Regex("(^|[\\s\\u060C\\u06D4,.!?؛؟])${Regex.escape(bad)}(?=$|[\\s\\u060C\\u06D4,.!?؛؟])")
            text = pattern.replace(text) { match -> "${match.groupValues[1]}$good" }
        }

        return text
    }

    fun normalizeEnglishVoiceText(value: String): String {
        var text = value.trim()
        englishVoicePunctuation.forEach { (from, to) ->
            val pattern = Regex("(?i)\\b${Regex.escape(from)}\\b")
            text = pattern.replace(text, to)
        }
        return text
    }

    fun getErrorMessage(error: Int): String {
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
}
