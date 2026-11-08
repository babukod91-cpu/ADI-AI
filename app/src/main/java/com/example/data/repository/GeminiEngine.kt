package com.example.data.repository

import android.content.Context
import android.util.Base64
import com.example.data.model.LanguageMode
import com.example.data.prefs.SettingsStore
import com.example.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Gemini via the public REST API. The key comes from encrypted storage (entered by the user), never from source. */
class GeminiEngine(private val ctx: Context, private val settings: SettingsStore) : AiEngine {
    override val label = "Gemini (cloud)"

    override suspend fun status(): EngineStatus = when {
        settings.geminiKey == null -> EngineStatus(false, "API key not set")
        !Net.isOnline(ctx) -> EngineStatus(false, "No internet")
        else -> EngineStatus(true, "Ready (${settings.settings.value.geminiModel})")
    }

    override suspend fun generate(prompt: String, language: LanguageMode, history: List<ChatTurn>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val contents = JSONArray()
                history.takeLast(10).forEach { contents.put(turn(if (it.role == "user") "user" else "model", it.text)) }
                contents.put(turn("user", prompt))
                call(contents, Prompts.system(language), json = false)
            }
        }

    /** Multimodal call (camera scan / shared screen). */
    suspend fun generateWithImage(prompt: String, jpeg: ByteArray, language: LanguageMode, json: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parts = JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
                call(contents, Prompts.system(language), json)
            }
        }

    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun call(contents: JSONArray, system: String, json: Boolean): String {
        val key = settings.geminiKey ?: throw EngineException("Gemini API key set nahi hai (Settings mein add karein)")
        val model = settings.settings.value.geminiModel.ifBlank { "gemini-2.5-flash" }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 768); put("temperature", 0.7)
                if (json) put("responseMimeType", "application/json")
            })
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 45_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)          // header, not URL, so it never lands in logs
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw EngineException(httpMessage(code, text))
            val root = JSONObject(text)
            val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
            val out = buildString { if (parts != null) for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }.trim()
            if (out.isEmpty()) {
                val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw EngineException(if (block.isNotEmpty()) "Gemini ne request block ki ($block)" else "Gemini se khaali jawab aaya")
            }
            return out
        } catch (e: UnknownHostException) { throw EngineException("Internet nahi hai")
        } catch (e: SocketTimeoutException) { throw EngineException("Gemini ne time par jawab nahi diya")
        } catch (e: IOException) { throw EngineException("Network error: ${e.message}")
        } finally { conn.disconnect() }
    }

    private fun httpMessage(code: Int, body: String): String = when {
        code == 400 && body.contains("API key not valid", true) -> "Gemini API key galat hai"
        code == 400 -> "Gemini ne request reject ki (400)"
        code == 401 || code == 403 -> "Gemini API key ko permission nahi hai ($code)"
        code == 404 -> "Gemini model naam galat hai (Settings mein model check karein)"
        code == 429 -> "Gemini quota/rate limit khatam (429)"
        code >= 500 -> "Gemini server problem ($code)"
        else -> "Gemini error $code"
    }
}
