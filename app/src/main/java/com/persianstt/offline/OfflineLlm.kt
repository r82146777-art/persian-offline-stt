package com.persianstt.offline

import android.content.Context
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline AI connection: llama.cpp + Qwen2.5-0.5B-Instruct GGUF.
 * Used for text fix + emoji after (or with) ASR.
 */
object OfflineLlm {
    private const val TAG = "OfflineLlm"
    private const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_0.gguf"
    private val URLS = listOf(
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf"
    )

    @Volatile var lastError: String = ""
    @Volatile private var modelHandle: LlamaModel? = null
    private val mutex = Mutex()

    fun modelFile(context: Context): File =
        File(context.applicationContext.filesDir, "llm-models/$MODEL_NAME")

    fun isReady(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > 50_000_000L
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        var lastEx: Exception? = null
        for (url in URLS) {
            try {
                download(url, tmp, onProgress)
                if (tmp.exists() && tmp.length() > 50_000_000L) {
                    if (dest.exists()) dest.delete()
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    onProgress(100)
                    lastError = ""
                    return
                }
            } catch (e: Exception) {
                lastEx = e
                Log.e(TAG, "download fail $url", e)
            }
        }
        lastError = lastEx?.message ?: "دانلود مدل هوش مصنوعی ناموفق"
        throw IllegalStateException(lastError)
    }

    private fun download(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        var existing = if (dest.exists()) dest.length() else 0L
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 600_000
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code == 200 && existing > 0) {
            existing = 0
            dest.delete()
        }
        if (code !in 200..299 && code != 206) throw IllegalStateException("HTTP $code")
        val totalHdr = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (code == 206 && totalHdr > 0) existing + totalHdr
        else if (totalHdr > 0) totalHdr else -1L
        BufferedInputStream(conn.inputStream, 256 * 1024).use { input ->
            FileOutputStream(dest, existing > 0 && code == 206).use { out ->
                var done = existing
                var last = -1
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
                out.flush()
            }
        }
        conn.disconnect()
    }

    suspend fun ensureLoaded(context: Context): Boolean = mutex.withLock {
        if (modelHandle != null) return true
        if (!isReady(context)) {
            lastError = "مدل هوش مصنوعی دانلود نشده — یک‌بار با اینترنت دانلود کنید"
            return false
        }
        return try {
            withContext(Dispatchers.IO) {
                System.gc()
                modelHandle = Llama.loadModel(
                    modelPath = modelFile(context).absolutePath,
                    config = LlamaConfig(contextSize = 1024, threads = 2)
                )
            }
            lastError = ""
            true
        } catch (oom: OutOfMemoryError) {
            modelHandle = null
            lastError = "حافظه کم برای هوش مصنوعی"
            Log.e(TAG, "OOM", oom)
            System.gc()
            false
        } catch (e: Throwable) {
            modelHandle = null
            lastError = e.message ?: "بارگذاری هوش مصنوعی ناموفق"
            Log.e(TAG, "load", e)
            false
        }
    }

    /** Write final text from rough speech transcript (more permissive than button-correct). */
    suspend fun typeFromSpeech(context: Context, input: String): String {
        if (input.isBlank()) return input
        if (!ensureLoaded(context)) return input
        val m = modelHandle ?: return input
        return try {
            withContext(Dispatchers.IO) {
                val result = Llama.complete(
                    m,
                    prompt = "Write this Persian speech transcript as clean typed Persian. Only the final sentence:\n$input",
                    systemPrompt = "You type clean Persian. Output only the typed text.",
                    maxTokens = 160
                )
                val out = strip(result.text)
                if (out.isNotBlank() && out.length >= input.length / 4) out else input
            }
        } catch (e: Exception) {
            lastError = e.message ?: "خطای LLM"
            Log.e(TAG, "typeFromSpeech", e)
            input
        }
    }

    suspend fun correctText(context: Context, input: String): String {
        if (input.isBlank()) return input
        if (!ensureLoaded(context)) return ""
        val m = modelHandle ?: return ""
        return try {
            withContext(Dispatchers.IO) {
                val result = Llama.complete(
                    m,
                    prompt =
                        "User text (Persian). Fix only spelling and spacing. " +
                        "If already correct, repeat it exactly. Reply with ONLY the Persian text, no English:\n" +
                        input,
                    systemPrompt =
                        "You fix Persian text. Output only the corrected Persian sentence. " +
                        "No explanation. No quotes. No English.",
                    maxTokens = 180
                )
                val out = strip(result.text)
                if (out.isBlank()) "" else out
            }
        } catch (e: Exception) {
            lastError = e.message ?: "خطای هوش مصنوعی"
            Log.e(TAG, "correct", e)
            ""
        }
    }

    suspend fun addEmojis(context: Context, input: String): String {
        if (input.isBlank()) return input
        if (!ensureLoaded(context)) return ""
        val m = modelHandle ?: return ""
        return try {
            withContext(Dispatchers.IO) {
                val result = Llama.complete(
                    m,
                    prompt =
                        "Add 1 or 2 suitable emojis to this Persian text. " +
                        "Keep every Persian word the same. Only insert emojis. " +
                        "Reply with ONLY the final text:\n" + input,
                    systemPrompt =
                        "You only insert emojis into Persian text. Do not rewrite words. " +
                        "Output only the final text with emojis.",
                    maxTokens = 180
                )
                val out = strip(result.text)
                // must still look like the input (contain most of original words)
                if (out.isBlank()) return@withContext ""
                if (!keepsWords(input, out)) return@withContext ""
                // prefer if has emoji-like chars
                out
            }
        } catch (e: Exception) {
            lastError = e.message ?: "خطای هوش مصنوعی"
            Log.e(TAG, "emoji", e)
            ""
        }
    }

    private fun keepsWords(original: String, candidate: String): Boolean {
        val oWords = original.split(Regex("\\s+")).filter { it.length > 1 }
        if (oWords.isEmpty()) return true
        val hit = oWords.count { candidate.contains(it) }
        return hit * 2 >= oWords.size
    }

    private fun strip(raw: String): String {
        var t = raw.trim().removePrefix("```").removeSuffix("```").trim()
        t = t.replace(
            Regex(
                "^(متن اصلاح[‌ ]*شده[:：]?\\s*|خروجی[:：]?\\s*|نتیجه[:：]?\\s*|" +
                    "Corrected[:：]?\\s*|Here[:：]?\\s*)",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        t = t.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: t
        return t.trim()
    }

    fun release() {
        try {
            val m = modelHandle
            modelHandle = null
            if (m != null) Llama.releaseModel(m)
        } catch (_: Exception) {}
    }
}
