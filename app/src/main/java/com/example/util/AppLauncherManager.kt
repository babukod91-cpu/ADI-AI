package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

sealed class AppLaunchResult {
    data class Success(val label: String) : AppLaunchResult()
    data class NotInstalled(val name: String) : AppLaunchResult()
    data class Failed(val message: String) : AppLaunchResult()
}

class AppLauncherManager(private val ctx: Context) {
    data class KnownApp(val label: String, val packages: List<String>, val aliases: List<String>)

    companion object {
        val KNOWN = listOf(
            KnownApp("YouTube", listOf("com.google.android.youtube"), listOf("youtube", "yt", "\u092f\u0942\u091f\u094d\u092f\u0942\u092c", "\u0987\u0989\u099f\u09bf\u0989\u09ac")),
            KnownApp("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), listOf("whatsapp", "whats app", "wa", "\u0935\u094d\u0939\u093e\u091f\u094d\u0938\u090f\u092a", "\u0935\u094d\u0939\u093e\u091f\u094d\u0938\u0910\u092a")),
            KnownApp("Camera", listOf("com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera", "com.android.camera2", "com.oppo.camera", "com.oneplus.camera", "org.codeaurora.snapcam"), listOf("camera", "cam", "\u0915\u0948\u092e\u0930\u093e", "\u092b\u094b\u091f\u094b")),
            KnownApp("Chrome", listOf("com.android.chrome"), listOf("chrome", "google chrome", "browser", "internet", "\u0915\u094d\u0930\u094b\u092e", "\u0907\u0902\u091f\u0930\u0928\u0947\u091f")),
            KnownApp("Settings", listOf("com.android.settings"), listOf("settings", "setting", "\u0938\u0947\u091f\u093f\u0902\u0917\u094d\u0938", "\u0938\u0947\u091f\u093f\u0902\u0917", "\u092b\u094b\u0928 \u0938\u0947\u091f\u093f\u0902\u0917\u094d\u0938")),
            KnownApp("Gallery", listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d", "com.miui.gallery"), listOf("gallery", "photos", "photo", "\u0917\u0948\u0932\u0930\u0940", "\u0924\u0938\u094d\u0935\u0940\u0930\u0947\u0902")),
            KnownApp("Calculator", listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.miui.calculator"), listOf("calculator", "calc", "\u0915\u0948\u0932\u0915\u0941\u0932\u0947\u091f\u0930", "\u0939\u093f\u0938\u093e\u092c")),
            KnownApp("Clock", listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock"), listOf("clock", "alarm", "\u0905\u0932\u093e\u0930\u094d\u092e", "\u0918\u0921\u093c\u0940")),
            KnownApp("Spotify", listOf("com.spotify.music"), listOf("spotify")),
            KnownApp("Maps", listOf("com.google.android.apps.maps"), listOf("maps", "google maps", "map")),
            KnownApp("Gmail", listOf("com.google.android.gm"), listOf("gmail", "mail", "email")),
            KnownApp("Play Store", listOf("com.android.vending"), listOf("play store", "playstore", "google play")),
            KnownApp("Instagram", listOf("com.instagram.android"), listOf("instagram", "insta")),
            KnownApp("Facebook", listOf("com.facebook.katana"), listOf("facebook", "fb")),
            KnownApp("Telegram", listOf("org.telegram.messenger"), listOf("telegram")),
            KnownApp("X", listOf("com.twitter.android"), listOf("twitter", "x"))
        )
        fun findKnown(name: String): KnownApp? {
            val n = name.lowercase().trim()
            return KNOWN.firstOrNull { k -> k.aliases.any { a -> a == n || (a.length > 3 && n.contains(a)) } }
        }
    }

    private val pm: PackageManager get() = ctx.packageManager

    fun isInstalled(pkg: String) = pm.getLaunchIntentForPackage(pkg) != null
    fun firstInstalled(app: KnownApp) = app.packages.firstOrNull { isInstalled(it) }

    fun open(name: String): AppLaunchResult {
        val known = findKnown(name)
        val pkg: String? = if (known != null) firstInstalled(known) else fuzzyInstalled(name)
        if (pkg == null) return AppLaunchResult.NotInstalled(known?.label ?: name)
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return AppLaunchResult.NotInstalled(name)
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(intent)
            AppLaunchResult.Success(known?.label ?: labelOf(pkg) ?: name)
        } catch (e: Exception) { AppLaunchResult.Failed(e.message ?: "launch failed") }
    }

    private fun labelOf(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()

    private fun fuzzyInstalled(name: String): String? {
        val q = name.lowercase().trim()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        apps.firstOrNull { it.first.equals(q, true) }?.let { return it.second }
        apps.firstOrNull { it.first.startsWith(q, true) }?.let { return it.second }
        apps.firstOrNull { it.first.contains(q, true) }?.let { return it.second }
        if (q.length >= 5) apps.minByOrNull { distance(it.first.lowercase(), q) }?.takeIf { distance(it.first.lowercase(), q) <= 2 }?.let { return it.second }
        return null
    }

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
