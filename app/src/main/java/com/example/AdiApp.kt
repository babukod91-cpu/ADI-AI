package com.example

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import com.example.core.AssistantController
import com.example.data.llama.LlamaInferenceEngine
import com.example.data.llama.LlamaModelManager
import com.example.data.local.AdiDatabase
import com.example.data.prefs.SettingsStore
import com.example.data.repository.BrainRouter
import com.example.data.repository.GeminiEngine
import com.example.data.repository.HybridEngine
import com.example.util.AndroidActionExecutor
import com.example.util.AppLauncherManager
import com.example.util.CameraAiAnalyzer
import com.example.util.ContactResolver
import com.example.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Tiny manual service locator (no DI framework -> faster startup, fewer moving parts). Everything is lazy. */
object AdiGraph {
    lateinit var app: Application
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { SettingsStore(app) }
    val db by lazy { AdiDatabase.build(app) }
    val llamaManager by lazy { LlamaModelManager(app) }
    val llama by lazy { LlamaInferenceEngine(app, llamaManager) }
    val gemini by lazy { GeminiEngine(app, settings) }
    val hybrid by lazy { HybridEngine() }
    val router by lazy { BrainRouter(app, settings, gemini, llama, hybrid) }
    val launcher by lazy { AppLauncherManager(app) }
    val contacts by lazy { ContactResolver(app) }
    val executor by lazy { AndroidActionExecutor(app, launcher) }
    val vision by lazy { CameraAiAnalyzer(app, gemini) }
    val voice by lazy { VoiceManager(app) }
    val controller by lazy { AssistantController(app) }

    @Volatile var visibleActivities = 0
    val appVisible: Boolean get() = visibleActivities > 0
}

class AdiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdiGraph.app = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { AdiGraph.visibleActivities++ }
            override fun onActivityStopped(a: Activity) { AdiGraph.visibleActivities-- }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        AdiGraph.voice.initTts()
        AdiGraph.appScope.launch {
            AdiGraph.settings.settings.collect { s ->
                AdiGraph.voice.configure(s.language, s.speechRate, s.speechPitch, s.wakeWordEnabled, s.wakeWord)
            }
        }
    }

    /** Free the ~2.5 GB Llama model when the system is short on memory. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) AdiGraph.appScope.launch { AdiGraph.llama.release() }
    }
}
