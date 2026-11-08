package com.example.util

/**
 * Android has no Santali TTS voice. This transliterates Ol Chiki to a Roman approximation so an Indian
 * voice can still read it. APPROXIMATE - not linguistically perfect; a real sat-IN voice would be better.
 */
object SantaliPhonetics {
    private val map = mapOf(
        '\u1C5A' to "a", '\u1C5B' to "t", '\u1C5C' to "g", '\u1C5D' to "ng", '\u1C5E' to "l", '\u1C5F' to "aa",
        '\u1C60' to "k", '\u1C61' to "j", '\u1C62' to "m", '\u1C63' to "w", '\u1C64' to "i", '\u1C65' to "s",
        '\u1C66' to "h", '\u1C67' to "ny", '\u1C68' to "r", '\u1C69' to "u", '\u1C6A' to "ch", '\u1C6B' to "d",
        '\u1C6C' to "n", '\u1C6D' to "y", '\u1C6E' to "e", '\u1C6F' to "p", '\u1C70' to "d", '\u1C71' to "n",
        '\u1C72' to "r", '\u1C73' to "o", '\u1C74' to "t", '\u1C75' to "b", '\u1C76' to "v", '\u1C77' to "h",
        '\u1C7E' to ".", '\u1C7F' to "."
    )
    fun hasOlChiki(s: String) = s.any { it.code in 0x1C50..0x1C7F }
    fun toRoman(s: String): String = buildString {
        for (ch in s) when {
            ch.code in 0x1C50..0x1C59 -> append(ch.code - 0x1C50)
            ch.code in 0x1C78..0x1C7D -> {}
            else -> append(map[ch] ?: ch)
        }
    }
}
