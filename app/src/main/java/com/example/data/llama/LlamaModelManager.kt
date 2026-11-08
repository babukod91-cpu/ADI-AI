package com.example.data.llama

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.data.model.LlamaModelStatus
import com.example.data.model.LlamaState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Downloads (resumable), validates and tracks the Llama 3.2 3B Q4_K_M GGUF file. Wi-Fi recommended (~2 GB). */
class LlamaModelManager(private val ctx: Context) {
    companion object {
        const val MODEL_FILE = "Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        const val MODEL_URL = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        const val MODEL_SIZE_MB = 2020
        private const val MIN_VALID_BYTES = 1_900_000_000L
        private const val TAG = "LlamaModelManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow(compute())
    val status: StateFlow<LlamaModelStatus> = _status

    val modelFile: File get() = File(File(ctx.filesDir, "models").apply { mkdirs() }, MODEL_FILE)
    private val partFile: File get() = File(modelFile.parentFile, "$MODEL_FILE.part")

    private fun ram(): Pair<Float, Float> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Pair(mi.totalMem / 1e9f, mi.availMem / 1e9f)
    }

    private fun freeGb(): Float = StatFs(ctx.filesDir.absolutePath).availableBytes / 1e9f

    private fun compute(): LlamaModelStatus {
        val (total, avail) = ram()
        val free = freeGb()
        val downloaded = modelFile.exists() && modelFile.length() >= MIN_VALID_BYTES
        val supported = total >= 3.5f && (downloaded || free >= 2.4f)
        return LlamaModelStatus(
            isSupportedOnDevice = supported, totalRamGb = round2(total), availableRamGb = round2(avail),
            freeStorageGb = round2(free),
            state = if (downloaded) LlamaState.DOWNLOADED else LlamaState.NOT_DOWNLOADED,
            modelFileName = MODEL_FILE, modelSizeMb = MODEL_SIZE_MB,
            errorMessage = if (!supported) "Needs >= 3.5 GB RAM (6 GB recommended) and ~2.4 GB free storage" else null
        )
    }

    private fun round2(f: Float) = (f * 100).toInt() / 100f

    fun refreshStatus() { if (_status.value.state != LlamaState.DOWNLOADING && _status.value.state != LlamaState.READY) _status.value = compute() }
    fun markReady() { _status.value = _status.value.copy(state = LlamaState.READY, errorMessage = null) }
    fun markUnloaded() { _status.value = compute() }
    fun markError(msg: String) { _status.value = _status.value.copy(state = LlamaState.ERROR, errorMessage = msg) }

    fun startModelDownload() {
        if (job?.isActive == true) return
        val s = compute()
        if (!s.isSupportedOnDevice) { _status.value = s.copy(state = LlamaState.ERROR); return }
        _status.value = s.copy(state = LlamaState.DOWNLOADING, downloadProgress = 0f, errorMessage = null)
        job = scope.launch {
            try { download(); _status.value = compute() }
            catch (e: CancellationException) { _status.value = compute().copy(errorMessage = "Download paused (resumes from where it stopped)"); throw e }
            catch (e: Exception) { Log.w(TAG, "download failed", e); markError("Download failed: ${e.message}") }
        }
    }

    fun cancelDownload() { job?.cancel() }

    fun deleteModel() {
        job?.cancel()
        if (!modelFile.delete() && modelFile.exists()) Log.w(TAG, "Failed deleting model file")
        partFile.delete()
        _status.value = compute()
    }

    private suspend fun download() {
        var url = MODEL_URL
        val existing = if (partFile.exists()) partFile.length() else 0L
        var hops = 0
        var cc: HttpURLConnection? = null
        while (true) {
            val h = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val rc = h.responseCode
            if (rc in 300..399 && hops++ < 5) {
                url = h.getHeaderField("Location") ?: throw java.io.IOException("redirect without Location")
                h.disconnect(); continue
            }
            cc = h; break
        }
        val c = cc!!
        val code = c.responseCode
        if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
        val resume = code == 206 && existing > 0
        val startAt = if (resume) existing else 0L
        val total = startAt + c.contentLengthLong.coerceAtLeast(0)
        RandomAccessFile(partFile, "rw").use { raf ->
            if (!resume) raf.setLength(0)
            raf.seek(startAt)
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var done = startAt; var lastEmit = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    raf.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 400 && total > 0) {
                        lastEmit = now
                        _status.value = _status.value.copy(downloadProgress = (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        c.disconnect()
        if (partFile.length() < MIN_VALID_BYTES) throw java.io.IOException("File incomplete (${partFile.length() / 1_000_000} MB)")
        modelFile.delete()
        if (!partFile.renameTo(modelFile)) throw java.io.IOException("Could not finalize model file")
    }
}
