package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phone control + (on explicit request only) screen reading.
 * It does not process events in the background; it acts only when ADI is asked to.
 */
class AdiVoiceAccessibilityService : AccessibilityService() {
    enum class Dir { UP, DOWN, LEFT, RIGHT }
    data class Shot(val bitmap: Bitmap?, val error: String?)

    companion object {
        @Volatile var instance: AdiVoiceAccessibilityService? = null
        private const val TAG = "AdiAccessibility"
        private val sensitive = listOf("bank", "wallet", "upi", "paytm", "phonepe", "gpay", "authenticator", "keepass", "bitwarden", "lastpass", "1password", "password")
    }

    override fun onServiceConnected() { instance = this; Log.i(TAG, "connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { Log.i(TAG, "interrupted") }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun global(action: Int): Boolean = performGlobalAction(action)

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()
    fun isSensitiveForeground(): Boolean = foregroundPackage()?.lowercase()?.let { p -> sensitive.any { p.contains(it) } } ?: false

    /** Visible text/labels only; password fields are skipped. Capped to protect memory and privacy. */
    fun snapshotText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder(); var count = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count > 250 || sb.length > 6000 || !n.isVisibleToUser) return
            if (!n.isPassword) {
                val label = n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
                if (label.isNotEmpty()) {
                    sb.append(if (n.isClickable) "[button] " else if (n.isEditable) "[input] " else "").append(label.take(160)).append('\n'); count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    fun clickByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(label)) {
            var c: AccessibilityNodeInfo? = n; var depth = 0
            while (c != null && depth < 5) {
                if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                c = c.parent; depth++
            }
        }
        return false
    }

    fun clickByDescription(sub: String): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.contentDescription?.toString()?.contains(sub, true) == true) {
                var c: AccessibilityNodeInfo? = n; var d = 0
                while (c != null && d < 4) { if (c.isClickable) return c; c = c.parent; d++ }
            }
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        return find(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** Finger direction (SWIPE_UP = finger moves up = page scrolls down). True only if the system completed the gesture. */
    suspend fun swipe(dir: Dir): Boolean = suspendCancellableCoroutine { cont ->
        val dm = resources.displayMetrics; val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            Dir.UP -> { p.moveTo(w / 2, h * 0.72f); p.lineTo(w / 2, h * 0.28f) }
            Dir.DOWN -> { p.moveTo(w / 2, h * 0.28f); p.lineTo(w / 2, h * 0.72f) }
            Dir.LEFT -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            Dir.RIGHT -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
        }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 320)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    /** Real screenshot (Android 11+). The system blocks it for FLAG_SECURE windows - we report that honestly. */
    suspend fun screenshot(): Shot =
        if (Build.VERSION.SDK_INT < 30) Shot(null, "Screenshot ke liye Android 11+ chahiye") else shot30()

    @RequiresApi(30)
    private suspend fun shot30(): Shot = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (cont.isActive) cont.resume(Shot(bmp, if (bmp == null) "Screenshot decode fail" else null))
            }
            override fun onFailure(errorCode: Int) {
                val msg = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Yeh screen secure hai - Android ne screenshot block kiya"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Thodi der baad dobara try karein"
                    else -> "Screenshot fail (code $errorCode)"
                }
                if (cont.isActive) cont.resume(Shot(null, msg))
            }
        })
    }
}
