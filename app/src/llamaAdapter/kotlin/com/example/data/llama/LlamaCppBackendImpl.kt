package com.example.data.llama

import android.llama.cpp.LLamaAndroid
import kotlinx.coroutines.flow.Flow

/**
 * Compiled only when ./llama (official llama.cpp examples/llama.android `llama` module) exists.
 * API used: LLamaAndroid.instance(), load(path), send(message, formatChat), unload().
 * If your llama.cpp checkout changed these signatures, adjust this one file.
 */
class LlamaCppBackendImpl : LlamaBackend {
    private val llama = LLamaAndroid.instance()
    override val name = "llama.cpp"
    override suspend fun load(path: String) { llama.load(path) }
    override fun generate(prompt: String): Flow<String> = llama.send(prompt, formatChat = false)
    override suspend fun unload() { llama.unload() }
}
