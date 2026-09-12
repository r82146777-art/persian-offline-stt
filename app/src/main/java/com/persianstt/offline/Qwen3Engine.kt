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
 * Qwen3-ASR 0.6B int8 — memory-safe extract + lazy load.
 * Crash during "آماده‌سازی" was usually OOM while extracting/loading ~1GB model.
 */
object Qwen3Engine {

    private const val TAG = "Qwen3Engine"
    private const val FOLDER = "qwen3-asr-0.6b"
    private const val TAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile var lastError: String = ""
    @Volatile var lastPhase: String = ""

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
        }
        dir.walkTopDown().maxDepth(4).forEach { c ->
            if (c.isFile && c.name in names && c.length() > 1000) return c
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
            enc!!.length() > 50_000_000 && dec!!.length() > 50_000_000
    }

    /**
     * Download + extract only. Does NOT load the neural net into RAM.
     * Progress: 0-85 download, 86-99 extract, 100 files ready.
     */
    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            lastPhase = "ready"
            return
        }
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        val tarFile = File(dir, "qwen3.tar.bz2")
        try {
            lastPhase = "download"
            // Resume incomplete download
            downloadResumable(TAR_URL, tarFile) { pct ->
                onProgress((pct * 85) / 100)
            }
            onProgress(86)
            if (!tarFile.exists() || tarFile.length() < 50_000_000) {
                lastError = "دانلود ناقص است — دوباره تلاش کنید"
                throw IllegalStateException(lastError)
            }

            lastPhase = "extract"
            // Extract with progress 86..98
            extractTarBz2(tarFile, dir) { extractPct ->
                onProgress(86 + (extractPct * 12) / 100)
            }
            onProgress(98)

            // Free disk ASAP before any load
            try {
                if (tarFile.exists()) tarFile.delete()
            } catch (_: Exception) {}
            // leftover part files
            try {
                File(dir, "qwen3.tar.bz2.part").delete()
            } catch (_: Exception) {}

            System.gc()
            onProgress(99)

            if (!isReady(context)) {
                val names = dir.walkTopDown().maxDepth(3)
                    .filter { it.isFile }
                    .map { "${it.name}:${it.length() / 1_000_000}M" }
                    .joinToString()
                lastError = "استخراج ناقص ($names)"
                throw IllegalStateException(lastError)
            }
            onProgress(100)
            lastError = ""
            lastPhase = "extracted"
            Log.i(TAG, "model files ready (not loaded yet)")
        } catch (e: java.net.UnknownHostException) {
            lastError = "اینترنت/DNS قطع (github.com)"
            throw e
        } catch (e: OutOfMemoryError) {
            lastError = "حافظه کافی نیست هنگام استخراج — برنامه‌های دیگر را ببندید"
            try { System.gc() } catch (_: Throwable) {}
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "خطای دانلود/استخراج"
            throw e
        }
    }

    private fun downloadResumable(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val tmp = File(dest.absolutePath + ".part")
        var existing = if (tmp.exists()) tmp.length() else 0L
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 45_000
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
        BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
            FileOutputStream(tmp, existing > 0 && code == 206).use { out ->
                var done = existing
                var lastPct = -1
                val buf = ByteArray(64 * 1024) // smaller buffer = less peak RAM
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
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun extractTarBz2(tarBz2: File, destDir: File, onProgress: (Int) -> Unit = {}) {
        val totalBytes = tarBz2.length().coerceAtLeast(1)
        var readCompressedApprox = 0L
        // Approximate progress from stream position is hard with bzip2;
        // count entries processed instead.
        var entries = 0
        val expectedEntries = 8 // rough

        FileInputStream(tarBz2).use { fis ->
            BZip2CompressorInputStream(BufferedInputStream(fis, 32 * 1024)).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    val buf = ByteArray(32 * 1024)
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        val relative = when {
                            name.contains("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/") ->
                                name.substringAfter("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/")
                            else -> name.substringAfterLast('/')
                        }
                        if (relative.isBlank()) {
                            entry = tarIn.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            File(destDir, relative).mkdirs()
                            entry = tarIn.nextEntry
                            continue
                        }
                        val outFile = File(destDir, relative)
                        outFile.parentFile?.mkdirs()
                        // Skip already-complete large files (resume extract)
                        if (outFile.exists() && outFile.length() == entry.size && entry.size > 0) {
                            tarIn.skip(entry.size)
                            entries++
                            onProgress(((entries * 100) / expectedEntries).coerceIn(0, 99))
                            entry = tarIn.nextEntry
                            continue
                        }
                        FileOutputStream(outFile).use { out ->
                            var n: Int
                            while (tarIn.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n)
                                readCompressedApprox += n
                            }
                            out.flush()
                        }
                        // Drop RAM pressure after huge onnx files
                        if (outFile.length() > 50_000_000) {
                            try { System.gc() } catch (_: Throwable) {}
                        }
                        entries++
                        onProgress(((entries * 100) / expectedEntries).coerceIn(0, 99))
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
        // Move nested folder contents up if needed
        destDir.listFiles()?.filter {
            it.isDirectory && it.name.startsWith("sherpa-onnx-qwen3")
        }?.forEach { nested ->
            nested.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(nested).path
                    val dest = File(destDir, rel)
                    if (!dest.exists()) {
                        dest.parentFile?.mkdirs()
                        try {
                            f.copyTo(dest, overwrite = false)
                        } catch (_: Exception) {}
                    }
                }
            }
            try { nested.deleteRecursively() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun load(context: Context): Boolean {
        if (recognizer != null) return true
        if (!isReady(context)) {
            lastError = "فایل‌های مدل کامل نیست"
            return false
        }
        lastPhase = "load"
        return try {
            // Free other engines if still held
            try { WhisperEngine.release() } catch (_: Throwable) {}
            try { ShenavaEngine.release() } catch (_: Throwable) {}
            System.gc()
            Thread.sleep(200)

            val dir = modelDir(context)
            val conv = find(dir, "conv_frontend.onnx")!!
            val enc = find(dir, "encoder.int8.onnx", "encoder.onnx")!!
            val dec = find(dir, "decoder.int8.onnx", "decoder.onnx")!!
            val tok = findTokenizerDir(dir)!!

            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = conv.absolutePath,
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        tokenizer = tok.absolutePath,
                        maxNewTokens = 96,
                        temperature = 1e-6f
                    ),
                    tokens = "",
                    numThreads = 1, // lower peak RAM
                    provider = "cpu"
                )
            )
            recognizer = OfflineRecognizer(config = config)
            lastError = ""
            lastPhase = "loaded"
            true
        } catch (e: OutOfMemoryError) {
            recognizer = null
            lastError = "حافظه کافی نیست. برنامه‌های دیگر را ببندید و دوباره باز کنید"
            Log.e(TAG, "OOM on load", e)
            try { System.gc() } catch (_: Throwable) {}
            false
        } catch (e: Throwable) {
            recognizer = null
            lastError = e.message ?: "خطای بارگذاری"
            Log.e(TAG, "load fail", e)
            false
        }
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        lastPhase = "released"
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
        } catch (e: OutOfMemoryError) {
            lastError = "حافظه کم هنگام تشخیص"
            ""
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
        t = t.replace(Regex("""^\s*(language|lang)\s*:\s*\w+\s*""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""[«»""]"""), "")
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        return NumberNormalizer.normalize(PersianPostProcess.fix(t)).trim()
    }
}
