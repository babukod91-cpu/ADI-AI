package com.example.voice

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.AdiGraph
import com.example.service.Notifs

/**
 * Optional "Hey ADI" background listening. Foreground service with a persistent notification (Android requirement),
 * started only after the user enables it AND grants microphone permission. Uses the same recogniser loop as the app.
 * NOTE: SpeechRecognizer restarts can beep on some phones and cost battery; a dedicated engine (Porcupine/openWakeWord) is better long-term.
 */
class WakeWordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            AdiGraph.settings.update { it.copy(wakeWordEnabled = false) }
            AdiGraph.controller.stopAll(); stopSelf(); return START_NOT_STICKY
        }
        Notifs.ensure(this)
        val stop = PendingIntent.getService(this, 2, Intent(this, WakeWordService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this, 2002,
            Notifs.build(this, "ADI is listening for \"Hey ADI\"", "Microphone is in use. Tap Stop to turn off.", stop),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        AdiGraph.controller.startWakeLoop()
        return START_STICKY
    }

    override fun onDestroy() { AdiGraph.controller.stopAll(); super.onDestroy() }
}
