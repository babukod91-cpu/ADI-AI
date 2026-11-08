package com.example.data.llama

import kotlinx.coroutines.flow.Flow

/**
 * Seam for the native llama.cpp runtime. v1.0 had NO native runtime (its "Llama" answers were canned strings).
 * To enable real inference, add the official llama.cpp Android module as ./llama (see README) -
 * then LlamaCppBackendImpl (src/llamaAdapter) is compiled in and found here by reflection.
 */
interface LlamaBackend {
    val name: String
    suspend fun load(path: String)
    fun generate(prompt: String): Flow<String>
    suspend fun unload()
}

object LlamaBackendFactory {
    fun create(): LlamaBackend? = try {
        Class.forName("com.example.data.llama.LlamaCppBackendImpl").getDeclaredConstructor().newInstance() as LlamaBackend
    } catch (t: Throwable) { null }
}
