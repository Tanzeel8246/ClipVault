package com.clipvault.mobile.keyboard

import java.util.Locale

/**
 * RomanUrduEngine — High-performance Offline Roman Urdu to Nastaleeq Transliteration
 *
 * Converts Latin / Roman Urdu phonetic input (e.g., "shukriya", "kese", "theek")
 * into authentic Urdu script candidates in real time.
 */
object RomanUrduEngine {

    // Common Roman Urdu vocabulary mapped to standard Urdu spellings with frequency rank
    private val dictionary: Map<String, List<String>> = mapOf(
        // Greetings & Respect
        "salam" to listOf("سلام", "السلام علیکم"),
        "assalam" to listOf("السلام", "السلام علیکم"),
        "assalamoalaikum" to listOf("السلام علیکم"),
        "assalamualaykum" to listOf("السلام علیکم"),
        "shukriya" to listOf("شکریہ"),
        "jazakallah" to listOf("جزاک اللہ"),
        "mashaallah" to listOf("ماشاءاللہ"),
        "subhanallah" to listOf("سبحان اللہ"),
        "alhamdulillah" to listOf("الحمدللہ"),
        "inshallah" to listOf("ان شاء اللہ", "انشاءاللہ"),
        "khudahafiz" to listOf("خدا حافظ"),
        "allahhafiz" to listOf("اللہ حافظ"),
        "fiAmanillah" to listOf("فی امان اللہ"),
        "marhaba" to listOf("مرحبا"),
        "khushamdeed" to listOf("خوش آمدید"),

        // Pronouns & Questions
        "main" to listOf("میں"),
        "hum" to listOf("ہم"),
        "ap" to listOf("آپ"),
        "aap" to listOf("آپ"),
        "tum" to listOf("تم"),
        "tu" to listOf("تو"),
        "ye" to listOf("یہ"),
        "yeh" to listOf("یہ"),
        "wo" to listOf("وہ"),
        "woh" to listOf("وہ"),
        "in" to listOf("ان"),
        "un" to listOf("ان", "اُن"),
        "mera" to listOf("میرا"),
        "meri" to listOf("میری"),
        "mere" to listOf("میرے"),
        "apka" to listOf("آپ کا"),
        "apki" to listOf("آپ کی"),
        "apke" to listOf("آپ کے"),
        "aapka" to listOf("آپ کا"),
        "aapki" to listOf("آپ کی"),
        "aapke" to listOf("آپ کے"),
        "tumhara" to listOf("تمہارا"),
        "tumhari" to listOf("تمہاری"),
        "tumhare" to listOf("تمہارے"),
        "uska" to listOf("اس کا"),
        "uski" to listOf("اس کی"),
        "uske" to listOf("اس کے"),
        "iska" to listOf("اس کا"),
        "iski" to listOf("اس کی"),
        "iske" to listOf("اس کے"),
        "kya" to listOf("کیا"),
        "kyun" to listOf("کیوں"),
        "kyu" to listOf("کیوں"),
        "kab" to listOf("کب"),
        "kahan" to listOf("کہاں"),
        "kidhar" to listOf("کدھر"),
        "kaise" to listOf("کیسے"),
        "kese" to listOf("کیسے"),
        "kaisi" to listOf("کیسی"),
        "kesi" to listOf("کیسی"),
        "kaisa" to listOf("کیسا"),
        "kesa" to listOf("کیسا"),
        "kaun" to listOf("کون"),
        "kon" to listOf("کون"),
        "kis" to listOf("کس"),
        "kiska" to listOf("کس کا"),
        "kitna" to listOf("کتنا"),
        "kitni" to listOf("کتنی"),
        "kitne" to listOf("کتنے"),

        // Common Conversational Words
        "theek" to listOf("ٹھیک"),
        "thik" to listOf("ٹھیک"),
        "haan" to listOf("ہاں"),
        "han" to listOf("ہاں"),
        "nahi" to listOf("نہیں"),
        "nahin" to listOf("نہیں"),
        "na" to listOf("نہ", "نا"),
        "ji" to listOf("جی"),
        "jee" to listOf("جی"),
        "acha" to listOf("اچھا"),
        "achha" to listOf("اچھا"),
        "achi" to listOf("اچھی"),
        "achhi" to listOf("اچھی"),
        "ache" to listOf("اچھے"),
        "achhe" to listOf("اچھے"),
        "bura" to listOf("برا"),
        "buri" to listOf("بری"),
        "bure" to listOf("برے"),
        "bohot" to listOf("بہت"),
        "boht" to listOf("بہت"),
        "bahut" to listOf("بہت"),
        "ziyada" to listOf("زیادہ"),
        "zyada" to listOf("زیادہ"),
        "kam" to listOf("کم"),
        "sab" to listOf("سب"),
        "kuch" to listOf("کچھ"),
        "kuchh" to listOf("کچھ"),
        "koi" to listOf("کوئی"),
        "kisi" to listOf("کسی"),
        "har" to listOf("ہر"),
        "aur" to listOf("اور"),
        "ya" to listOf("یا"),
        "lekin" to listOf("لیکن"),
        "magar" to listOf("مگر"),
        "par" to listOf("پر"),
        "pe" to listOf("پہ", "پر"),
        "se" to listOf("سے"),
        "ko" to listOf("کو"),
        "ka" to listOf("کا"),
        "ki" to listOf("کی"),
        "ke" to listOf("کے"),
        "ne" to listOf("نے"),
        "tak" to listOf("تک"),
        "bhi" to listOf("بھی"),
        "to" to listOf("تو"),
        "toh" to listOf("تو"),
        "balke" to listOf("بلکہ"),
        "sirf" to listOf("صرف"),
        "hamesha" to listOf("ہمیشہ"),
        "kabhi" to listOf("کبھی"),

        // Verbs & Actions
        "hai" to listOf("ہے"),
        "he" to listOf("ہے"),
        "hain" to listOf("ہیں"),
        "hen" to listOf("ہیں"),
        "ho" to listOf("ہو"),
        "tha" to listOf("تھا"),
        "thi" to listOf("تھی"),
        "the" to listOf("تھے"),
        "hoga" to listOf("ہوگا"),
        "hogi" to listOf("ہوگی"),
        "honge" to listOf("ہوں گے"),
        "karna" to listOf("کرنا"),
        "karo" to listOf("کرو"),
        "karen" to listOf("کریں"),
        "karein" to listOf("کریں"),
        "karta" to listOf("کرتا"),
        "karti" to listOf("کرتی"),
        "karte" to listOf("کرتے"),
        "kiya" to listOf("کیا"),
        "kia" to listOf("کیا"),
        "jana" to listOf("جانا"),
        "jao" to listOf("جاؤ"),
        "jaen" to listOf("جائیں"),
        "gaya" to listOf("گیا"),
        "gaye" to listOf("گئے"),
        "gayi" to listOf("گئی"),
        "aana" to listOf("آنا"),
        "ana" to listOf("آنا"),
        "aao" to listOf("آؤ"),
        "aaye" to listOf("آئے"),
        "aayi" to listOf("آئی"),
        "aaya" to listOf("آیا"),
        "dekhna" to listOf("دیکھنا"),
        "dekho" to listOf("دیکھو"),
        "dekha" to listOf("دیکھا"),
        "sunna" to listOf("سننا"),
        "suno" to listOf("سنو"),
        "suna" to listOf("سنا"),
        "bolna" to listOf("بولنا"),
        "bolo" to listOf("بولو"),
        "bola" to listOf("بولا"),
        "kehna" to listOf("کہنا"),
        "kaho" to listOf("کہو"),
        "kaha" to listOf("کہا"),
        "batana" to listOf("بتانا"),
        "batao" to listOf("بتاؤ"),
        "bataya" to listOf("بتایا"),
        "samajh" to listOf("سمجھ"),
        "samjho" to listOf("سمجھو"),
        "samjha" to listOf("سمجھا"),
        "sochna" to listOf("سوچنا"),
        "socho" to listOf("سوچو"),
        "socha" to listOf("سوچا"),
        "likhna" to listOf("لکھنا"),
        "likho" to listOf("لکھو"),
        "likha" to listOf("لکھا"),
        "parhna" to listOf("پڑھنا"),
        "parho" to listOf("پڑھو"),
        "parha" to listOf("پڑھا"),
        "dena" to listOf("دینا"),
        "do" to listOf("دو"),
        "de" to listOf("دے"),
        "diya" to listOf("دیا"),
        "lena" to listOf("لینا"),
        "lo" to listOf("لو"),
        "le" to listOf("لے"),
        "liya" to listOf("لیا"),
        "bhejna" to listOf("بھیجنا"),
        "bhejo" to listOf("بھیجو"),
        "bheja" to listOf("بھیجا"),

        // Time & Day
        "aaj" to listOf("آج"),
        "aj" to listOf("آج"),
        "kal" to listOf("کل"),
        "parso" to listOf("پرسوں"),
        "subah" to listOf("صبح"),
        "subha" to listOf("صبح"),
        "dopahar" to listOf("دوپہر"),
        "sham" to listOf("شام"),
        "raat" to listOf("رات"),
        "din" to listOf("دن"),
        "waqt" to listOf("وقت"),
        "abhi" to listOf("ابھی"),
        "baad" to listOf("بعد"),
        "pehle" to listOf("پہلے"),
        "roz" to listOf("روز"),
        "hafta" to listOf("ہفتہ"),
        "mahina" to listOf("مہینہ"),
        "saal" to listOf("سال"),

        // Relationships & Places
        "bhai" to listOf("بھائی"),
        "behan" to listOf("بہن"),
        "ammi" to listOf("امی"),
        "abbu" to listOf("ابو"),
        "walid" to listOf("والد"),
        "walida" to listOf("والدہ"),
        "dost" to listOf("دوست"),
        "yaar" to listOf("یار"),
        "ghar" to listOf("گھر"),
        "kamra" to listOf("کمرہ"),
        "daftar" to listOf("دفتر"),
        "bazar" to listOf("بازار"),
        "masjid" to listOf("مسجد"),
        "shehar" to listOf("شہر"),
        "mulk" to listOf("ملک"),
        "pakistan" to listOf("پاکستان"),

        // Common Tech & Daily Objects
        "khairiyat" to listOf("خیریت"),
        "pareshan" to listOf("پریشان"),
        "khushi" to listOf("خوشی"),
        "zaroor" to listOf("ضرور"),
        "zaroori" to listOf("ضروری"),
        "mushkil" to listOf("مشکل"),
        "asan" to listOf("آسان"),
        "paise" to listOf("پیسے"),
        "paisa" to listOf("پیسہ"),
        "kam" to listOf("کام"),
        "cheez" to listOf("چیز"),
        "nam" to listOf("نام"),
        "naam" to listOf("نام"),
        "pata" to listOf("پتہ"),
        "number" to listOf("نمبر"),
        "fone" to listOf("فون"),
        "phone" to listOf("فون"),
        "paani" to listOf("پانی"),
        "pani" to listOf("پانی"),
        "khana" to listOf("کھانا"),
        "chaye" to listOf("چائے"),
        "chai" to listOf("چائے")
    )

    // Phonetic mapping rules for fallback transliteration
    private val phoneticMultiChar = listOf(
        "kh" to "کھ",
        "gh" to "غ",
        "ch" to "چ",
        "sh" to "ش",
        "th" to "تھ",
        "ph" to "پھ",
        "bh" to "بھ",
        "jh" to "جھ",
        "dh" to "دھ",
        "rh" to "ڑھ",
        "zh" to "ژ",
        "aa" to "آ",
        "ee" to "ی",
        "oo" to "و",
        "ai" to "ائے",
        "au" to "اؤ"
    )

    private val phoneticSingleChar = mapOf(
        'a' to "ا",
        'b' to "ب",
        'p' to "پ",
        't' to "ت",
        's' to "س",
        'j' to "ج",
        'c' to "چ",
        'h' to "ہ",
        'd' to "د",
        'r' to "ر",
        'z' to "ز",
        'x' to "کس",
        'f' to "ف",
        'q' to "ق",
        'k' to "ک",
        'g' to "گ",
        'l' to "ل",
        'm' to "م",
        'n' to "ن",
        'v' to "و",
        'w' to "و",
        'y' to "ی",
        'e' to "ے",
        'i' to "ی",
        'o' to "و",
        'u' to "و"
    )

    /**
     * Transliterates a Roman Urdu token into authentic Urdu script suggestions.
     */
    fun transliterate(token: String): List<String> {
        val clean = token.lowercase(Locale.ROOT).trim()
        if (clean.isBlank()) return emptyList()

        // 1. Direct dictionary exact match
        dictionary[clean]?.let { return it }

        // 2. Prefix / partial dictionary matches
        val partialMatches = dictionary.keys
            .filter { it.startsWith(clean) && it != clean }
            .take(2)
            .mapNotNull { dictionary[it]?.firstOrNull() }

        // 3. Fallback rule-based phonetic transliteration
        val phoneticResult = fallbackPhonetic(clean)

        val results = mutableListOf<String>()
        if (phoneticResult.isNotBlank()) {
            results.add(phoneticResult)
        }
        results.addAll(partialMatches)

        return results.distinct()
    }

    /**
     * Converts unknown Roman string via phonetic consonant/vowel rules
     */
    private fun fallbackPhonetic(input: String): String {
        var str = input
        val sb = StringBuilder()

        var i = 0
        while (i < str.length) {
            var matched = false
            // Check 2-character phonetic pairs
            if (i + 1 < str.length) {
                val pair = str.substring(i, i + 2)
                val mappedPair = phoneticMultiChar.firstOrNull { it.first == pair }
                if (mappedPair != null) {
                    sb.append(mappedPair.second)
                    i += 2
                    matched = true
                }
            }

            if (!matched) {
                val c = str[i]
                val single = phoneticSingleChar[c]
                if (single != null) {
                    // If 'a' is at start of word, use Alif
                    if (i == 0 && c == 'a') {
                        sb.append("آ")
                    } else {
                        sb.append(single)
                    }
                } else {
                    sb.append(c)
                }
                i += 1
            }
        }

        return sb.toString()
    }
}
