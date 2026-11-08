package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.data.model.AssistantMode
import com.example.data.model.LanguageMode
import com.example.data.model.VoiceEngineStatus
import com.example.util.SantaliPhonetics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Speech-to-text (Android SpeechRecognizer, on-device when offline and supported), text-to-speech with Indian voice
 * selection, continuous/wake-word listen loops, error recovery and explicit LISTENING/THINKING/SPEAKING states.
 * All recogniser/TTS calls are marshalled onto the main thread.
 */
class VoiceManager(private val app: Context) {
    private val TAG = "VoiceManager"
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _mode = MutableStateFlow(AssistantMode.IDLE); val mode: StateFlow<AssistantMode> = _mode
    private val _partial = MutableStateFlow(""); val partial: StateFlow<String> = _partial
    private val _level = MutableStateFlow(0f); val level: StateFlow<Float> = _level
    private val _status = MutableStateFlow(VoiceEngineStatus(LanguageMode.HINGLISH, false, "Initializing Indian voice synthesis...", ""))
    val status: StateFlow<VoiceEngineStatus> = _status
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8); val errors: SharedFlow<String> = _errors

    /** Final recognised command text (wake word already stripped). Set by AssistantController. */
    @Volatile var onFinalText: (String) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Triple<String, LanguageMode, () -> Unit>? = null
    private var doneCallback: () -> Unit = {}
    private var finalUtteranceId = ""
    private var focusRequest: AudioFocusRequest? = null

    private var lang = LanguageMode.HINGLISH
    private var rate = 1f; private var pitch = 1.1f
    private var wakeEnabled = false; private var wakeWord = "hey adi"
    private var awaitingCommand = false
    private var listeningRequested = false
    private var keepLoop = false
    private var wantOffline = false
    private var forceOffline = false
    private var retry = 0
    private var langFallbackUsed = false
    private var localeOverride: String? = null

    fun configure(language: LanguageMode, speechRate: Float, speechPitch: Float, wakeOn: Boolean, wake: String) = main.post {
        val changed = language != lang
        lang = language; rate = speechRate; pitch = speechPitch; wakeEnabled = wakeOn; wakeWord = wake.lowercase().trim().ifEmpty { "hey adi" }
        localeOverride = null; langFallbackUsed = false
        if (ttsReady) { if (changed) applyVoice(language) else { tts?.setSpeechRate(rate); tts?.setPitch(pitch) } }
    }

    // ---------------------------------------------------------------- listening

    fun startListening(language: LanguageMode, offline: Boolean, loop: Boolean = false) = main.post {
        lang = language; wantOffline = offline; keepLoop = loop; listeningRequested = true; retry = 0; awaitingCommand = false
        doStart()
    }

    fun stopListening() = main.post {
        listeningRequested = false; keepLoop = false; awaitingCommand = false
        destroyRecognizer(); _partial.value = ""; _level.value = 0f
        if (_mode.value == AssistantMode.LISTENING) _mode.value = AssistantMode.IDLE
    }

    fun setThinking() = main.post { _mode.value = AssistantMode.THINKING }
    fun setIdle() = main.post { if (_mode.value != AssistantMode.SPEAKING && _mode.value != AssistantMode.LISTENING) _mode.value = AssistantMode.IDLE }

    private fun recLocale(): String = localeOverride ?: when (lang) {
        LanguageMode.SANTALI -> "hi-IN"            // no sat-IN recogniser on Android; Devanagari fallback
        else -> lang.localeTag
    }

    private fun doStart() {
        if (!listeningRequested) return
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            fail("Speech recognition service is phone par nahi hai (Google app / Speech Services install karein)"); return
        }
        stopSpeakingInternal()
        destroyRecognizer()
        val offline = wantOffline || forceOffline
        recognizer = if (Build.VERSION.SDK_INT >= 33 && offline && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
        recognizer?.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLocale())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            if (Build.VERSION.SDK_INT >= 33 && (lang == LanguageMode.HINDI || lang == LanguageMode.HINGLISH))
                putExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, arrayOf("en-IN"))
        }
        try { recognizer?.startListening(i) } catch (e: Exception) { Log.w(TAG, "startListening", e); retryOrFail(-1, 600) }
    }

    private fun destroyRecognizer() { runCatching { recognizer?.cancel(); recognizer?.destroy() }; recognizer = null }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { _mode.value = AssistantMode.LISTENING }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) { _level.value = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { _level.value = 0f }
        override fun onError(error: Int) { handleError(error) }
        override fun onResults(b: Bundle?) {
            handleResult(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty())
        }
        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { _partial.value = it }
        }
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    private fun handleResult(textIn: String) {
        retry = 0; _partial.value = ""; _level.value = 0f
        var text = textIn
        if (text.isBlank()) { restartIfLoop(); return }
        if (wakeEnabled && !awaitingCommand) {
            val rest = stripWake(text)
            if (rest == null) { restartIfLoop(); return }            // not for ADI - ignore silently
            if (rest.isBlank()) { awaitingCommand = true; beep(); doStart(); return }
            text = rest
        }
        awaitingCommand = false
        _mode.value = AssistantMode.THINKING
        onFinalText(text)
    }

    private fun restartIfLoop() {
        if (keepLoop && listeningRequested) restartLater(350) else { _mode.value = AssistantMode.IDLE; listeningRequested = false }
    }

    private fun restartLater(ms: Long) = main.postDelayed({ if (listeningRequested) doStart() }, ms)

    private fun handleError(code: Int) {
        _level.value = 0f
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { awaitingCommand = false; restartIfLoop() }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { keepLoop = false; listeningRequested = false; fail("Microphone permission chahiye") }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, 11 -> {
                if (!forceOffline) { forceOffline = true; retryOrFail(code, 300) } else fail("Recognition server/network problem")
            }
            12, 13 -> if (!langFallbackUsed) {
                langFallbackUsed = true; localeOverride = "en-IN"
                _errors.tryEmit("Is language ki voice recognition available nahi - English (India) use kar rahi hoon")
                restartLater(200)
            } else fail("Is language ke liye speech recognition available nahi hai")
            else -> retryOrFail(code, if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 700 else 600)
        }
    }

    private fun retryOrFail(code: Int, delayMs: Long) {
        if (retry++ < 3) { destroyRecognizer(); restartLater(delayMs) }
        else { retry = 0; fail("Voice recognition baar baar fail ho rahi hai (error $code)") }
    }

    private fun fail(msg: String) {
        listeningRequested = false; keepLoop = false; awaitingCommand = false; destroyRecognizer()
        _errors.tryEmit(msg); _mode.value = AssistantMode.ERROR
        main.postDelayed({ if (_mode.value == AssistantMode.ERROR) _mode.value = AssistantMode.IDLE }, 1500)
    }

    private fun stripWake(text: String): String? {
        val n = text.lowercase().replace(Regex("[,.!?\u0964]"), " ").replace(Regex("\\s+"), " ").trim()
        val variants = listOf(wakeWord, "hey adi", "hi adi", "hey aadi", "hey addy", "hey adhi", "hey ady", "ok adi",
            "\u0939\u0947 \u0906\u0926\u093f", "\u0939\u0947 \u0906\u0926\u0940", "\u0939\u0947\u092f \u0906\u0926\u093f", "\u0939\u093e\u092f \u0906\u0926\u093f").distinct()
        for (v in variants) { val i = n.indexOf(v); if (i >= 0) return n.substring(i + v.length).trim() }
        return null
    }

    private fun beep() { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_ACK, 120) } }

    // ---------------------------------------------------------------- speaking

    fun initTts() = main.post { ensureTts() }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (!ttsReady) { _status.value = _status.value.copy(engineNote = "Text-to-Speech engine start nahi hua (system TTS install/enable karein)"); return@TextToSpeech }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { _mode.value = AssistantMode.SPEAKING } }
                override fun onDone(id: String?) { if (id == finalUtteranceId) main.post { finishSpeaking() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (id == finalUtteranceId) main.post { finishSpeaking() } }
                override fun onStop(id: String?, interrupted: Boolean) {}
            })
            applyVoice(lang)
            pendingSpeak?.let { (t, l, cb) -> pendingSpeak = null; speakNow(t, l, cb) }
        }
    }

    private fun finishSpeaking() {
        abandonFocus(); _mode.value = AssistantMode.IDLE
        val cb = doneCallback; doneCallback = {}; cb()
    }

    private fun applyVoice(l: LanguageMode) {
        val t = tts ?: return
        fun ok(loc: Locale) = t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE
        val primary = Locale.forLanguageTag(l.localeTag)
        var chosen: Locale? = null; var note = ""
        if (l != LanguageMode.SANTALI && ok(primary)) { chosen = primary; note = "Native ${l.displayName} voice loaded." }
        else {
            chosen = listOf(Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("en-IN")).firstOrNull { ok(it) }
            note = if (l == LanguageMode.SANTALI) "Santali: native voice pack not installed on device TTS. Using Indian acoustic phonetics with Ol Chiki transliteration (approximate)."
                   else "${l.displayName} native voice not found. Using Indian regional TTS voice."
        }
        if (chosen == null) { _status.value = VoiceEngineStatus(l, false, "Koi Indian TTS voice installed nahi (Settings > Text-to-speech se Hindi/English-India voice download karein)", ""); return }
        val loc: Locale = chosen
        t.language = loc
        var voiceName = ""
        try {
            val female = listOf("female", "sfg", "f0", "-x-hie", "-x-hia", "-x-end", "-x-ene")
            val candidates = t.voices.orEmpty().filter { it.locale.language == loc.language && (it.locale.country == loc.country || loc.country.isEmpty()) }
            val pick = candidates.firstOrNull { v -> female.any { v.name.contains(it, true) } && !v.isNetworkConnectionRequired } ?: candidates.firstOrNull { !v(it) }
            if (pick != null) { t.voice = pick; voiceName = pick.name }
        } catch (e: Exception) { Log.w(TAG, "Error selecting voice", e) }
        t.setSpeechRate(rate); t.setPitch(pitch)
        val native = l != LanguageMode.SANTALI && loc.language == primary.language
        _status.value = VoiceEngineStatus(l, native, if (voiceName.isNotEmpty() && native) "Natural Indian Voice active." else note, voiceName)
    }
    private fun v(x: android.speech.tts.Voice) = x.isNetworkConnectionRequired && !com.example.util.Net.isOnline(app)

    fun speak(text: String, language: LanguageMode, onDone: () -> Unit = {}) = main.post {
        ensureTts()
        val clean = sanitize(text, language)
        if (clean.isBlank()) { onDone(); return@post }
        if (!ttsReady) { pendingSpeak = Triple(clean, language, onDone); return@post }
        speakNow(clean, language, onDone)
    }

    private fun speakNow(clean: String, language: LanguageMode, onDone: () -> Unit) {
        if (language != lang) { lang = language; applyVoice(language) }
        listeningRequested = false; destroyRecognizer(); _partial.value = ""      // never listen to ourselves
        requestFocus()
        val chunks = chunk(clean)
        doneCallback = onDone
        val base = "adi-${System.nanoTime()}"
        finalUtteranceId = "$base-${chunks.lastIndex}"
        _mode.value = AssistantMode.SPEAKING
        chunks.forEachIndexed { i, c -> tts?.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$base-$i") }
    }

    fun stopSpeaking() = main.post { stopSpeakingInternal() }

    private fun stopSpeakingInternal() {
        doneCallback = {}
        if (_mode.value == AssistantMode.SPEAKING) { tts?.stop(); abandonFocus(); _mode.value = AssistantMode.IDLE }
    }

    private fun sanitize(text: String, l: LanguageMode): String {
        var s = text.replace(Regex("```[\\s\\S]*?```"), " Code snippet displayed on screen. ")
            .replace(Regex("\\bhttps?://\\S+"), "web link").replace(Regex("[*#_`>]"), "")
            .replace(Regex("[\\p{So}\\p{Cs}]"), "").replace(Regex("\\s+"), " ").trim()
        if (l == LanguageMode.SANTALI && SantaliPhonetics.hasOlChiki(s)) s = SantaliPhonetics.toRoman(s)
        return s
    }

    private fun chunk(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (sent in s.split(Regex("(?<=[.!?\u0964])\\s+"))) {
            if (cur.length + sent.length > 350 && cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
            var rest = sent
            while (rest.length > 350) { out += rest.substring(0, 350); rest = rest.substring(350) }
            if (cur.isNotEmpty()) cur.append(' '); cur.append(rest)
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out.ifEmpty { listOf(s) }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            focusRequest = r; audio.requestAudioFocus(r)
        } else @Suppress("DEPRECATION") audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) { focusRequest?.let { audio.abandonAudioFocusRequest(it) }; focusRequest = null }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(null)
    }

    fun release() = main.post {
        listeningRequested = false; destroyRecognizer(); runCatching { tts?.stop(); tts?.shutdown() }; tts = null; ttsReady = false
    }
}
