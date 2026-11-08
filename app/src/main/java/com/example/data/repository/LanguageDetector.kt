package com.example.data.repository

import com.example.data.model.LanguageMode

/** Pure-Kotlin script/marker based detector for per-turn automatic language selection. */
object LanguageDetector {
    private val hinglish = setOf(
        "kya", "hai", "hain", "karo", "kar", "mujhe", "mera", "meri", "aap", "tum", "nahi", "nahin", "chalao",
        "bhai", "batao", "kaise", "kaisi", "kab", "kyun", "kyu", "acha", "accha", "theek", "thik", "haan", "yaar",
        "abhi", "kholo", "bhejo", "dikhao", "raha", "rahi", "wala", "wali", "kuch", "bahut", "ko", "par", "pe",
        "mein", "se", "ka", "ki", "ke", "bajao", "lagao", "rasta", "gaana", "baje", "subah", "shaam", "ghar"
    )
    private val english = setOf(
        "the", "is", "are", "what", "how", "you", "i", "to", "a", "of", "and", "in", "it", "my", "me", "can",
        "please", "open", "call", "play", "send", "set", "tell", "who", "why", "when", "where", "this", "that"
    )
    private val marathiMarks = listOf("\u0906\u0939\u0947", "\u0915\u093e\u092f", "\u0924\u0941\u092e\u094d\u0939\u0940", "\u0915\u0930\u093e", "\u0906\u0923\u093f", "\u092e\u0932\u093e")

    fun detect(text: String, fallback: LanguageMode): LanguageMode {
        var dev = 0; var ben = 0; var asm = 0; var gur = 0; var guj = 0; var odi = 0; var tam = 0
        var tel = 0; var kan = 0; var mal = 0; var ara = 0; var ol = 0; var lat = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x0900..0x097F -> dev++
                c == 0x09F0 || c == 0x09F1 -> { ben++; asm++ }
                c in 0x0980..0x09FF -> ben++
                c in 0x0A00..0x0A7F -> gur++
                c in 0x0A80..0x0AFF -> guj++
                c in 0x0B00..0x0B7F -> odi++
                c in 0x0B80..0x0BFF -> tam++
                c in 0x0C00..0x0C7F -> tel++
                c in 0x0C80..0x0CFF -> kan++
                c in 0x0D00..0x0D7F -> mal++
                c in 0x0600..0x06FF || c in 0x0750..0x077F -> ara++
                c in 0x1C50..0x1C7F -> ol++
                ch.isLetter() && c < 0x250 -> lat++
            }
        }
        val scripts = listOf(
            dev to (if (marathiMarks.any { text.contains(it) }) LanguageMode.MARATHI else LanguageMode.HINDI),
            ben to (if (asm > 0) LanguageMode.ASSAMESE else LanguageMode.BENGALI),
            gur to LanguageMode.PUNJABI, guj to LanguageMode.GUJARATI, odi to LanguageMode.ODIA, tam to LanguageMode.TAMIL,
            tel to LanguageMode.TELUGU, kan to LanguageMode.KANNADA, mal to LanguageMode.MALAYALAM,
            ara to LanguageMode.URDU, ol to LanguageMode.SANTALI
        )
        val best = scripts.maxByOrNull { it.first }!!
        if (best.first > 0 && best.first >= lat) return best.second
        if (lat == 0) return fallback
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val hMarks = words.count { it in hinglish }
        val eMarks = words.count { it in english }
        return when {
            hMarks > 0 && hMarks >= eMarks -> LanguageMode.HINGLISH
            eMarks > 0 -> LanguageMode.ENGLISH_IN
            else -> fallback
        }
    }
}
