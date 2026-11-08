package com.example.data.llama

import android.content.Context
import com.example.data.model.LanguageMode
import com.example.data.model.LlamaState
import com.example.data.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Real on-device engine. Reports "unavailable" honestly if the native backend or model is missing. */
class LlamaInferenceEngine(private val ctx: Context, val manager: LlamaModelManager) : AiEngine {
    override val label = "Llama 3.2 3B (on-device)"
    private val backend: LlamaBackend? = LlamaBackendFactory.create()
    private val mutex = Mutex()
    @Volatile private var loaded = false

    val nativeAvailable: Boolean get() = backend != null

    override suspend fun status(): EngineStatus {
        val s = manager.status.value
        return when {
            backend == null -> EngineStatus(false, "llama.cpp native runtime not bundled (see README)")
            !s.isSupportedOnDevice -> EngineStatus(false, s.errorMessage ?: "Device not supported")
            s.state == LlamaState.DOWNLOADING -> EngineStatus(false, "Downloading ${(s.downloadProgress * 100).toInt()}%")
            s.state == LlamaState.NOT_DOWNLOADED -> EngineStatus(false, "Model not downloaded (~2 GB)")
            s.state == LlamaState.ERROR -> EngineStatus(false, s.errorMessage ?: "Error")
            else -> EngineStatus(true, if (loaded) "Loaded in RAM" else "Downloaded (loads on first use)")
        }
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (!loaded) {
            try {
                backend!!.load(manager.modelFile.absolutePath)
                loaded = true; manager.markReady()
            } catch (t: Throwable) { manager.markError("Model load failed: ${t.message}"); throw EngineException("Llama model load nahi hua: ${t.message}") }
        }
    }

    override suspend fun generate(prompt: String, language: LanguageMode, history: List<ChatTurn>): Result<String> =
        withContext(Dispatchers.Default) {
            runCatching {
                if (!status().ready) throw EngineException("Llama abhi ready nahi: ${status().detail}")
                ensureLoaded()
                val sb = StringBuilder()
                withTimeout(90_000) {
                    backend!!.generate(buildPrompt(prompt, language, history)).takeWhile { piece ->
                        sb.append(piece); sb.length < 1600 && !sb.contains("<|eot_id|>")
                    }.collect { }
                }
                val out = sb.toString().substringBefore("<|eot_id|>").trim()
                if (out.isEmpty()) throw EngineException("Llama ne khaali jawab diya")
                out
            }
        }

    /** Llama-3 chat template. History is trimmed to ~3000 chars to protect RAM/latency on small devices. */
    fun buildPrompt(prompt: String, language: LanguageMode, history: List<ChatTurn>): String {
        val sb = StringBuilder("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
        sb.append(Prompts.system(language)).append("<|eot_id|>\n")
        var budget = 3000
        val kept = ArrayDeque<ChatTurn>()
        for (t in history.asReversed()) { if (budget - t.text.length < 0) break; budget -= t.text.length; kept.addFirst(t) }
        kept.forEach { t ->
            sb.append("<|start_header_id|>").append(if (t.role == "user") "user" else "assistant").append("<|end_header_id|>\n\n")
                .append(t.text).append("<|eot_id|>\n")
        }
        sb.append("<|start_header_id|>user<|end_header_id|>\n\n").append(prompt).append("<|eot_id|>\n")
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    /** Free ~2.5 GB when the system is short on memory. */
    suspend fun release() = mutex.withLock {
        if (loaded) { runCatching { backend?.unload() }; loaded = false; manager.markUnloaded() }
    }
}
