package com.example.data.repository

import com.example.data.model.LanguageMode
import com.example.data.model.LanguageMode.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * ADI Hybrid (built-in) engine: honest rule-based replies that work with zero network and zero model.
 * It can NOT answer open-ended questions - for those it fails so the router can pick Gemini/Llama.
 */
class HybridEngine : AiEngine {
    override val label = "ADI built-in"

    private enum class Rule { GREET, HOW, WHO, TIME, DATE, THANKS, TIRED, CAPS }

    private val rules = listOf(
        Rule.GREET to Regex("^(hi+|hello+|hey+( adi)?|helo|namaste|namaskar|hii+|good (morning|evening|night))[ !.]*$", RegexOption.IGNORE_CASE),
        Rule.HOW to Regex("\\b(how are you|kaise ho|kaisi ho|kya chal raha|kya haal)\\b", RegexOption.IGNORE_CASE),
        Rule.WHO to Regex("\\b(who are you|tum kaun ho|aap kaun ho|about yourself|introduce yourself|tumhara naam)\\b", RegexOption.IGNORE_CASE),
        Rule.TIME to Regex("\\b(what('s| is)? the time|current time|time kya|kitne baje|samay kya)\\b", RegexOption.IGNORE_CASE),
        Rule.DATE to Regex("\\b(today'?s date|what('s| is) the date|aaj ki (date|tareekh)|aaj kya (date|din))\\b", RegexOption.IGNORE_CASE),
        Rule.THANKS to Regex("\\b(thank(s| you)|shukriya|dhanyavad|thanks)\\b", RegexOption.IGNORE_CASE),
        Rule.TIRED to Regex("\\b(thak gaya|thak gayi|tired|i am bored|bore ho raha)\\b", RegexOption.IGNORE_CASE),
        Rule.CAPS to Regex("\\b(what can you do|kya kar sakti|help me with|tum kya kar sakti)\\b", RegexOption.IGNORE_CASE)
    )

    fun canHandle(prompt: String): Boolean = rules.any { it.second.containsMatchIn(prompt.trim()) }

    override suspend fun status() = EngineStatus(true, "Always available (rules only, offline)")

    override suspend fun generate(prompt: String, language: LanguageMode, history: List<ChatTurn>): Result<String> {
        val rule = rules.firstOrNull { it.second.containsMatchIn(prompt.trim()) }?.first
            ?: return Result.failure(EngineException("Built-in engine ke paas iska rule nahi hai"))
        return Result.success(reply(rule, language))
    }

    private fun roman(l: LanguageMode) = l != ENGLISH_IN && l != HINDI

    private fun reply(rule: Rule, l: LanguageMode): String = when (rule) {
        Rule.GREET -> greeting(l)
        Rule.HOW -> when {
            l == ENGLISH_IN -> "I'm doing great, thank you! How is your day going?"
            l == HINDI -> "\u092e\u0948\u0902 \u092c\u093f\u0932\u094d\u0915\u0941\u0932 \u0920\u0940\u0915 \u0939\u0942\u0901! \u0906\u092a \u092c\u0924\u093e\u0907\u090f, \u0906\u091c \u0915\u093e \u0926\u093f\u0928 \u0915\u0948\u0938\u093e \u0930\u0939\u093e?"
            else -> "Main ekdum first class hoon! Aap bataiye, aaj ka din kaisa beeta?"
        }
        Rule.WHO -> when {
            l == ENGLISH_IN -> "I'm ADI AI, your Indian AI companion. I can open apps, search YouTube, draft WhatsApp messages, set alarms, navigate on Maps, and chat in many Indian languages."
            l == HINDI -> "\u092e\u0948\u0902 ADI AI \u0939\u0942\u0901, \u0906\u092a\u0915\u0940 \u0907\u0902\u0921\u093f\u092f\u0928 AI \u0938\u0939\u0947\u0932\u0940\u0964 \u092e\u0948\u0902 \u0910\u092a\u094d\u0938 \u0916\u094b\u0932 \u0938\u0915\u0924\u0940 \u0939\u0942\u0901, YouTube \u0938\u0930\u094d\u091a \u0915\u0930 \u0938\u0915\u0924\u0940 \u0939\u0942\u0901, WhatsApp \u092e\u0948\u0938\u0947\u091c \u0924\u0948\u092f\u093e\u0930 \u0915\u0930 \u0938\u0915\u0924\u0940 \u0939\u0942\u0901 \u0914\u0930 \u0905\u0932\u093e\u0930\u094d\u092e \u0938\u0947\u091f \u0915\u0930 \u0938\u0915\u0924\u0940 \u0939\u0942\u0901\u0964"
            else -> "Main hoon ADI AI, aapki Indian AI saheli! Main apps khol sakti hoon, YouTube search, WhatsApp message tayyar, alarm aur Maps navigation kar sakti hoon."
        }
        Rule.TIME -> {
            val t = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())
            if (l == ENGLISH_IN) "It's $t." else "Abhi $t ho raha hai."
        }
        Rule.DATE -> {
            val d = DateFormat.getDateInstance(DateFormat.FULL, Locale.getDefault()).format(Date())
            if (l == ENGLISH_IN) "Today is $d." else "Aaj $d hai."
        }
        Rule.THANKS -> if (l == ENGLISH_IN) "You're welcome!" else "Koi baat nahi, hamesha haazir hoon!"
        Rule.TIRED -> if (l == ENGLISH_IN) "Take a deep breath - you've worked hard. Want me to play some relaxing music on YouTube?"
            else "Awww, relax karo yaar! Din bhar ki mehnat ke baad aaram banta hai. Kahiye toh koi shaant gaana chala doon?"
        Rule.CAPS -> if (l == ENGLISH_IN) "I can open apps, make calls, draft WhatsApp messages, search YouTube, navigate, set alarms and timers, control volume and flashlight, read your screen on request, and chat when Gemini or Llama is available."
            else "Main apps khol sakti hoon, call, WhatsApp message tayyar, YouTube search, navigation, alarm-timer, volume-torch control, aur kehne par screen padh sakti hoon. Open sawaalon ke liye Gemini ya Llama chahiye."
    }

    private fun greeting(l: LanguageMode): String = when (l) {
        ENGLISH_IN -> "Hello! I'm ADI. How can I help you?"
        HINDI -> "\u0928\u092e\u0938\u094d\u0924\u0947! \u092e\u0948\u0902 ADI \u0939\u0942\u0901\u0964 \u092c\u0924\u093e\u0907\u090f, \u092e\u0948\u0902 \u0915\u094d\u092f\u093e \u0915\u0930\u0942\u0901?"
        BENGALI, ASSAMESE -> "Nomoskar! Ami ADI. Ki sahajyo korte pari?"
        URDU -> "Adaab! Main ADI hoon. Farmayiye kya madad karoon?"
        MALAYALAM, TELUGU -> "Namaskaram! Njan ADI. Enthu sahayam veno?"
        KANNADA -> "Namaskara! Naanu ADI. Enu maadli?"
        PUNJABI -> "Sat sri akaal ji! Main ADI haan. Dasso ki sewa kariye?"
        TAMIL -> "Vanakkam! Naan ADI. Epdi udhavalam?"
        SANTALI -> "Johar! In do ADI kana\u00f1. Chet leka menama?"
        else -> "Namaste! Main ADI hoon. Bataiye, kya madad karoon?"
    }
}
