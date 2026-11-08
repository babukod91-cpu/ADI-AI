package com.example.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.example.data.model.AssistantAction
import com.example.data.model.AssistantAction.*
import com.example.data.model.SystemActionType
import com.example.data.model.SystemActionType.*
import com.example.service.AdiVoiceAccessibilityService
import com.example.service.AdiVoiceAccessibilityService.Dir
import kotlinx.coroutines.delay
import java.net.URLEncoder

data class ExecResult(val success: Boolean, val message: String)

/**
 * Executes phone actions. Rule: success=true ONLY when Android confirmed what we claim.
 * Intents that merely hand over to another app say so ("dialer khul gaya"), never "call ho gayi".
 */
class AndroidActionExecutor(private val ctx: Context, private val launcher: AppLauncherManager) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    @Volatile private var torchOn = false

    init {
        runCatching {
            cam.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, null)
        }
    }

    private fun m(en: Boolean, e: String, h: String) = if (en) e else h
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun svc() = AdiVoiceAccessibilityService.instance
    private fun start(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    private val needA11y get() = "Accessibility service on karein (Settings > ADI AI)"

    suspend fun execute(a: AssistantAction, english: Boolean): ExecResult = when (a) {
        is OpenApp -> when (val r = launcher.open(a.appName)) {
            is AppLaunchResult.Success -> ExecResult(true, m(english, "Opened ${r.label}.", "${r.label} khol diya."))
            is AppLaunchResult.NotInstalled -> ExecResult(false, m(english, "${r.name} isn't installed.", "${r.name} installed nahi hai."))
            is AppLaunchResult.Failed -> ExecResult(false, m(english, "Couldn't open it: ${r.message}", "Khol nahi paayi: ${r.message}"))
        }
        is SetTimer -> {
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            val min = a.seconds / 60; val sec = a.seconds % 60
            val txt = if (min > 0) "$min min" + (if (sec > 0) " $sec sec" else "") else "$sec sec"
            if (start(i)) ExecResult(true, m(english, "Timer request for $txt sent to the clock app - check it to confirm.", "$txt ke timer ki request clock app ko bheji - confirm karne ke liye clock app dekh lein."))
            else ExecResult(false, m(english, "No clock app handled the timer request.", "Koi clock app timer request handle nahi kar paaya."))
        }
        is SetAlarm -> {
            val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, a.minute).putExtra(AlarmClock.EXTRA_MESSAGE, a.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            val t = "%02d:%02d".format(a.hour, a.minute)
            if (start(i)) ExecResult(true, m(english, "Alarm request for $t sent to the clock app - check it to confirm.", "$t ke alarm ki request clock app ko bheji - confirm karne ke liye clock app dekh lein."))
            else ExecResult(false, m(english, "No clock app handled the alarm request.", "Koi clock app alarm request handle nahi kar paaya."))
        }
        is MakePhoneCall -> {
            val num = a.target.filter { it.isDigit() || it == '+' }
            if (num.length < 3) ExecResult(false, m(english, "I don't have a valid number for '${a.target}'.", "'${a.target}' ka sahi number nahi mila."))
            else if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))))
                ExecResult(true, m(english, "Dialer is open with ${a.displayName ?: num} - press the call button.", "${a.displayName ?: num} ke saath dialer khul gaya - call button aap dabayein."))
            else ExecResult(false, m(english, "No dialer app found.", "Dialer app nahi mila."))
        }
        is SendWhatsApp -> sendWhatsApp(a, english)
        is SearchYouTube -> searchYouTube(a, english)
        is PlaySpotify -> {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${enc(a.query)}")).setPackage("com.spotify.music")
            val ok = start(i) || start(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${enc(a.query)}")))
            if (ok) ExecResult(true, m(english, "Opened Spotify search for '${a.query}' (tap a song to play).", "Spotify mein '${a.query}' ka search khol diya (gaana aap tap karke chalayein)."))
            else ExecResult(false, m(english, "Couldn't open Spotify.", "Spotify nahi khul paaya."))
        }
        is NavigateMaps -> {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${enc(a.destination)}")).setPackage("com.google.android.apps.maps")
            val ok = start(nav) || start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${enc(a.destination)}")))
            if (ok) ExecResult(true, m(english, "Opened navigation to ${a.destination}.", "${a.destination} ke liye navigation khol diya."))
            else ExecResult(false, m(english, "No maps app found.", "Maps app nahi mila."))
        }
        is SystemControl -> system(a.actionType, english)
        is Tap -> {
            val s = svc()
            if (s == null) ExecResult(false, needA11y)
            else if (s.clickByText(a.label)) ExecResult(true, m(english, "Tapped '${a.label}'.", "'${a.label}' par tap kiya."))
            else ExecResult(false, m(english, "I couldn't find a tappable '${a.label}' on screen.", "Screen par tap karne layak '${a.label}' nahi mila."))
        }
        is AnswerText -> ExecResult(true, a.message)
        is AskScreen, OpenCameraAI -> ExecResult(true, "")
    }

    private fun sendWhatsApp(a: SendWhatsApp, en: Boolean): ExecResult {
        var num = a.contactOrPhone.filter { it.isDigit() }
        if (num.length < 7) return ExecResult(false, m(en, "I don't have a number for '${a.contactOrPhone}'.", "'${a.contactOrPhone}' ka number nahi mila."))
        if (num.length == 10) num = "91$num"      // assumes India when no country code is given
        val pkg = AppLauncherManager.KNOWN.first { it.label == "WhatsApp" }.packages.firstOrNull { launcher.isInstalled(it) }
            ?: return ExecResult(false, m(en, "WhatsApp isn't installed.", "WhatsApp installed nahi hai."))
        val url = "https://wa.me/$num" + if (a.message.isNotBlank()) "?text=${enc(a.message)}" else ""
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)))
            ExecResult(true, m(en, "WhatsApp chat with ${a.displayName ?: num} is open${if (a.message.isNotBlank()) " with your message ready - press send" else ""}.",
                "${a.displayName ?: num} ki WhatsApp chat khul gayi${if (a.message.isNotBlank()) " aur message tayyar hai - send button aap dabayein" else ""}."))
        else ExecResult(false, m(en, "Couldn't open WhatsApp.", "WhatsApp nahi khul paaya."))
    }

    private suspend fun searchYouTube(a: SearchYouTube, en: Boolean): ExecResult {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${enc(a.query)}"))
        AppLauncherManager.KNOWN.first { it.label == "YouTube" }.packages.firstOrNull { launcher.isInstalled(it) }?.let { i.setPackage(it) }
        if (!start(i)) return ExecResult(false, m(en, "Couldn't open YouTube.", "YouTube nahi khul paaya."))
        if (!a.autoPlay) return ExecResult(true, m(en, "Opened YouTube results for '${a.query}'.", "YouTube par '${a.query}' ke results khol diye."))
        val s = svc() ?: return ExecResult(true, m(en, "Opened YouTube results for '${a.query}'. Turn on Accessibility so I can tap the first video.", "'${a.query}' ke results khul gaye. Pehla video khud chalane ke liye Accessibility on karein."))
        var clicked = false
        for (n in 1..10) { delay(700); if (s.clickByDescription("play video")) { clicked = true; break } }
        return if (clicked) ExecResult(true, m(en, "Tapped the first result for '${a.query}'.", "'${a.query}' ka pehla result tap kiya."))
        else ExecResult(true, m(en, "Results are open but I couldn't tap the first video - please pick one.", "Results khul gaye par pehla video tap nahi kar paayi - aap chun lein."))
    }

    private suspend fun system(t: SystemActionType, en: Boolean): ExecResult {
        fun global(action: Int, ok: String, okH: String): ExecResult {
            val s = svc() ?: return ExecResult(false, needA11y)
            return if (s.global(action)) ExecResult(true, m(en, ok, okH)) else ExecResult(false, m(en, "Android refused that action.", "Android ne yeh action nahi hone diya."))
        }
        return when (t) {
            BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Went back.", "Peeche gayi.")
            RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Opened recent apps.", "Recent apps khol diye.")
            NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opened notifications.", "Notification panel khol diya.")
            HOME -> svc()?.takeIf { it.global(AccessibilityService.GLOBAL_ACTION_HOME) }?.let { ExecResult(true, m(en, "Went to the home screen.", "Home screen par aa gayi.")) }
                ?: if (start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))) ExecResult(true, m(en, "Went to the home screen.", "Home screen par aa gayi.")) else ExecResult(false, "Home fail")
            OPEN_SETTINGS -> if (start(Intent(Settings.ACTION_SETTINGS))) ExecResult(true, m(en, "Opened Settings.", "Settings khol di.")) else ExecResult(false, "Settings fail")
            OPEN_CAMERA -> if (start(Intent("android.media.action.STILL_IMAGE_CAMERA"))) ExecResult(true, m(en, "Opened the camera.", "Camera khol diya.")) else ExecResult(false, m(en, "No camera app found.", "Camera app nahi mila."))
            OPEN_GALLERY -> {
                val sel = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
                if (start(sel)) ExecResult(true, m(en, "Opened the gallery.", "Gallery khol di.")) else ExecResult(false, m(en, "No gallery app found.", "Gallery app nahi mili."))
            }
            VOLUME_UP -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); ExecResult(true, m(en, "Volume raised.", "Volume badha diya.")) }
            VOLUME_DOWN -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); ExecResult(true, m(en, "Volume lowered.", "Volume kam kar diya.")) }
            FLASHLIGHT -> try {
                val id = cam.cameraIdList.firstOrNull {
                    val c = cam.getCameraCharacteristics(it)
                    c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: return ExecResult(false, m(en, "This phone has no flashlight.", "Is phone mein flashlight nahi hai."))
                val target = !torchOn
                cam.setTorchMode(id, target)
                ExecResult(true, m(en, if (target) "Flashlight on." else "Flashlight off.", if (target) "Flashlight on kar di." else "Flashlight band kar di."))
            } catch (e: CameraAccessException) { ExecResult(false, m(en, "Flashlight is busy (camera in use).", "Flashlight abhi busy hai (camera use mein hai).")) }
            SWIPE_UP -> swipe(Dir.UP, en); SWIPE_DOWN -> swipe(Dir.DOWN, en)
            SWIPE_LEFT -> swipe(Dir.LEFT, en); SWIPE_RIGHT -> swipe(Dir.RIGHT, en)
            MEDIA_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PAUSE, en, "Sent pause to the active player.", "Active player ko pause bheja.")
            MEDIA_PLAY -> media(KeyEvent.KEYCODE_MEDIA_PLAY, en, "Sent play to the active player.", "Active player ko play bheja.")
            MEDIA_NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT, en, "Sent next to the active player.", "Active player ko next bheja.")
        }
    }

    private suspend fun swipe(d: Dir, en: Boolean): ExecResult {
        val s = svc() ?: return ExecResult(false, needA11y)
        return if (s.swipe(d)) ExecResult(true, m(en, "Done.", "Ho gaya.")) else ExecResult(false, m(en, "The gesture didn't complete.", "Gesture complete nahi hua."))
    }

    private fun media(code: Int, en: Boolean, ok: String, okH: String): ExecResult {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ExecResult(true, m(en, "$ok (works only if a media app is active).", "$okH (sirf tab kaam karta hai jab koi media app active ho)."))
    }
}
