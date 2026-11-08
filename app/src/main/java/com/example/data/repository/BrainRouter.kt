package com.example.data.repository

import android.content.Context
import com.example.data.llama.LlamaInferenceEngine
import com.example.data.model.AiEngineMode
import com.example.data.model.LanguageMode
import com.example.data.prefs.SettingsStore

enum class EngineId { GEMINI, LLAMA, HYBRID }

data class BrainReply(val text: String?, val engine: String, val fellBack: Boolean, val error: String?)

/**
 * Picks the "AI brain" for every question (Auto mode) and falls back down the chain on failure.
 * Context is rebuilt from the same stored history for every engine, so switching never loses the conversation.
 */
class BrainRouter(
    private val ctx: Context,
    private val settings: SettingsStore,
    val gemini: GeminiEngine,
    val llama: LlamaInferenceEngine,
    val hybrid: HybridEngine
) {
    companion object {
        /** Pure ordering logic (unit-tested). */
        fun order(mode: AiEngineMode, ruleMatch: Boolean, geminiReady: Boolean, llamaReady: Boolean, preferLocal: Boolean): List<EngineId> {
            val auto = buildList {
                if (ruleMatch) add(EngineId.HYBRID)              // instant, zero battery, no data sent anywhere
                if (preferLocal) { if (llamaReady) add(EngineId.LLAMA); if (geminiReady) add(EngineId.GEMINI) }
                else { if (geminiReady) add(EngineId.GEMINI); if (llamaReady) add(EngineId.LLAMA) }
                if (!ruleMatch) add(EngineId.HYBRID)
            }
            val pinned = when (mode) {
                AiEngineMode.AUTO -> null
                AiEngineMode.ADI_HYBRID -> EngineId.HYBRID
                AiEngineMode.LLAMA_3_2_3B -> EngineId.LLAMA
                AiEngineMode.GEMINI_CLOUD -> EngineId.GEMINI
            }
            return if (pinned == null) auto else listOf(pinned) + auto.filter { it != pinned }
        }
    }

    private fun engine(id: EngineId): AiEngine = when (id) { EngineId.GEMINI -> gemini; EngineId.LLAMA -> llama; EngineId.HYBRID -> hybrid }

    suspend fun statusLines(): Map<EngineId, EngineStatus> =
        EngineId.entries.associateWith { engine(it).status() }

    suspend fun reply(prompt: String, language: LanguageMode, history: List<ChatTurn>): BrainReply {
        val s = settings.settings.value
        val st = statusLines()
        val ids = order(s.engineMode, hybrid.canHandle(prompt), st[EngineId.GEMINI]!!.ready, st[EngineId.LLAMA]!!.ready, s.preferOnDevice)
        val failures = mutableListOf<String>()
        ids.forEachIndexed { index, id ->
            val e = engine(id)
            if (!st[id]!!.ready) { failures += "${e.label}: ${st[id]!!.detail}"; return@forEachIndexed }
            val r = e.generate(prompt, language, history)
            r.getOrNull()?.let { return BrainReply(it, e.label, fellBack = index > 0, error = null) }
            failures += "${e.label}: ${r.exceptionOrNull()?.message}"
        }
        val why = failures.joinToString("; ")
        return BrainReply(null, "", false,
            "Abhi koi AI engine jawab nahi de paya ($why). Settings mein Gemini API key ya Llama model set karein - phone commands tab bhi kaam karte hain.")
    }
}
