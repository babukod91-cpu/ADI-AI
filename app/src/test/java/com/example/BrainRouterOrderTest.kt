package com.example

import com.example.data.model.AiEngineMode
import com.example.data.repository.BrainRouter
import com.example.data.repository.EngineId.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BrainRouterOrderTest {
    private fun o(m: AiEngineMode, rule: Boolean, g: Boolean, l: Boolean, local: Boolean = false) = BrainRouter.order(m, rule, g, l, local)

    @Test fun autoPrefersRulesForSmallTalk() = assertEquals(listOf(HYBRID, GEMINI, LLAMA), o(AiEngineMode.AUTO, true, true, true))
    @Test fun autoOnlineUsesGeminiThenLlama() = assertEquals(listOf(GEMINI, LLAMA, HYBRID), o(AiEngineMode.AUTO, false, true, true))
    @Test fun autoOfflineUsesLlama() = assertEquals(listOf(LLAMA, HYBRID), o(AiEngineMode.AUTO, false, false, true))
    @Test fun autoNothingReadyEndsAtBuiltIn() = assertEquals(listOf(HYBRID), o(AiEngineMode.AUTO, false, false, false))
    @Test fun preferLocal() = assertEquals(listOf(LLAMA, GEMINI, HYBRID), o(AiEngineMode.AUTO, false, true, true, local = true))
    @Test fun pinnedGeminiFallsBack() = assertEquals(listOf(GEMINI, LLAMA, HYBRID), o(AiEngineMode.GEMINI_CLOUD, false, false, true))
    @Test fun pinnedKeepsFirst() = assertEquals(LLAMA, o(AiEngineMode.LLAMA_3_2_3B, false, true, true).first())
}
