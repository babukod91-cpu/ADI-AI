package com.example.data.repository

import com.example.data.model.AssistantAction
import com.example.data.model.AssistantAction.*
import com.example.data.model.SystemActionType.*

/** Pure-Kotlin (JVM-testable) voice/text command parser. English, Hindi (romanised) and Hinglish phrasing. */
object IntentParser {
    private val I = RegexOption.IGNORE_CASE
    private fun r(p: String) = Regex(p, I)

    private val screenQ = r("(what('?s| is) (on|in) (my |the |this )?screen)|(read (my |the |this )?screen)|(screen (par|pe|mein|me) kya)|(screen (padh|padho|batao|dikhao|explain))|(explain (this |my |the )?screen)|(\\b(is|iss|yeh|ye) screen\\b)|(describe (this |my |the )?screen)|(screen analy[sz]e)")
    private val scanQ = r("\\b(scan|scanner|analy[sz]e (this )?(image|photo|picture)|camera ai|camera scan|yeh kya hai|ye kya hai)\\b|^what is this\\??$")

    fun parse(raw: String): AssistantAction? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        val l = t.lowercase()
        if (screenQ.containsMatchIn(l)) return AskScreen(t)
        if (scanQ.containsMatchIn(l)) return OpenCameraAI
        media(l)?.let { return it }
        system(l)?.let { return it }
        alarm(l)?.let { return it }
        timer(l)?.let { return it }
        whatsapp(t, l)?.let { return it }
        call(t, l)?.let { return it }
        maps(t)?.let { return it }
        tap(t)?.let { return it }
        platform(t, l)?.let { return it }
        openApp(t)?.let { return it }
        return null
    }

    private fun media(l: String): AssistantAction? = when {
        r("^(pause|pause (the )?(video|song|music)|video roko|gaana roko|roko)$").matches(l) -> SystemControl(MEDIA_PAUSE)
        r("^(resume|continue|play again|phir se chalao|dobara chalao|play video|play)$").matches(l) -> SystemControl(MEDIA_PLAY)
        r("^(next|skip)$|\\bnext (video|song|track)\\b|^agla (video|gaana|song)").containsMatchIn(l) -> SystemControl(MEDIA_NEXT)
        else -> null
    }

    private fun system(l: String): AssistantAction? {
        fun m(p: String) = r(p).containsMatchIn(l)
        return when {
            m("^(go )?home( screen)?$|\\bgo to home\\b|\\bhome (jao|screen|par jao|pe jao)\\b|\\bghar jao\\b") -> SystemControl(HOME)
            m("^(go )?back$|\\bgo back\\b|\\bback (jao|karo)\\b|\\b(peeche|piche|wapas) jao\\b") -> SystemControl(BACK)
            m("\\brecents?( apps?)?\\b|\\bapp switcher\\b") -> SystemControl(RECENTS)
            m("\\bnotifications?\\b") -> SystemControl(NOTIFICATIONS)
            m("^(open )?(phone )?settings( kholo| open)?$") -> SystemControl(OPEN_SETTINGS)
            m("\\b(open camera|camera (kholo|khol|open|chalu)|take (a )?(photo|picture|selfie)|photo (kheencho|khicho))\\b") -> SystemControl(OPEN_CAMERA)
            m("^(open |show )?(my )?(gallery|photos)( kholo| dikhao| open)?$") -> SystemControl(OPEN_GALLERY)
            m("\\bvolume (up|increase|badha|badhao|zyada|full)\\b|\\b(increase|raise) (the )?volume\\b|\\bawaaz (badhao|tez)\\b|\\blouder\\b") -> SystemControl(VOLUME_UP)
            m("\\bvolume (down|decrease|kam|ghata|ghatao|low)\\b|\\b(decrease|lower|reduce) (the )?volume\\b|\\bawaaz (kam|dheemi)\\b|\\bquieter\\b") -> SystemControl(VOLUME_DOWN)
            m("\\b(flash ?light|torch|tarch)\\b") -> SystemControl(FLASHLIGHT)
            m("\\bswipe up\\b|\\bscroll down\\b|\\bneeche scroll\\b|\\bscroll neeche\\b") -> SystemControl(SWIPE_UP)
            m("\\bswipe down\\b|\\bscroll up\\b|\\bupar scroll\\b|\\bscroll upar\\b") -> SystemControl(SWIPE_DOWN)
            m("\\bswipe left\\b|\\bscroll right\\b") -> SystemControl(SWIPE_LEFT)
            m("\\bswipe right\\b|\\bscroll left\\b") -> SystemControl(SWIPE_RIGHT)
            else -> null
        }
    }

    private val timeRe = r("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|baje)?")

    private fun alarm(l: String): AssistantAction? {
        if (!l.contains("alarm") || r("\\b(cancel|delete|off|band|hatao)\\b").containsMatchIn(l)) return null
        val m = timeRe.find(l.substringAfter("alarm").ifBlank { l }) ?: timeRe.find(l) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val mark = m.groupValues[3].lowercase().replace(".", "")
        val pm = mark == "pm" || r("\\b(shaam|evening|raat|night|dopahar|afternoon)\\b").containsMatchIn(l)
        if (pm && h < 12) h += 12
        if (mark == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        return SetAlarm(h, min, "ADI Alarm")
    }

    private fun timer(l: String): AssistantAction? {
        if (!l.contains("timer")) return null
        val m = r("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|ghante|ghanta)").find(l) ?: return null
        val v = m.groupValues[1].toInt()
        val u = m.groupValues[2].lowercase()
        val secs = when { u.startsWith("sec") -> v; u.startsWith("min") -> v * 60; else -> v * 3600 }
        return SetTimer(secs, "ADI Timer")
    }

    private fun whatsapp(t: String, l: String): AssistantAction? {
        if (!r("whats ?app").containsMatchIn(l)) return null
        if (!r("\\b(send|message|msg|text|bhejo|bhej|likho|likh)\\b").containsMatchIn(l)) return null
        val stop = "(?=\\s+(?:saying|that|ki|message|msg|on whats|whats|par|pe)\\b|$)"
        val contact = (r("\\bto\\s+(.+?)$stop").find(t)?.groupValues?.get(1)
            ?: r("^(?:whats ?app (?:par|pe)\\s+)?(.+?)\\s+ko\\b").find(t)?.groupValues?.get(1))
            ?.trim()?.trimEnd(',', '.')
        if (contact.isNullOrBlank()) return AnswerText("Kisko WhatsApp message bhejna hai? Jaise: 'Rahul ko WhatsApp par hello bhejo'.")
        val after = t.substringAfter(contact, "")
        val msg = r("\\b(?:saying|that|ki|message|msg|likho|text)\\s+(.+?)(?:\\s+(?:on|par|pe)\\s+whats ?app.*|\\s+(?:bhejo|bhej do|send|karo))?$").find(after)?.groupValues?.get(1)?.trim().orEmpty()
        return SendWhatsApp(contact, msg)
    }

    private fun call(t: String, l: String): AssistantAction? {
        if (r("whats ?app").containsMatchIn(l)) return null
        r("^(?:please )?(?:call|dial|phone)\\s+(?:to\\s+)?(.+)$").find(t)?.let { return MakePhoneCall(it.groupValues[1].trim()) }
        r("^(.+?)\\s+ko\\s+(?:call|phone|dial|fon|ring)(?:\\s+(?:karo|lagao|laga do|kar do|kijiye))?$").find(t)?.let { return MakePhoneCall(it.groupValues[1].trim()) }
        r("^(.+?)\\s+(?:ko )?call (?:karo|lagao|kar do)$").find(t)?.let { return MakePhoneCall(it.groupValues[1].trim()) }
        return null
    }

    private fun maps(t: String): AssistantAction? {
        r("(?:navigate to|directions? (?:to|for)|take me to|route to|rasta dikhao|navigation (?:to|for))\\s+(.+)").find(t)?.let { return NavigateMaps(it.groupValues[1].trim()) }
        r("^(.+?)\\s+(?:ka|ke liye|tak)\\s+(?:rasta|route|navigation|directions?)(?:\\s+(?:dikhao|batao))?$").find(t)?.let { return NavigateMaps(it.groupValues[1].trim()) }
        return null
    }

    private fun tap(t: String): AssistantAction? {
        r("^(?:tap|click|press|touch|dabao)\\s+(?:on\\s+)?(.+)$").find(t)?.let { return Tap(it.groupValues[1].trim()) }
        r("^(.+?)\\s+(?:dabao|click karo|tap karo|par click karo|pe click karo|par tap karo)$").find(t)?.let { return Tap(it.groupValues[1].trim()) }
        return null
    }

    private val fillers = setOf("play", "search", "on", "youtube", "yt", "spotify", "par", "pe", "mein", "me", "open", "chalao", "kholo",
        "dikhao", "video", "videos", "song", "gaana", "baja", "bajao", "chala", "karo", "for", "find", "the", "a", "ek", "do", "kar", "mere", "liye")

    private fun platform(t: String, l: String): AssistantAction? {
        fun query() = t.split(" ").filter { it.lowercase().trim(',', '.') !in fillers }.joinToString(" ").trim()
        if (l.contains("spotify")) { val q = query(); return if (q.isBlank()) OpenApp("spotify") else PlaySpotify(q) }
        if (r("\\b(youtube|yt)\\b").containsMatchIn(l)) {
            val q = query()
            if (q.isBlank()) return OpenApp("youtube")
            return SearchYouTube(q, autoPlay = r("\\b(play|chalao|baja|bajao|chala)\\b").containsMatchIn(l))
        }
        r("^(?:play|chalao|bajao)\\s+(.+)$").find(t)?.let { return SearchYouTube(it.groupValues[1].trim(), true) }
        r("^(.+?)\\s+(?:chalao|bajao|baja do|chala do|play karo)$").find(t)?.let { return SearchYouTube(it.groupValues[1].trim(), true) }
        return null
    }

    private fun openApp(t: String): AssistantAction? {
        val raw = r("^(?:please )?(?:open|launch|start)\\s+(.+)$").find(t)?.groupValues?.get(1)
            ?: r("^(.+?)\\s+(?:kholo|khol do|khol|open karo|open kar do|chalu karo|launch karo)$").find(t)?.groupValues?.get(1)
            ?: return null
        val name = raw.replace(r("^(the )?"), "").replace(r("\\s+app$"), "").trim()
        return if (name.isBlank()) null else OpenApp(name)
    }
}

/** Voice yes/no/stop and ordinal helpers (pure Kotlin). */
object Replies {
    private fun norm(s: String) = s.lowercase().replace(Regex("[,.!?\u0964]"), " ").trim().replace(Regex("\\s+"), " ")
    private val yes = setOf("haan", "han", "ha", "haa", "yes", "yeah", "yep", "ok", "okay", "theek hai", "thik hai", "kar do", "bhej do", "send", "confirm",
        "sure", "bilkul", "ji haan", "ji", "\u0939\u093e\u0901", "\u0939\u093e\u0902", "\u0920\u0940\u0915 \u0939\u0948", "\u091c\u0940 \u0939\u093e\u0901")
    private val no = setOf("no", "nahi", "nahin", "na", "cancel", "mat karo", "nope", "\u0928\u0939\u0940\u0902", "\u0928\u093e")
    private val stop = setOf("stop", "ruko", "chup", "bas", "bas karo", "stop speaking", "quiet", "shut up", "chup ho jao", "\u091a\u0941\u092a", "\u0930\u0941\u0915\u094b", "\u092c\u0938")

    fun isYes(s: String) = norm(s) in yes
    fun isNo(s: String) = norm(s) in no
    fun isStop(s: String) = norm(s) in stop || norm(s).startsWith("stop ")

    /** "1", "first", "pehla", "second"... -> 0-based index, or null. */
    fun ordinal(s: String): Int? {
        val n = norm(s)
        val words = mapOf("1" to 0, "one" to 0, "first" to 0, "pehla" to 0, "pehle" to 0, "ek" to 0,
            "2" to 1, "two" to 1, "second" to 1, "doosra" to 1, "dusra" to 1, "do" to 1,
            "3" to 2, "three" to 2, "third" to 2, "teesra" to 2, "teen" to 2,
            "4" to 3, "four" to 3, "fourth" to 3, "chautha" to 3, "char" to 3,
            "5" to 4, "five" to 4, "fifth" to 4, "paanchva" to 4, "paanch" to 4)
        return n.split(" ").firstNotNullOfOrNull { words[it] }
    }
}
