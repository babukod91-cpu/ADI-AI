package com.example

import com.example.data.model.LanguageMode
import com.example.data.repository.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {
    @Test fun scripts() {
        assertEquals(LanguageMode.HINDI, LanguageDetector.detect("\u092f\u0939 \u0915\u094d\u092f\u093e \u0939\u0948", LanguageMode.ENGLISH_IN))
        assertEquals(LanguageMode.BENGALI, LanguageDetector.detect("\u0986\u09ae\u09bf \u09ad\u09be\u09b2\u09cb", LanguageMode.ENGLISH_IN))
        assertEquals(LanguageMode.TAMIL, LanguageDetector.detect("\u0bb5\u0ba3\u0b95\u0bcd\u0b95\u0bae\u0bcd", LanguageMode.ENGLISH_IN))
        assertEquals(LanguageMode.SANTALI, LanguageDetector.detect("\u1c65\u1c5f\u1c71\u1c5b\u1c5f\u1c72\u1c64", LanguageMode.ENGLISH_IN))
    }
    @Test fun latin() {
        assertEquals(LanguageMode.HINGLISH, LanguageDetector.detect("mujhe gaana chalao", LanguageMode.ENGLISH_IN))
        assertEquals(LanguageMode.ENGLISH_IN, LanguageDetector.detect("what is the weather", LanguageMode.HINDI))
        assertEquals(LanguageMode.HINDI, LanguageDetector.detect("123", LanguageMode.HINDI))
    }
}
