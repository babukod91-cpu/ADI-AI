package com.example.util

import android.content.Context
import android.graphics.Bitmap
import com.example.data.model.LanguageMode
import com.example.data.model.VisionScanResult
import com.example.data.repository.GeminiEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

object Images {
    fun toJpeg(b: Bitmap, maxSide: Int = 1280, quality: Int = 85): ByteArray {
        val s = maxSide.toFloat() / maxOf(b.width, b.height)
        val bmp = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }
}

/**
 * Replaces v1.0's hard-coded fake results ("INVOICE #4092", "Botanical Specimen").
 * Offline: real ML Kit OCR only. Online + Gemini key: real visual understanding. It says which one it used.
 */
class CameraAiAnalyzer(private val ctx: Context, private val gemini: GeminiEngine) {
    suspend fun analyze(bmp: Bitmap, question: String, lang: LanguageMode): VisionScanResult = withContext(Dispatchers.Default) {
        val thumb = Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        val ocr = runCatching { ocr(bmp) }.getOrDefault("")
        if (gemini.status().ready) {
            val prompt = "Analyze this image for the user. Reply ONLY with JSON: {\"title\":string,\"category\":string,\"confidence\":number 0-1,\"explanation\":string," +
                "\"attributes\":[{\"key\":string,\"value\":string}],\"actions\":[string]}. User question: '${question.ifBlank { "What is this?" }}'. Answer text in: ${lang.displayName}."
            gemini.generateWithImage(prompt, Images.toJpeg(bmp), lang, json = true).getOrNull()?.let { raw ->
                runCatching {
                    val j = JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim())
                    val attrs = j.optJSONArray("attributes"); val acts = j.optJSONArray("actions")
                    return@withContext VisionScanResult(
                        title = j.optString("title", "Result"), category = j.optString("category", ""),
                        confidence = j.optDouble("confidence", 0.0).toFloat(), extractedText = ocr,
                        detailedExplanation = j.optString("explanation", ""),
                        keyAttributes = (0 until (attrs?.length() ?: 0)).map { attrs!!.getJSONObject(it).let { o -> o.optString("key") to o.optString("value") } },
                        suggestedActions = (0 until (acts?.length() ?: 0)).map { acts!!.getString(it) },
                        thumbnailBitmap = thumb, source = "Gemini vision + ML Kit OCR (confidence is model-estimated)"
                    )
                }
            }
        }
        VisionScanResult(
            title = if (ocr.isBlank()) "No text found" else "Text found (offline OCR)", category = "Text extraction",
            confidence = 0f, extractedText = ocr,
            detailedExplanation = "Offline: only on-device text extraction ran. To describe objects or scenes, connect to the internet and add a Gemini API key in Settings.",
            keyAttributes = listOf("Lines" to ocr.lines().count { it.isNotBlank() }.toString(), "Characters" to ocr.length.toString()),
            suggestedActions = if (ocr.isBlank()) emptyList() else listOf("Copy text"),
            thumbnailBitmap = thumb, source = "ML Kit OCR (on-device)"
        )
    }

    private suspend fun ocr(bmp: Bitmap): String {
        val img = InputImage.fromBitmap(bmp, 0)
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val dev = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        try {
            val a = latin.read(img)?.lines()?.toList().orEmpty(); val b = dev.read(img)?.lines()?.toList().orEmpty()
            return (a + b).distinct().joinToString("\n")
        } finally { latin.close(); dev.close() }
    }

    private fun Text.lines(): Sequence<String> = textBlocks.asSequence().flatMap { it.lines.asSequence().map { l -> l.text } }

    private suspend fun TextRecognizer.read(img: InputImage): Text? = suspendCancellableCoroutine { c ->
        process(img).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
    }
}
