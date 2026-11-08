package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AdiGraph
import com.example.data.model.AiEngineMode
import com.example.data.model.LanguageMode
import com.example.data.model.VoiceSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** Thin UI wrapper: all logic lives in AssistantController so the service/bubble share it. */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    val c = AdiGraph.controller
    val settings = AdiGraph.settings.settings
    val keyPresent = AdiGraph.settings.keyPresent
    val llama = AdiGraph.llamaManager.status
    val voiceStatus = AdiGraph.voice.status
    val messages = AdiGraph.db.dao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun update(t: (VoiceSettings) -> VoiceSettings) = AdiGraph.settings.update(t)
    fun setEngine(m: AiEngineMode) = update { it.copy(engineMode = m) }
    fun setLanguage(l: LanguageMode) = update { it.copy(language = l) }
    fun saveKey(k: String?) { AdiGraph.settings.geminiKey = k }
    val secureStorageAvailable get() = AdiGraph.settings.secureStorageAvailable
    suspend fun engineStatuses() = AdiGraph.router.statusLines()
    val llamaNativeAvailable get() = AdiGraph.llama.nativeAvailable
    fun downloadLlama() = AdiGraph.llamaManager.startModelDownload()
    fun cancelLlama() = AdiGraph.llamaManager.cancelDownload()
    fun deleteLlama() {
        AdiGraph.appScope.launch { AdiGraph.llama.release(); AdiGraph.llamaManager.deleteModel() }
    }
}
