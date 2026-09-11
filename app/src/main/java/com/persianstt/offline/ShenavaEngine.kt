package com.persianstt.offline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shenava Koochik int8 — stable on phones (full FP model OOMs on many devices).
 * ~95–100 MB on disk. Persian-only FastConformer CTC.
 */
object ShenavaEngine {

    private const val TAG = "ShenavaEngine"
    private const val FOLDER = "shenava-koochik-int8"
    private const val TOKENS = "tokens.txt"
    private const val TAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-nemo-ctc-fa-shenava-koochik-v1.0-non-streaming-int8-2026-06-26.tar.bz2"

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile var lastError: String = ""
        private set

    private fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "shenava-models/$FOLDER")

    private fun findModelFile(dir: File): File? {
        val names = listOf("model.int8.onnx", "model.onnx")
        for (name in names) {
            val f = File(dir, name)
            if (f.exists() && f.length() > 10_000_000) return f
        }
        dir.walkTopDown().maxDepth(3).forEach { f ->
            if (f.isFile && f.name.endsWith(".onnx") && f.length() > 10_000_000) return f
        }
        return null
    }

    private fun findTokens(dir: File): File? {
        val t = File(dir, TOKENS)
        if (t.exists() && t.length() > 100) return t
        return dir.walkTopDown().maxDepth(3).firstOrNull {
            it.isFile && it.name == TOKENS && it.length() > 100
        }
    }

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return findModelFile(dir) != null && findTokens(dir) != null
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        // remove other shenava folders (old full / rizeh)
        try {
            val root = File(context.applicationContext.filesDir, "shenava-models")
            root.listFiles()?.forEach { f ->
                if (f.name != FOLDER) f.deleteRecursively()
            }
        } catch (_: Exception) {}

        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        val tarFile = File(dir, "model.tar.bz2")
        try {
            downloadResumable(TAR_URL, tarFile) { pct -> onProgress((pct * 88) / 100) }
            onProgress(90)
            extractTarBz2(tarFile, dir)
            onProgress(96)
            try { tarFile.delete() } catch (_: Exception) {}
            flatten(dir)
            onProgress(99)
            if (!isReady(context)) {
                lastError = "استخراج مدل ناقص بود"
                dir.deleteRecursively()
                throw IllegalStateException(lastError)
            }
            onProgress(100)
        } catch (e: java.net.UnknownHostException) {
            lastError = "اینترنت یا DNS قطع است (github.com را چک کنید)"
            throw e
        } catch (e: OutOfMemoryError) {
            lastError = "حافظه کافی نیست برای آماده‌سازی مدل"
            try { dir.deleteRecursively() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "خطای دانلود/استخراج"
            throw e
        }
    }

    private fun flatten(dir: File) {
        val nested = dir.listFiles()?.filter { it.isDirectory } ?: return
        for (n in nested) {
            n.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val dest = File(dir, f.name)
                    if (!dest.exists()) {
                        try { f.copyTo(dest, overwrite = false) } catch (_: Exception) {}
                    }
                }
            }
            try { n.deleteRecursively() } catch (_: Exception) {}
        }
    }

    private fun downloadResumable(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val tmp = File(dest.absolutePath + ".part")
        var existing = if (tmp.exists()) tmp.length() else 0L
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
            tmp.delete()
        }
        if (code !in 200..299) {
            throw IllegalStateException("دانلود ناموفق: HTTP $code")
        }
        val totalFromHeader = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (code == 206 && totalFromHeader > 0) existing + totalFromHeader
        else if (totalFromHeader > 0) totalFromHeader
        else -1L
        val input = BufferedInputStream(conn.inputStream, 64 * 1024)
        val out = if (existing > 0 && code == 206) FileOutputStream(tmp, true) else FileOutputStream(tmp, false)
        var done = existing
        var lastPct = -1
        val buf = ByteArray(128 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                done += n
                if (total > 0) {
                    val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
            out.flush()
        } finally {
            try { out.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
            conn.disconnect()
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun extractTarBz2(tarBz2: File, destDir: File) {
        FileInputStream(tarBz2).use { fis ->
            BZip2CompressorInputStream(BufferedInputStream(fis, 64 * 1024)).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    val buf = ByteArray(128 * 1024)
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        // only keep file name (flatten)
                        val base = name.substringAfterLast('/')
                        if (base.isBlank() || entry.isDirectory) {
                            entry = tarIn.nextEntry
                            continue
                        }
                        val outFile = File(destDir, base)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            var n: Int
                            while (tarIn.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n)
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
    }

    @Synchronized
    fun load(context: Context): Boolean {
        if (recognizer != null) return true
        if (!isReady(context)) {
            lastError = "مدل روی دیسک نیست"
            return false
        }
        return try {
            val dir = modelDir(context)
            val modelFile = findModelFile(dir) ?: run {
                lastError = "فایل model پیدا نشد"
                return false
            }
            val tokensFile = findTokens(dir) ?: run {
                lastError = "فایل tokens پیدا نشد"
                return false
            }
            Log.i(TAG, "loading ${modelFile.name} size=${modelFile.length()}")
            System.gc()
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        model = modelFile.absolutePath
                    ),
                    tokens = tokensFile.absolutePath,
                    modelType = "nemo_ctc",
                    numThreads = 2,
                    provider = "cpu"
                ),
                decodingMethod = "greedy_search"
            )
            recognizer = OfflineRecognizer(config = config)
            lastError = ""
            true
        } catch (oom: OutOfMemoryError) {
            lastError = "حافظه کم است — برنامه را ببندید و دوباره باز کنید"
            Log.e(TAG, "OOM loading model", oom)
            recognizer = null
            false
        } catch (e: Exception) {
            lastError = e.message ?: "خطای بارگذاری مدل"
            Log.e(TAG, "load failed", e)
            recognizer = null
            false
        }
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }

    @Synchronized
    fun transcribe(pcm16: ShortArray, sampleRate: Int = 16000): String {
        val r = recognizer ?: return ""
        if (pcm16.isEmpty() || pcm16.size < sampleRate / 6) return ""
        val floats = FloatArray(pcm16.size) { i -> pcm16[i] / 32768.0f }
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(floats, sampleRate)
            r.decode(stream)
            val raw = r.getResult(stream).text.trim()
            NumberNormalizer.normalize(PersianPostProcess.fix(raw))
        } catch (oom: OutOfMemoryError) {
            lastError = "حافظه کم هنگام تشخیص"
            ""
        } catch (_: Exception) {
            ""
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }
}
