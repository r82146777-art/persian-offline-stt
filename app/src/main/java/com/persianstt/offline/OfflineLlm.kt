package com.persianstt.offline

import android.content.Context
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
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
 * Real offline LLM package: llama.cpp (Maven AAR) + Qwen2.5-0.5B-Instruct GGUF.
 * Used for text correction and emoji — not hand-written rules.
 */
object OfflineLlm {
    private const val TAG = "OfflineLlm"
    private const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_0.gguf"
    // Hugging Face direct + mirror
    private val URLS = listOf(
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf"
    )

    @Volatile var lastError: String = ""
    @Volatile private var modelHandle: Any? = null
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
        lastError = lastEx?.message ?: "دانلود مدل LLM ناموفق"
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
            lastError = "مدل LLM دانلود نشده"
            return false
        }
        return try {
            withContext(Dispatchers.IO) {
                System.gc()
                val path = modelFile(context).absolutePath
                modelHandle = Llama.loadModel(
                    modelPath = path,
                    config = LlamaConfig(contextSize = 1024, threads = 2)
                )
            }
            lastError = ""
            true
        } catch (oom: OutOfMemoryError) {
            modelHandle = null
            lastError = "حافظه کم برای LLM — برنامه‌ها را ببندید"
            Log.e(TAG, "OOM", oom)
            System.gc()
            false
        } catch (e: Throwable) {
            modelHandle = null
            lastError = e.message ?: "بارگذاری LLM ناموفق"
            Log.e(TAG, "load", e)
            false
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
                    prompt = "متن فارسی زیر را فقط از نظر املا و فاصله اصلاح کن. " +
                        "اگر درست است همان را برگردان. بدون توضیح:\n$input",
                    systemPrompt = "تو ویرایشگر فارسی هستی. فقط متن نهایی را بنویس.",
                    maxTokens = 256
                )
                cleanOutput(result.text, input)
            }
        } catch (e: Exception) {
            lastError = e.message ?: "خطای LLM"
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
                    prompt = "به این متن فارسی ایموجی مناسب اضافه کن؛ جملات را عوض نکن. فقط متن نهایی:\n$input",
                    systemPrompt = "فقط خروجی نهایی را بنویس.",
                    maxTokens = 256
                )
                cleanOutput(result.text, input).ifBlank { input }
            }
        } catch (e: Exception) {
            lastError = e.message ?: "خطای LLM"
            Log.e(TAG, "emoji", e)
            ""
        }
    }

    private fun cleanOutput(raw: String, fallback: String): String {
        var t = raw.trim()
            .removePrefix("```").removeSuffix("```").trim()
        t = t.replace(Regex("^(متن اصلاح[‌ ]*شده[:：]?\\s*|خروجی[:：]?\\s*|نتیجه[:：]?\\s*)"), "")
        if (t.isBlank()) return fallback
        return t
    }

    fun release() {
        try {
            val m = modelHandle
            modelHandle = null
            if (m != null) Llama.releaseModel(m)
        } catch (_: Exception) {}
    }
}
