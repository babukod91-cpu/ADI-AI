package com.example.data.repository

import com.example.data.model.LanguageMode

data class ChatTurn(val role: String, val text: String)          // role: "user" | "assistant"
data class EngineStatus(val ready: Boolean, val detail: String)
class EngineException(message: String) : Exception(message)

interface AiEngine {
    val label: String
    /** Real status - never a fake "success". */
    suspend fun status(): EngineStatus
    suspend fun generate(prompt: String, language: LanguageMode, history: List<ChatTurn>): Result<String>
}

object Prompts {
    fun system(language: LanguageMode): String =
        "You are ADI AI, a cheerful, sweet, and intelligent Indian female AI companion. " +
        "You speak naturally in Hindi, English, Hinglish, Bengali, Santali, and regional languages. " +
        "Current language mode: ${language.displayName}. Answer naturally and concisely without robotic jargon. " +
        "Reply in the same language and script as the user. Keep answers to a few sentences unless asked for more. " +
        "You cannot perform phone actions yourself (the app does that); if you are unsure of a fact, say so."
}
