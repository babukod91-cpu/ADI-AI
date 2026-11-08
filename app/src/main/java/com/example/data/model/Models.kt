package com.example.data.model

import android.graphics.Bitmap

/** AUTO is new in v1.1: the router picks the best available brain per question. */
enum class AiEngineMode(val displayName: String, val subtitle: String, val badge: String) {
    AUTO("Auto (best brain)", "ADI picks Gemini, Llama or the built-in engine for every question", "Smart"),
    ADI_HYBRID("ADI Hybrid Engine", "Built-in rules + real app control, works fully offline", "Built-in"),
    LLAMA_3_2_3B("Meta Llama 3.2 3B Instruct", "On-device local LLM for offline reasoning & privacy", "Local"),
    GEMINI_CLOUD("Gemini Cloud AI", "Cloud inference (needs internet + your API key)", "Cloud")
}

enum class AssistantMode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

enum class AvatarEmotion(val emoji: String, val label: String) {
    FRIENDLY("\u2728", "Friendly"), CHEERFUL("\uD83D\uDC96", "Cheerful"), CARING("\uD83C\uDF38", "Caring"),
    PLAYFUL("\uD83D\uDE09", "Playful"), LISTENING("\uD83D\uDC42", "Listening"),
    THINKING("\uD83D\uDCAD", "Thinking"), SPEAKING("\uD83D\uDCAC", "Speaking")
}

enum class LanguageMode(
    val code: String, val displayName: String, val nativeName: String,
    val localeTag: String, val ttsLanguage: String, val scriptSupport: String
) {
    HINGLISH("hinglish", "Hinglish (Mix)", "Hinglish", "hi-IN", "hi", "Latin / Devanagari"),
    HINDI("hi", "Hindi", "\u0939\u093f\u0928\u094d\u0926\u0940", "hi-IN", "hi", "Devanagari"),
    ENGLISH_IN("en_in", "English (India)", "English", "en-IN", "en", "Latin"),
    BENGALI("bn", "Bengali", "\u09ac\u09be\u0982\u09b2\u09be", "bn-IN", "bn", "Bengali"),
    SANTALI("sat", "Santali", "\u1c65\u1c5f\u1c71\u1c5b\u1c5f\u1c72\u1c64 (Santali)", "sat-IN", "sat", "Ol Chiki / Roman / Devanagari"),
    ODIA("or", "Odia", "\u0b13\u0b21\u0b3c\u0b3f\u0b06", "or-IN", "or", "Odia"),
    ASSAMESE("as", "Assamese", "\u0985\u09b8\u09ae\u09c0\u09af\u09bc\u09be", "as-IN", "as", "Bengali-Assamese"),
    TAMIL("ta", "Tamil", "\u0ba4\u0bae\u0bbf\u0bb4\u0bcd", "ta-IN", "ta", "Tamil"),
    TELUGU("te", "Telugu", "\u0c24\u0c46\u0c32\u0c41\u0c17\u0c41", "te-IN", "te", "Telugu"),
    MARATHI("mr", "Marathi", "\u092e\u0930\u093e\u0920\u0940", "mr-IN", "mr", "Devanagari"),
    GUJARATI("gu", "Gujarati", "\u0a97\u0ac1\u0a9c\u0ab0\u0abe\u0aa4\u0ac0", "gu-IN", "gu", "Gujarati"),
    PUNJABI("pa", "Punjabi", "\u0a2a\u0a70\u0a1c\u0a3e\u0a2c\u0a40", "pa-IN", "pa", "Gurmukhi"),
    KANNADA("kn", "Kannada", "\u0c95\u0ca8\u0ccd\u0ca8\u0ca1", "kn-IN", "kn", "Kannada"),
    MALAYALAM("ml", "Malayalam", "\u0d2e\u0d32\u0d2f\u0d3e\u0d33\u0d02", "ml-IN", "ml", "Malayalam"),
    URDU("ur", "Urdu", "\u0627\u0631\u062f\u0648", "ur-IN", "ur", "Perso-Arabic");

    companion object {
        fun fromCode(code: String?): LanguageMode = entries.firstOrNull { it.code == code } ?: HINGLISH
    }
}

enum class LlamaState { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, READY, ERROR }

data class LlamaModelStatus(
    val isSupportedOnDevice: Boolean = true,
    val totalRamGb: Float = 0f,
    val availableRamGb: Float = 0f,
    val freeStorageGb: Float = 0f,
    val state: LlamaState = LlamaState.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val modelFileName: String = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
    val modelSizeMb: Int = 2020,
    val errorMessage: String? = null
)

data class VoiceEngineStatus(
    val language: LanguageMode,
    val isNativeTtsSupported: Boolean,
    val engineNote: String,
    val activeVoiceName: String
)

enum class SystemActionType {
    BACK, HOME, RECENTS, NOTIFICATIONS, OPEN_SETTINGS, OPEN_CAMERA, OPEN_GALLERY,
    VOLUME_UP, VOLUME_DOWN, FLASHLIGHT,
    SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT,
    MEDIA_PAUSE, MEDIA_PLAY, MEDIA_NEXT
}

sealed class AssistantAction {
    data class OpenApp(val appName: String, val packageName: String? = null) : AssistantAction()
    data class SetTimer(val seconds: Int, val label: String) : AssistantAction()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : AssistantAction()
    data class MakePhoneCall(val target: String, val displayName: String? = null) : AssistantAction()
    data class AnswerText(val message: String, val speechText: String = message) : AssistantAction()
    data class SendWhatsApp(val contactOrPhone: String, val message: String, val displayName: String? = null) : AssistantAction()
    data class SearchYouTube(val query: String, val autoPlay: Boolean = false) : AssistantAction()
    data class PlaySpotify(val query: String) : AssistantAction()
    data class SystemControl(val actionType: SystemActionType) : AssistantAction()
    data class NavigateMaps(val destination: String) : AssistantAction()
    data class Tap(val label: String) : AssistantAction()
    data class AskScreen(val question: String) : AssistantAction()
    object OpenCameraAI : AssistantAction()
}

data class PendingConfirmation(
    val title: String,
    val details: String,
    val actionType: String,
    val actionToExecute: AssistantAction
)

data class ContactMatch(val name: String, val number: String)
data class ContactChoice(val query: String, val matches: List<ContactMatch>, val action: AssistantAction)

data class VisionScanResult(
    val title: String,
    val category: String,
    val confidence: Float,
    val extractedText: String,
    val detailedExplanation: String,
    val keyAttributes: List<Pair<String, String>>,
    val suggestedActions: List<String>,
    val thumbnailBitmap: Bitmap? = null,
    val source: String = ""
)

data class VoiceSettings(
    val language: LanguageMode = LanguageMode.HINGLISH,
    val engineMode: AiEngineMode = AiEngineMode.AUTO,
    val wakeWordEnabled: Boolean = false,
    val wakeWord: String = "hey adi",
    val continuousMode: Boolean = false,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.1f,
    val autoLanguage: Boolean = true,
    val preferOnDevice: Boolean = false,
    val geminiModel: String = "gemini-2.5-flash",
    val themeMode: Int = 0,          // 0 system, 1 light, 2 dark
    val screenConsent: Boolean = false,
    val floatingOrb: Boolean = false
)

data class AssistantResult(
    val text: String,
    val speech: String = text,
    val action: AssistantAction? = null,
    val engine: String = "",
    val ok: Boolean = true
)
