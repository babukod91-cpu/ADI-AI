package com.example.core

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.example.AdiGraph
import com.example.QuickAssistActivity
import com.example.data.local.ConversationEntity
import com.example.data.model.*
import com.example.data.model.AssistantAction.*
import com.example.data.repository.*
import com.example.service.AdiVoiceAccessibilityService
import com.example.util.Images
import com.example.util.Net
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class UiEvent {
    object RequestMic : UiEvent()
    object RequestContacts : UiEvent()
    object OpenAccessibilitySettings : UiEvent()
    object AskScreenConsent : UiEvent()
    object MoveToBackground : UiEvent()
    object CloseQuickAssist : UiEvent()
    data class Toast(val msg: String) : UiEvent()
}

/**
 * The single brain of the app: used by the UI, the quick-assist card, the floating circle and the wake-word service.
 * Pipeline: text -> (stop / yes-no / contact-choice) -> IntentParser -> phone action | screen AI | camera AI | BrainRouter.
 */
class AssistantController(private val app: Application) {
    private val g = AdiGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val s: VoiceSettings get() = g.settings.settings.value

    val mode get() = g.voice.mode
    val partial get() = g.voice.partial
    val level get() = g.voice.level
    val lastReply = MutableStateFlow("")
    val engineUsed = MutableStateFlow("")
    val emotion = MutableStateFlow(AvatarEmotion.FRIENDLY)
    val busy = MutableStateFlow(false)
    val pending = MutableStateFlow<PendingConfirmation?>(null)
    val contactChoice = MutableStateFlow<ContactChoice?>(null)
    val showCameraAi = MutableStateFlow(false)
    val visionResult = MutableStateFlow<VisionScanResult?>(null)
    val visionBusy = MutableStateFlow(false)
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events

    private class ScreenContext(val text: String, val jpeg: ByteArray?, val at: Long)
    private var screenCtx: ScreenContext? = null
    private var voiceSession = false
    private var wakeLoop = false
    private var bubbleScreen = false
    private var pendingScreenQuestion: String? = null
    private var retryAfterContacts: String? = null
    private var inserts = 0

    init {
        g.voice.onFinalText = { onUserText(it, fromVoice = true) }
        scope.launch { g.voice.errors.collect { _events.tryEmit(UiEvent.Toast(it)); emotion.value = AvatarEmotion.CARING } }
    }

    private fun m(en: Boolean, e: String, h: String) = if (en) e else h
    private fun micGranted() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ voice control

    fun toggleMic() {
        when (g.voice.mode.value) {
            AssistantMode.LISTENING -> { voiceSession = false; g.voice.stopListening() }
            AssistantMode.SPEAKING -> { voiceSession = false; g.voice.stopSpeaking() }
            else -> startListening()
        }
    }

    fun startListening(loop: Boolean = false) {
        if (!micGranted()) { _events.tryEmit(UiEvent.RequestMic); return }
        voiceSession = s.continuousMode && !loop
        emotion.value = AvatarEmotion.LISTENING
        g.voice.startListening(s.language, offline = !Net.isOnline(app), loop = loop)
    }

    fun startWakeLoop() { wakeLoop = true; startListening(loop = true) }

    fun stopAll() { wakeLoop = false; voiceSession = false; g.voice.stopListening(); g.voice.stopSpeaking() }

    fun onMicGranted() = startListening()
    fun onContactsResult(granted: Boolean) {
        val t = retryAfterContacts; retryAfterContacts = null
        if (granted && t != null) onUserText(t, true)
        else if (!granted) _events.tryEmit(UiEvent.Toast("Contacts permission ke bina naam se call/WhatsApp nahi ho sakta (number bol kar try karein)"))
    }

    fun sendText(t: String) = onUserText(t, fromVoice = false)

    fun onUserText(text: String, fromVoice: Boolean) {
        scope.launch { lock.withLock { try { process(text.trim(), fromVoice) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            busy.value = false; g.voice.setIdle(); lastReply.value = "Kuch gadbad ho gayi: ${e.message}" } } }
    }

    // ------------------------------------------------------------------ pipeline

    private suspend fun process(text: String, fromVoice: Boolean) {
        if (text.isBlank()) { g.voice.setIdle(); return }
        val set = s
        val lang = if (set.autoLanguage) LanguageDetector.detect(text, set.language) else set.language
        val en = lang == LanguageMode.ENGLISH_IN

        if (Replies.isStop(text)) { stopAll(); g.voice.setIdle(); return }

        pending.value?.let { p ->
            if (Replies.isYes(text)) { pending.value = null; log("user", text); executeNow(p.actionToExecute, lang, fromVoice); return }
            if (Replies.isNo(text)) { pending.value = null; log("user", text); reply(m(en, "Okay, cancelled.", "Theek hai, cancel kar diya."), lang, fromVoice); return }
            pending.value = null
        }
        contactChoice.value?.let { c ->
            val idx = Replies.ordinal(text) ?: c.matches.indexOfFirst { it.name.contains(text, true) }.takeIf { it >= 0 }
            if (idx != null && idx < c.matches.size) { contactChoice.value = null; log("user", text); confirmContact(c.action, c.matches[idx], lang, fromVoice); return }
            contactChoice.value = null
            if (Replies.isNo(text)) { log("user", text); reply(m(en, "Okay, cancelled.", "Theek hai, cancel kar diya."), lang, fromVoice); return }
        }
        if (Regex("^(done|exit screen|screen (band|hatao|clear)|screen context (clear|hatao))$", RegexOption.IGNORE_CASE).matches(text)) {
            screenCtx = null; log("user", text); reply(m(en, "Screen context cleared.", "Screen context hata diya."), lang, fromVoice); return
        }

        val history = recentHistory()
        log("user", text)
        g.voice.setThinking(); busy.value = true; emotion.value = AvatarEmotion.THINKING
        try {
            when (val a = IntentParser.parse(text)) {
                null -> brainReply(text, lang, history, fromVoice)
                is AskScreen -> askScreen(a.question, lang, fromVoice)
                OpenCameraAI -> { showCameraAi.value = true; reply(m(en, "Opening the camera scanner.", "Camera scanner khol rahi hoon."), lang, fromVoice) }
                is AnswerText -> reply(a.message, lang, fromVoice)
                is SendWhatsApp, is MakePhoneCall -> prepareContactAction(a, lang, fromVoice, text)
                else -> { screenCtx = null; executeNow(a, lang, fromVoice) }
            }
        } finally { busy.value = false }
    }

    private suspend fun brainReply(text: String, lang: LanguageMode, history: List<ChatTurn>, fromVoice: Boolean) {
        val sc = screenCtx?.takeIf { System.currentTimeMillis() - it.at < 5 * 60_000 }
        if (sc != null) { answerWithScreen(text, sc, lang, history, fromVoice); return }
        val r = g.router.reply(text, lang, history)
        if (r.text != null) reply(r.text, lang, fromVoice, engine = r.engine + if (r.fellBack) " (fallback)" else "")
        else reply(r.error ?: "Jawab nahi mil paaya.", lang, fromVoice, ok = false)
    }

    private suspend fun answerWithScreen(q: String, sc: ScreenContext, lang: LanguageMode, history: List<ChatTurn>, fromVoice: Boolean) {
        val prompt = "The user shared their phone screen earlier. Visible text:\n${sc.text.take(2500)}\n\nFollow-up question: $q"
        if (sc.jpeg != null && g.gemini.status().ready) {
            g.gemini.generateWithImage(prompt, sc.jpeg, lang).onSuccess { reply(it, lang, fromVoice, engine = g.gemini.label + " + screen"); return }
        }
        val r = g.router.reply(prompt.take(3500), lang, history)
        if (r.text != null) reply(r.text, lang, fromVoice, engine = r.engine + " + screen text") else reply(r.error ?: "Jawab nahi mil paaya.", lang, fromVoice, ok = false)
    }

    private suspend fun executeNow(a: AssistantAction, lang: LanguageMode, fromVoice: Boolean) {
        val r = g.executor.execute(a, lang == LanguageMode.ENGLISH_IN)
        reply(r.message, lang, fromVoice, engine = "Phone control", action = a, ok = r.success)
    }

    // ------------------------------------------------------------------ contacts / confirmation

    private suspend fun prepareContactAction(a: AssistantAction, lang: LanguageMode, fromVoice: Boolean, original: String) {
        val en = lang == LanguageMode.ENGLISH_IN
        val name = when (a) { is SendWhatsApp -> a.contactOrPhone; is MakePhoneCall -> a.target; else -> "" }
        if (name.none { it.isLetter() } && name.count { it.isDigit() } >= 7) { confirmAction(a, name, lang, fromVoice); return }
        if (!g.contacts.hasPermission()) {
            retryAfterContacts = original; _events.tryEmit(UiEvent.RequestContacts)
            reply(m(en, "I need contacts permission to find '$name'.", "'$name' dhundhne ke liye contacts permission chahiye."), lang, fromVoice); return
        }
        val matches = withContext(Dispatchers.IO) { g.contacts.find(name) }
        when {
            matches.isEmpty() -> reply(m(en, "I couldn't find a contact named '$name'.", "'$name' naam ka contact nahi mila."), lang, fromVoice, ok = false)
            matches.size == 1 -> confirmContact(a, matches[0], lang, fromVoice)
            else -> {
                contactChoice.value = ContactChoice(name, matches, a)
                val list = matches.mapIndexed { i, c -> "${i + 1}) ${c.name}" }.joinToString(", ")
                reply(m(en, "I found several: $list. Which one?", "Kai contacts mile: $list. Kaunsa?"), lang, fromVoice)
            }
        }
    }

    private suspend fun confirmContact(a: AssistantAction, c: ContactMatch, lang: LanguageMode, fromVoice: Boolean) {
        val resolved = when (a) { is SendWhatsApp -> a.copy(contactOrPhone = c.number, displayName = c.name); is MakePhoneCall -> a.copy(target = c.number, displayName = c.name); else -> a }
        confirmAction(resolved, c.name, lang, fromVoice)
    }

    private suspend fun confirmAction(a: AssistantAction, who: String, lang: LanguageMode, fromVoice: Boolean) {
        val en = lang == LanguageMode.ENGLISH_IN
        val p = when (a) {
            is SendWhatsApp -> PendingConfirmation("WhatsApp message", "To: ${a.displayName ?: who}\nMessage: ${a.message.ifBlank { "(empty - will only open the chat)" }}", "SendWhatsApp", a)
            is MakePhoneCall -> PendingConfirmation("Phone call", "Call ${a.displayName ?: who}?", "MakePhoneCall", a)
            else -> PendingConfirmation("Confirm", a.toString(), a::class.simpleName ?: "", a)
        }
        pending.value = p
        reply(m(en, "${p.title}. ${p.details}. Say yes to confirm or no to cancel.", "${p.title}. ${p.details}. Confirm karne ke liye 'haan', cancel ke liye 'nahi' boliye."), lang, fromVoice)
    }

    fun confirmPending() { val p = pending.value ?: return; pending.value = null
        scope.launch { lock.withLock { executeNow(p.actionToExecute, s.language, false) } } }
    fun cancelPending() { pending.value = null; g.voice.setIdle() }
    fun chooseContact(i: Int) { val c = contactChoice.value ?: return; contactChoice.value = null
        scope.launch { lock.withLock { c.matches.getOrNull(i)?.let { confirmContact(c.action, it, s.language, false) } } } }
    fun cancelContactChoice() { contactChoice.value = null }

    // ------------------------------------------------------------------ screen AI (explicit, user-triggered)

    fun grantScreenConsent() {
        g.settings.update { it.copy(screenConsent = true) }
        pendingScreenQuestion?.let { onUserText(it, true) }; pendingScreenQuestion = null
    }

    /** Called by the floating-circle card: closes the card, then analyses whatever app is underneath. */
    fun askAboutScreenFromBubble() { bubbleScreen = true; _events.tryEmit(UiEvent.CloseQuickAssist); onUserText("explain this screen", true) }

    private suspend fun askScreen(question: String, lang: LanguageMode, fromVoice: Boolean) {
        val en = lang == LanguageMode.ENGLISH_IN
        if (!s.screenConsent) {
            pendingScreenQuestion = question; _events.tryEmit(UiEvent.AskScreenConsent)
            reply(m(en, "I need your permission before I look at your screen.", "Screen dekhne se pehle aapki permission chahiye."), lang, fromVoice); return
        }
        val svc = AdiVoiceAccessibilityService.instance
        if (svc == null) {
            _events.tryEmit(UiEvent.OpenAccessibilitySettings)
            reply(m(en, "Please turn on the ADI accessibility service so I can see the screen.", "Screen dekhne ke liye ADI accessibility service on karein."), lang, fromVoice, ok = false); return
        }
        if (bubbleScreen) delay(800) else if (g.appVisible) { _events.tryEmit(UiEvent.MoveToBackground); delay(900) }   // otherwise I'd read my own screen
        if (svc.isSensitiveForeground()) {
            reply(m(en, "That looks like a banking or password app - I won't read it.", "Yeh banking/password app lagta hai - main ise nahi padhungi."), lang, fromVoice, ok = false); finishBubble(); return
        }
        _events.tryEmit(UiEvent.Toast(m(en, "ADI is analysing your screen...", "ADI aapki screen analyze kar rahi hai...")))
        val text = svc.snapshotText()
        val shot = svc.screenshot()
        val jpeg = shot.bitmap?.let { Images.toJpeg(it) }
        if (text.isBlank() && jpeg == null) {
            reply(m(en, "I couldn't read the screen: ${shot.error ?: "no content found"}.", "Screen padh nahi paayi: ${shot.error ?: "koi content nahi mila"}."), lang, fromVoice, ok = false); finishBubble(); return
        }
        screenCtx = ScreenContext(text, jpeg, System.currentTimeMillis())
        val prompt = "The user shared their phone screen. Explain what is visible and help with: \"$question\". Visible text:\n${text.take(3000)}"
        val viaGemini = jpeg != null && g.gemini.status().ready
        var out: Pair<String, String>? = null
        if (viaGemini) out = g.gemini.generateWithImage(prompt, jpeg!!, lang).getOrNull()?.let { it to (g.gemini.label + " + screenshot") }
        if (out == null) out = g.router.reply(prompt.take(3500), lang, emptyList()).let { r -> r.text?.let { it to (r.engine + " + screen text") } }
        val note = if (shot.error != null) " (${shot.error})" else ""
        if (out != null) reply(out.first + note, lang, fromVoice, engine = out.second)
        else reply(m(en, "I read the screen but no AI engine is available to explain it. Add a Gemini key or the Llama model.", "Screen padh li par samjhane ke liye koi AI engine available nahi. Gemini key ya Llama model set karein."), lang, fromVoice, ok = false)
        finishBubble()
    }

    private fun finishBubble() {
        if (bubbleScreen) { bubbleScreen = false
            app.startActivity(Intent(app, QuickAssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
    }

    // ------------------------------------------------------------------ camera AI

    fun analyzeImage(bmp: Bitmap, question: String) {
        scope.launch {
            visionBusy.value = true; visionResult.value = null
            try { visionResult.value = g.vision.analyze(bmp, question, s.language) }
            catch (e: Exception) { _events.tryEmit(UiEvent.Toast("Image analyze nahi ho paayi: ${e.message}")) }
            finally { visionBusy.value = false }
        }
    }
    fun closeCameraAi() { showCameraAi.value = false; visionResult.value = null }

    // ------------------------------------------------------------------ history

    fun clearHistory() { scope.launch { g.db.dao().clearAll(); screenCtx = null; lastReply.value = ""; engineUsed.value = "" } }

    private suspend fun recentHistory(): List<ChatTurn> =
        g.db.dao().recent(14).asReversed().filter { it.actionType == null }.map { ChatTurn(if (it.sender == "user") "user" else "assistant", it.text) }

    private suspend fun log(sender: String, text: String, action: AssistantAction? = null, ok: Boolean = true) {
        g.db.dao().insert(ConversationEntity(sender = sender, text = text, actionType = action?.let { it::class.simpleName }, actionSummary = if (action != null) (if (ok) "done" else "failed") else null))
        if (++inserts % 25 == 0) g.db.dao().trim(500)
    }

    private suspend fun reply(text: String, lang: LanguageMode, fromVoice: Boolean, engine: String = "", action: AssistantAction? = null, ok: Boolean = true) {
        log("adi", text, action, ok)
        lastReply.value = text
        if (engine.isNotEmpty()) engineUsed.value = engine
        emotion.value = if (ok) AvatarEmotion.CHEERFUL else AvatarEmotion.CARING
        if (fromVoice || wakeLoop) g.voice.speak(text, lang) { afterSpeak() } else g.voice.setIdle()
    }

    private fun afterSpeak() {
        emotion.value = AvatarEmotion.FRIENDLY
        val needsAnswer = pending.value != null || contactChoice.value != null
        if (needsAnswer || voiceSession) startListening(loop = false)
        else if (wakeLoop && s.wakeWordEnabled) startListening(loop = true)
    }
}
