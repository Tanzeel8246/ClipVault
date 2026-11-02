package com.clipvault.mobile.keyboard

/**
 * SmartReplyEngine — Context-Aware Instant Smart Reply Chips for incoming messages.
 * Analyzes incoming or copied messages in Urdu, English, and Roman Urdu,
 * extracting user intent (greetings, location, wellness, availability, gratitude)
 * and generating 3 instant, one-tap reply suggestions on the keyboard strip.
 */
object SmartReplyEngine {

    data class SmartReplies(val chip1: String, val chip2: String, val chip3: String) {
        fun toList(): List<String> = listOf(chip1, chip2, chip3)
    }

    /**
     * Determines whether the given text looks like an incoming conversational message or question.
     */
    fun isIncomingMessage(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 3 || clean.length > 300) return false
        val lower = clean.lowercase()

        val patterns = listOf(
            "?", "؟", "kahan", "kidhar", "where", "kese", "kaisa", "kaisi", "how", "haal",
            "free", "busy", "salam", "slam", "hello", "hi", "hey", "thanks", "thank",
            "shukriya", "kab", "when", "call", "ok", "theek", "کہاں", "کیسے", "کب",
            "سلام", "شکریہ", "حال", "فری", "ٹھیک"
        )
        return patterns.any { lower.contains(it) }
    }

    /**
     * Generates 3 intelligent smart replies based on the input text.
     */
    fun generateReplies(text: String): SmartReplies? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase()
        val isUrdu = clean.any { it.code in 0x0600..0x06FF }

        // 1. Location / Where questions
        if (lower.contains("kahan") || lower.contains("kidhar") || lower.contains("where") ||
            clean.contains("کہاں") || clean.contains("کدھر")
        ) {
            return if (isUrdu) {
                SmartReplies("میں راستے میں ہوں", "گھر پر ہوں", "آ رہا ہوں")
            } else if (lower.contains("where")) {
                SmartReplies("On my way", "At home", "Almost there")
            } else {
                SmartReplies("Raste mein hun", "Ghar par hun", "Aa raha hun")
            }
        }

        // 2. Wellness / Status / How are you
        if (lower.contains("kese") || lower.contains("kaisa") || lower.contains("kya haal") ||
            lower.contains("how are") || lower.contains("how r") ||
            clean.contains("کیسے") || clean.contains("کیا حال") || clean.contains("خیریت")
        ) {
            return if (isUrdu) {
                SmartReplies("الحمدللہ ٹھیک ہوں", "میں خیریت سے ہوں", "سب شکر ہے")
            } else if (lower.contains("how")) {
                SmartReplies("I'm good, thanks!", "Doing great", "All good!")
            } else {
                SmartReplies("Alhamdulillah theek hun", "Sab theek hai", "Kheriyat hai")
            }
        }

        // 3. Availability / Free?
        if (lower.contains("free") || lower.contains("busy") || clean.contains("فری") || clean.contains("مصروف")) {
            return if (isUrdu) {
                SmartReplies("جی بالکل، بتائیں", "تھوڑی دیر میں بات کرتا ہوں", "جی فری ہوں")
            } else if (lower.contains("free") && !lower.contains("ho")) {
                SmartReplies("Yes, tell me!", "A bit busy right now", "Free now")
            } else {
                SmartReplies("Ji bilkul, batain", "Thori der mein bat karta hun", "Ji free hun")
            }
        }

        // 4. Greetings
        if (lower.contains("salam") || lower.contains("assalam") || clean.contains("سلام") || clean.contains("السلام")) {
            return if (isUrdu) {
                SmartReplies("وعلیکم السلام! کیا حال ہے؟", "وعلیکم السلام بھائی", "وعلیکم السلام جناب")
            } else {
                SmartReplies("Walaikum Assalam, kese ho?", "Walaikum Assalam bhai", "Walaikum Assalam")
            }
        }

        // 5. English greetings
        if (lower.startsWith("hi") || lower.startsWith("hello") || lower.startsWith("hey")) {
            return SmartReplies("Hello! How are you?", "Hey there!", "Hi! What's up?")
        }

        // 6. Gratitude / Thanks
        if (lower.contains("thank") || lower.contains("shukriya") || clean.contains("شکریہ") || clean.contains("جزاک")) {
            return if (isUrdu) {
                SmartReplies("خوش رہیں", "جزاک اللہ خیراً", "کوئی بات نہیں")
            } else if (lower.contains("thank")) {
                SmartReplies("You're welcome!", "Anytime!", "No problem!")
            } else {
                SmartReplies("Khush rahain", "JazakAllah", "Koi bat nahi")
            }
        }

        // 7. Time / When
        if (lower.contains("kab") || lower.contains("when") || clean.contains("کب")) {
            return if (isUrdu) {
                SmartReplies("انشاء اللہ جلد", "تھوڑی دیر تک", "شام کو ملتے ہیں")
            } else if (lower.contains("when")) {
                SmartReplies("Very soon", "In a little bit", "Let's meet later")
            } else {
                SmartReplies("InshaAllah jald", "Thori der tak", "Sham ko")
            }
        }

        // 8. General confirmation / agreement
        if (lower.contains("ok") || lower.contains("theek") || clean.contains("ٹھیک") || clean.contains("ڈن")) {
            return if (isUrdu) {
                SmartReplies("جی ٹھیک ہے", "بہترین", "اوکے ڈن")
            } else {
                SmartReplies("Great!", "Sounds good!", "Perfect")
            }
        }

        return null
    }
}
