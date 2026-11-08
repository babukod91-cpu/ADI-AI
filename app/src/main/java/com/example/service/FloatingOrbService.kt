package com.example.service

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import com.example.AdiGraph
import com.example.QuickAssistActivity
import com.example.data.model.AssistantMode
import kotlinx.coroutines.*

/** Floating ADI circle over other apps. Needs the user to grant "Display over other apps"; shows a visible notification. */
class FloatingOrbService : Service() {
    private var wm: WindowManager? = null
    private var bubble: OrbBubbleView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { AdiGraph.settings.update { it.copy(floatingOrb = false) }; stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        Notifs.ensure(this)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingOrbService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this, 2001,
            Notifs.build(this, "ADI circle is on", "Tap the circle to ask ADI. Stop here to remove it.", stop),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (bubble == null) addBubble()
        return START_STICKY
    }

    private fun addBubble() {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val size = (56 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 400 }
        val v = OrbBubbleView(this)
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) { lp.x = ox + dx; lp.y = oy + dy; w.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) openQuickAssist()
            }
            true
        }
        w.addView(v, lp); bubble = v
        scope.launch { AdiGraph.voice.mode.collect { v.mode = it } }
    }

    private fun openQuickAssist() {
        startActivity(Intent(this, QuickAssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    override fun onDestroy() {
        scope.cancel()
        bubble?.let { runCatching { wm?.removeView(it) } }; bubble = null
        super.onDestroy()
    }
}

class OrbBubbleView(ctx: Context) : View(ctx) {
    var mode: AssistantMode = AssistantMode.IDLE
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { pulse = it.animatedValue as Float; if (mode != AssistantMode.IDLE) invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f
        val (c1, c2) = when (mode) {
            AssistantMode.LISTENING -> 0xFF4FD8FF.toInt() to 0xFF8E7CFF.toInt()
            AssistantMode.THINKING -> 0xFFFFC857.toInt() to 0xFFFF7AC8.toInt()
            AssistantMode.SPEAKING -> 0xFFFF7AC8.toInt() to 0xFFFFB3E0.toInt()
            AssistantMode.ERROR -> 0xFFFF5252.toInt() to 0xFF8E0000.toInt()
            else -> 0xFF8E7CFF.toInt() to 0xFFFF7AC8.toInt()
        }
        val scale = if (mode == AssistantMode.IDLE) 0.9f else 0.8f + 0.2f * pulse
        paint.shader = RadialGradient(r * 0.8f, r * 0.8f, r, intArrayOf(Color.WHITE, c1, c2), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(width / 2f, height / 2f, r * scale, paint)
    }
}
