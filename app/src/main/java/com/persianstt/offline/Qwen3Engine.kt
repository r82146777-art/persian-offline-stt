package com.persianstt.offline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
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
 * Qwen3-ASR 0.6B int8 — multilingual including Persian (fa).
 * Official sherpa-onnx package from GitHub (not HuggingFace).
 */
object Qwen3Engine {

    private const val TAG = "Qwen3Engine"
    private const val FOLDER = "qwen3-asr-0.6b"
    private const val TAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile var lastError: String = ""

    private val BAD_SCRIPT = Regex(
        "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff" +
            "\\uac00-\\ud7af\\uff00-\\uffef\\u3000-\\u303f]"
    )

    private fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "qwen3-models/$FOLDER")

    private fun find(dir: File, vararg names: String): File? {
        for (n in names) {
            val f = File(dir, n)
            if (f.exists() && f.length() > 1000) return f
            dir.walkTopDown().maxDepth(4).forEach { c ->
                if (c.isFile && c.name == n && c.length() > 1000) return c
            }
        }
        return null
    }

    private fun findTokenizerDir(dir: File): File? {
        val t = File(dir, "tokenizer")
        if (t.isDirectory && File(t, "vocab.json").exists()) return t
        dir.walkTopDown().maxDepth(4).forEach { c ->
            if (c.isDirectory && c.name == "tokenizer" && File(c, "vocab.json").exists()) return c
        }
        return null
    }

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        val conv = find(dir, "conv_frontend.onnx")
        val enc = find(dir, "encoder.int8.onnx", "encoder.onnx")
        val dec = find(dir, "decoder.int8.onnx", "decoder.onnx")
        val tok = findTokenizerDir(dir)
        return conv != null && enc != null && dec != null && tok != null &&
            (enc.length() > 50_000_000) && (dec.length() > 50_000_000)
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        val tarFile = File(dir, "qwen3.tar.bz2")
        try {
            downloadResumable(TAR_URL, tarFile) { pct -> onProgress((pct * 85) / 100) }
            onProgress(86)
            if (!tarFile.exists() || tarFile.length() < 10_000_000) {
                lastError = "دانلود ناقص Qwen3"
                throw IllegalStateException(lastError)
            }
            onProgress(88)
            extractTarBz2(tarFile, dir)
            onProgress(94)
            try { tarFile.delete() } catch (_: Exception) {}
            flatten(dir)
            onProgress(98)
            if (!isReady(context)) {
                val names = dir.listFiles()?.joinToString { it.name } ?: "empty"
                lastError = "استخراج ناقص: $names"
                throw IllegalStateException(lastError)
            }
            onProgress(100)
            lastError = ""
        } catch (e: java.net.UnknownHostException) {
            lastError = "اینترنت/DNS قطع (github.com)"
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "خطای دانلود Qwen3"
            throw e
        }
    }

    private fun flatten(dir: File) {
        dir.listFiles()?.filter { it.isDirectory && it.name.startsWith("sherpa-onnx") }?.forEach { nested ->
            nested.listFiles()?.forEach { f ->
                val dest = File(dir, f.name)
                if (!dest.exists()) {
                    try {
                        if (f.isDirectory) f.copyRecursively(dest)
                        else f.copyTo(dest, overwrite = false)
                    } catch (_: Exception) {}
                }
            }
            try { nested.deleteRecursively() } catch (_: Exception) {}
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
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val totalFromHeader = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (code == 206 && totalFromHeader > 0) existing + totalFromHeader
        else if (totalFromHeader > 0) totalFromHeader else -1L
        val input = BufferedInputStream(conn.inputStream, 64 * 1024)
        val out = FileOutputStream(tmp, existing > 0 && code == 206)
        var done = existing
        var lastPct = -1
        val buf = ByteArray(256 * 1024)
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
                    val buf = ByteArray(256 * 1024)
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        // keep relative structure for tokenizer/
                        val relative = name.substringAfter("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/")
                            .ifBlank { name.substringAfterLast('/') }
                        if (relative.isBlank() || entry.isDirectory) {
                            if (entry.isDirectory && relative.isNotBlank()) {
                                File(destDir, relative).mkdirs()
                            }
                            entry = tarIn.nextEntry
                            continue
                        }
                        val outFile = File(destDir, relative)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            var n: Int
                            while (tarIn.read(buf).also { n = it } > 0) out.write(buf, 0, n)
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
            lastError = "مدل Qwen3 نیست"
            return false
        }
        return try {
            val dir = modelDir(context)
            val conv = find(dir, "conv_frontend.onnx")!!
            val enc = find(dir, "encoder.int8.onnx", "encoder.onnx")!!
            val dec = find(dir, "decoder.int8.onnx", "decoder.onnx")!!
            val tok = findTokenizerDir(dir)!!
            System.gc()
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = conv.absolutePath,
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        tokenizer = tok.absolutePath,
                        maxNewTokens = 128,
                        temperature = 1e-6f
                    ),
                    tokens = "",
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            recognizer = OfflineRecognizer(config = config)
            lastError = ""
            true
        } catch (e: OutOfMemoryError) {
            lastError = "حافظه کم برای Qwen3 (~۱ گیگ رم آزاد لازم)"
            Log.e(TAG, "OOM", e)
            false
        } catch (e: Exception) {
            lastError = e.message ?: "خطای بارگذاری Qwen3"
            Log.e(TAG, "load fail", e)
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
            cleanResult(r.getResult(stream).text.trim())
        } catch (e: Exception) {
            Log.e(TAG, "transcribe", e)
            ""
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }

    private fun cleanResult(text: String): String {
        if (text.isBlank()) return ""
        if (BAD_SCRIPT.containsMatchIn(text)) return ""
        var t = text
        // strip language tags sometimes produced
        t = t.replace(Regex("""^\s*(language|lang)\s*:\s*\w+\s*""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""[«»""]"""), "")
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        return NumberNormalizer.normalize(PersianPostProcess.fix(t)).trim()
    }
}
