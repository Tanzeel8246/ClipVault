package com.clipvault.mobile.keyboard

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * SentenceContextEngine — Gboard-level Context-Aware Next-Word & Sentence Prediction Engine.
 * Understands preceding words in the sentence (trigrams, bigrams, conversational context),
 * supports English, Urdu, and Roman Urdu, boosts word completions matching the context,
 * and dynamically learns the user's personal phrasing patterns over time.
 */
object SentenceContextEngine {

    // Dynamic user-learned context pairs (persisted in memory + adaptive learning)
    private val userLearnedBigrams = ConcurrentHashMap<String, MutableList<String>>()

    // ─────────────────────────────────────────────────────────────────────────
    // Pre-trained Conversational Trigrams: (wordN-2 + " " + wordN-1) -> [wordN...]
    // ─────────────────────────────────────────────────────────────────────────
    private val trigramMap: Map<String, List<String>> = mapOf(
        // English Conversational Trigrams
        "how are" to listOf("you", "things", "they", "we"),
        "how is" to listOf("it", "your", "everything", "the"),
        "what are" to listOf("you", "the", "we", "they"),
        "what is" to listOf("the", "your", "this", "that"),
        "where are" to listOf("you", "they", "we"),
        "where is" to listOf("the", "your", "my"),
        "i am" to listOf("doing", "going", "fine", "working", "happy", "here"),
        "i will" to listOf("be", "call", "come", "do", "let", "send"),
        "i want" to listOf("to", "a", "you"),
        "i have" to listOf("a", "been", "done", "no", "to"),
        "i would" to listOf("like", "love", "be", "have"),
        "i hope" to listOf("you", "all", "this"),
        "let me" to listOf("know", "see", "tell", "check", "do"),
        "can you" to listOf("please", "send", "call", "come", "do", "help"),
        "could you" to listOf("please", "send", "help", "do"),
        "would you" to listOf("like", "mind", "please"),
        "thank you" to listOf("so", "very", "much", "for", "again"),
        "thanks for" to listOf("the", "your", "coming", "everything"),
        "see you" to listOf("soon", "tomorrow", "later", "there"),
        "nice to" to listOf("meet", "see", "hear"),
        "good to" to listOf("see", "hear", "know"),
        "take care" to listOf("of", "and", "bye"),
        "call me" to listOf("when", "later", "back", "now"),
        "send me" to listOf("the", "your", "it", "details"),
        "let's" to listOf("go", "do", "meet", "see"),
        "are you" to listOf("okay", "ready", "sure", "coming", "free"),
        "do you" to listOf("have", "know", "want", "think", "need"),
        "did you" to listOf("get", "see", "know", "call", "go"),
        "have a" to listOf("great", "good", "nice", "wonderful"),
        "good morning" to listOf("everyone", "sir", "have", "bro"),
        "good night" to listOf("sweet", "sleep", "take", "all"),

        // Urdu Conversational Trigrams
        "کیا حال" to listOf("ہے", "تھا", "ہیں", "سنائیں"),
        "حال چال" to listOf("کیسا", "سب", "ٹھیک"),
        "آپ کیسے" to listOf("ہیں", "ہو", "تھے"),
        "تم کیسے" to listOf("ہو", "تھے"),
        "میں ٹھیک" to listOf("ہوں", "تھا", "الحمدللہ"),
        "سب ٹھیک" to listOf("ہے", "ہیں", "ہوگا"),
        "اللہ کا" to listOf("شکر", "فضل", "کرم", "حکم", "نام"),
        "اللہ تعالیٰ" to listOf("آپ", "ہم", "سب", "کا", "کی"),
        "بہت بہت" to listOf("شکریہ", "مبارک", "اچھا", "عمدہ"),
        "انشاء اللہ" to listOf("تعالیٰ", "کل", "ضرور", "جلد", "سب"),
        "ماشاء اللہ" to listOf("بہت", "خوب", "سبحان", "پیارا"),
        "جزاک اللہ" to listOf("خیراً", "بہت", "بھائی", "جناب"),
        "السلام علیکم" to listOf("ورحمتہ", "بھائی", "جناب", "کیسے"),
        "وعلیکم السلام" to listOf("ورحمتہ", "کیسے", "جناب"),
        "صبح بخیر" to listOf("آپ", "جناب", "سب", "پیارے"),
        "شب بخیر" to listOf("اپنا", "اللہ"),
        "اپنا خیال" to listOf("رکھنا", "رکھیں", "رکھئے"),
        "دعاؤں میں" to listOf("یاد", "شامل"),
        "یاد رکھیں" to listOf("اللہ", "جناب", "شکریہ"),
        "کب آ" to listOf("رہے", "رہا", "سکتے", "گی"),
        "کہاں ہو" to listOf("آپ", "یار", "بھائی"),
        "کہاں ہیں" to listOf("آپ", "جناب"),
        "بات سن" to listOf("رہے", "لو", "سنو"),
        "کام ہو" to listOf("گیا", "رہا", "جائے", "چکا"),
        "فون کرو" to listOf("مجھے", "ابھی", "جلدی"),
        "فون کریں" to listOf("مجھے", "ابھی", "ضرور"),
        "میسج کریں" to listOf("مجھے", "ضرور", "بعد"),
        "کوئی بات" to listOf("نہیں", "ہے", "ہو"),
        "کوئی مسئلہ" to listOf("نہیں", "ہے"),
        "جلد از" to listOf("جلد", "وقت"),
        "اس وقت" to listOf("کہاں", "میں", "کیا"),
        "کل ملتے" to listOf("ہیں", "گے"),
        "آج رات" to listOf("کو", "ملیں"),

        // Roman Urdu Trigrams
        "kya haal" to listOf("hai", "tha"),
        "kese ho" to listOf("aap", "bhai", "yaar"),
        "main theek" to listOf("hun", "hon", "tha"),
        "allah ka" to listOf("shukar", "fazal", "karam"),
        "bohot bohot" to listOf("shukriya", "mubarak"),
        "insha allah" to listOf("zaroor", "kal", "jald"),
        "mashallah bohot" to listOf("khoob", "acha", "pyara"),
        "kab aao" to listOf("ge", "gy", "ga"),
        "kahan ho" to listOf("aap", "bhai", "yaar", "tum"),
        "call karo" to listOf("mujhe", "abhi", "free"),
        "kam ho" to listOf("gaya", "raha", "chuka"),
        "free ho" to listOf("to", "kya", "bhai"),
        "bat suno" to listOf("meri", "yaar", "ek")
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Pre-trained Conversational Bigrams: wordN-1 -> [wordN...]
    // ─────────────────────────────────────────────────────────────────────────
    private val bigramMap: Map<String, List<String>> = mapOf(
        // English
        "how" to listOf("are", "is", "was", "about", "can"),
        "what" to listOf("are", "is", "about", "time", "do"),
        "where" to listOf("are", "is", "do", "can"),
        "when" to listOf("are", "will", "can", "is"),
        "why" to listOf("are", "not", "is", "did"),
        "who" to listOf("is", "are", "will", "can"),
        "i" to listOf("am", "will", "have", "would", "can", "think", "need"),
        "you" to listOf("are", "can", "will", "have", "know", "need"),
        "we" to listOf("are", "can", "will", "have", "need"),
        "they" to listOf("are", "will", "have", "were"),
        "it" to listOf("is", "was", "will", "would", "looks"),
        "this" to listOf("is", "was", "one", "week"),
        "that" to listOf("is", "was", "would", "will"),
        "please" to listOf("let", "send", "call", "help", "find", "check"),
        "thank" to listOf("you"),
        "thanks" to listOf("for", "a", "lot", "bro"),
        "welcome" to listOf("back", "to", "sir"),
        "sorry" to listOf("for", "about", "to"),
        "good" to listOf("morning", "night", "luck", "job", "idea", "afternoon"),
        "see" to listOf("you", "it", "them"),
        "let" to listOf("me", "us", "it"),
        "call" to listOf("me", "you", "later", "back"),
        "send" to listOf("me", "the", "it", "details"),
        "take" to listOf("care", "it", "time"),
        "tell" to listOf("me", "him", "her", "them"),
        "have" to listOf("a", "to", "been", "done"),
        "make" to listOf("sure", "it", "a"),
        "can" to listOf("you", "i", "we"),
        "will" to listOf("be", "call", "come", "do", "see"),
        "happy" to listOf("birthday", "to", "anniversary", "new"),
        "love" to listOf("you", "it", "this"),
        "best" to listOf("of", "regards", "wishes", "friend"),
        "nice" to listOf("to", "picture", "work"),

        // Urdu
        "کیا" to listOf("حال", "ہے", "ہوا", "بات", "کر رہے", "کرو", "چاہیے"),
        "کیسے" to listOf("ہیں", "ہو", "تھے", "ہوا", "کریں"),
        "کہاں" to listOf("ہو", "ہیں", "گئے", "تھے", "جانا"),
        "کیوں" to listOf("نہیں", "ہوا", "کہا", "پوچھا"),
        "کب" to listOf("آؤ", "تک", "ملیں", "آئیں گے", "ہوگا"),
        "کون" to listOf("ہے", "سا", "تھا", "آیا"),
        "میں" to listOf("ٹھیک", "ہوں", "کل", "آؤں", "نہیں", "نے", "آپ"),
        "آپ" to listOf("کیسے", "کہاں", "کا", "کی", "کو", "نے", "سے", "خیریت"),
        "تم" to listOf("کہاں", "کیسے", "کیا", "کب", "نے", "ہو"),
        "ہم" to listOf("سب", "بھی", "کل", "ٹھیک", "نے", "لوگ"),
        "یہ" to listOf("کیا", "بہت", "کام", "سب", "تو", "ایک", "ہے"),
        "وہ" to listOf("بھی", "کہاں", "کیا", "سب", "گیا", "تھا"),
        "بہت" to listOf("شکریہ", "اچھا", "خوب", "پیارا", "زیادہ", "عمدہ", "مبارک"),
        "شکریہ" to listOf("بہت", "جناب", "بھائی", "آپ", "ادا"),
        "مہربانی" to listOf("ہوگی", "فرما", "کریں", "فرمائیں"),
        "سلام" to listOf("علیکم", "جناب", "بھائی"),
        "السلام" to listOf("علیکم", "ورحمتہ"),
        "وعلیکم" to listOf("السلام"),
        "اللہ" to listOf("حافظ", "کا", "تعالیٰ", "اکبر", "شکر", "کرم", "فضل"),
        "خدا" to listOf("حافظ"),
        "انشاء" to listOf("اللہ"),
        "ماشاء" to listOf("اللہ"),
        "سبحان" to listOf("اللہ"),
        "جزاک" to listOf("اللہ"),
        "مبارک" to listOf("ہو", "باد"),
        "صبح" to listOf("بخیر", "کا", "کو"),
        "شام" to listOf("کو", "بخیر"),
        "شب" to listOf("بخیر"),
        "خیال" to listOf("رکھنا", "رکھیں", "آیا", "ہے"),
        "دعاؤں" to listOf("میں", "کی"),
        "آج" to listOf("کل", "کا", "کی", "رات", "شام", "صبح"),
        "کل" to listOf("آؤں", "ملتے", "کا", "صبح", "شام"),
        "فون" to listOf("کریں", "کرو", "نمبر", "اٹھائیں"),
        "میسج" to listOf("کریں", "کیا", "آیا"),
        "کام" to listOf("ہو", "گیا", "کریں", "کیا", "مکمل"),
        "کوئی" to listOf("بات", "مسئلہ", "شک", "کام"),
        "بات" to listOf("کریں", "سنیں", "کرو", "ہوئی"),
        "ٹھیک" to listOf("ہے", "ہوں", "ہوگا", "تھا"),
        "ضرور" to listOf("کریں", "آئیں", "بتائیں"),
        "رابطہ" to listOf("کریں", "نمبر", "ہوگا"),
        "خیر" to listOf("خیریت", "سے", "ہو"),
        "خدا" to listOf("حافظ"),
        "انشاء" to listOf("اللہ"),
        "ماشاء" to listOf("اللہ"),
        "سبحان" to listOf("اللہ"),
        "جزاک" to listOf("اللہ"),
        "مبارک" to listOf("ہو", "باد"),
        "صبح" to listOf("بخیر"),
        "شب" to listOf("بخیر"),
        "خیال" to listOf("رکھنا", "آیا", "ہے"),
        "دعاؤں" to listOf("میں"),
        "آج" to listOf("کل", "کا", "کی", "رات"),
        "کل" to listOf("آؤں", "ملتے", "کا", "صبح"),
        "فون" to listOf("کریں", "کرو", "نمبر"),
        "کام" to listOf("ہو", "گیا", "کریں", "کیا"),

        // Roman Urdu
        "kya" to listOf("haal", "hai", "hua", "bat", "karen"),
        "kese" to listOf("ho", "hain", "karein"),
        "kahan" to listOf("ho", "hain", "gaye"),
        "kyun" to listOf("nahi", "kya", "hua"),
        "kab" to listOf("aao", "tak", "milen"),
        "main" to listOf("theek", "hun", "hon", "kal", "bhi"),
        "aap" to listOf("kese", "kahan", "ka", "ki"),
        "tum" to listOf("kese", "kahan", "kab"),
        "bohot" to listOf("shukriya", "acha", "khoob", "pyara"),
        "shukriya" to listOf("bhai", "bohot", "janab", "yaar"),
        "salam" to listOf("janab", "bhai", "kese"),
        "allah" to listOf("hafiz", "ka", "shukar"),
        "khuda" to listOf("hafiz"),
        "mubarak" to listOf("ho", "bhai"),
        "call" to listOf("karo", "karen", "me"),
        "pic" to listOf("bhejo", "send", "karo"),
        "theek" to listOf("hai", "ho", "hun")
    )

    // Common sentence starters
    private val startersEn = listOf("I", "How", "What", "Can", "Thank", "Please", "Let", "Good")
    private val startersUr = listOf("میں", "کیا", "آپ", "یہ", "ہم", "السلام", "بہت", "اللہ")

    /**
     * Learns a 2-word sequence committed by the user.
     */
    fun recordWordCommit(previousWord: String, committedWord: String) {
        val prev = previousWord.trim().lowercase()
        val curr = committedWord.trim()
        if (prev.length < 2 || curr.length < 2) return

        val list = userLearnedBigrams.getOrPut(prev) { mutableListOf() }
        list.remove(curr)
        list.add(0, curr)
        if (list.size > 8) {
            list.removeAt(list.size - 1)
        }
    }

    /**
     * Predicts the next word given the text preceding the cursor.
     * Evaluates trigrams first, then bigrams, then user learned patterns.
     */
    fun predictNextWords(contextText: String, maxCount: Int = 3): List<String> {
        val clean = contextText.trimEnd()
        if (clean.isEmpty()) {
            return startersEn.take(maxCount)
        }

        // If ends with punctuation, suggest sentence starters
        val lastChar = clean.last()
        if (lastChar in listOf('.', '?', '!', '۔', '؟', '\n')) {
            val isUrdu = clean.any { it.code in 0x0600..0x06FF }
            return if (isUrdu) startersUr.take(maxCount) else startersEn.take(maxCount)
        }

        val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val last1 = tokens.last().lowercase().trim(',', '.', '!', '?', '،', '۔', '؟')
        val last2 = if (tokens.size >= 2) tokens[tokens.size - 2].lowercase().trim(',', '.', '!', '?', '،', '۔', '؟') else null

        val results = mutableListOf<String>()

        // 1. User-learned personal habits (top priority)
        userLearnedBigrams[last1]?.let {
            results.addAll(it)
        }

        // 2. Trigram matching (word2 + " " + word1)
        if (last2 != null) {
            val trigramKey = "$last2 $last1"
            trigramMap[trigramKey]?.let {
                for (w in it) {
                    if (!results.contains(w)) results.add(w)
                }
            }
        }

        // 3. Bigram matching (word1)
        bigramMap[last1]?.let {
            for (w in it) {
                if (!results.contains(w)) results.add(w)
            }
        }

        return results.distinct().take(maxCount)
    }

    /**
     * Boosts dictionary suggestions with context-aware candidates matching the typed prefix.
     * If user typed "I am " + "w", words like "working", "waiting" get prioritized over "which", "where".
     */
    fun boostWithContext(
        contextText: String,
        prefix: String,
        baseCandidates: List<String>,
        maxTotal: Int = 5
    ): List<String> {
        if (prefix.isBlank()) return baseCandidates.take(maxTotal)
        val pLower = prefix.lowercase()

        val contextPredictions = predictNextWords(contextText, maxCount = 8)
        val boosted = mutableListOf<String>()

        // 1. Context words starting with prefix
        for (w in contextPredictions) {
            if (w.startsWith(pLower, ignoreCase = true) && !w.equals(prefix, ignoreCase = true)) {
                boosted.add(w)
            }
        }

        // 2. Regular dictionary candidates
        for (w in baseCandidates) {
            if (w.startsWith(pLower, ignoreCase = true) && !boosted.contains(w) && !w.equals(prefix, ignoreCase = true)) {
                boosted.add(w)
            }
            if (boosted.size >= maxTotal) break
        }

        return boosted.distinct().take(maxTotal)
    }
}
