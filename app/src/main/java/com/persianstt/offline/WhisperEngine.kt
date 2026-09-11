package com.persianstt.offline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whisper small int8 — downloaded from GitHub (not HuggingFace)
 * so it works in regions where HF is blocked.
 * Language forced to fa/en.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"
    private const val FOLDER = "whisper-small"
    // GitHub release package (same CDN as Shenava)
    private const val TAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-whisper-small.tar.bz2"

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedLang: String = ""
    @Volatile var lastError: String = ""

    private val BAD_SCRIPT = Regex(
        "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff" +
            "\\uac00-\\ud7af\\uff00-\\uffef\\u3000-\\u303f]"
    )

    private fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "whisper-models/$FOLDER")

    private fun findFile(dir: File, vararg names: String): File? {
        for (n in names) {
            val f = File(dir, n)
            if (f.exists() && f.length() > 1000) return f
        }
        dir.walkTopDown().maxDepth(4).forEach { f ->
            if (f.isFile && f.name in names && f.length() > 1000) return f
        }
        return null
    }

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        val enc = findFile(dir, "small-encoder.int8.onnx", "small-encoder.onnx")
        val dec = findFile(dir, "small-decoder.int8.onnx", "small-decoder.onnx")
        val tok = findFile(dir, "small-tokens.txt", "tokens.txt")
        return enc != null && dec != null && tok != null &&
            enc.length() > 1_000_000 && dec.length() > 1_000_000
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        // remove old base / partial
        try {
            File(context.applicationContext.filesDir, "whisper-models").listFiles()?.forEach {
                if (it.name != FOLDER) it.deleteRecursively()
            }
        } catch (_: Exception) {}

        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        val tarFile = File(dir, "whisper-small.tar.bz2")
        try {
            downloadResumable(TAR_URL, tarFile) { pct -> onProgress((pct * 85) / 100) }
            onProgress(86)
            if (!tarFile.exists() || tarFile.length() < 1_000_000) {
                lastError = "فایل دانلود ناقص است"
                throw IllegalStateException(lastError)
            }
            onProgress(88)
            extractTarBz2(tarFile, dir)
            onProgress(94)
            try { tarFile.delete() } catch (_: Exception) {}
            onProgress(96)
            flatten(dir)
            onProgress(98)
            if (!isReady(context)) {
                // list what we got for debug
                val names = dir.listFiles()?.joinToString { it.name } ?: "empty"
                lastError = "استخراج ناقص: $names"
                throw IllegalStateException(lastError)
            }
            onProgress(100)
            lastError = ""
        } catch (e: java.net.UnknownHostException) {
            lastError = "اینترنت یا DNS قطع است (github.com)"
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "خطای دانلود Whisper"
            throw e
        }
    }

    private fun flatten(dir: File) {
        dir.listFiles()?.filter { it.isDirectory }?.forEach { nested ->
            nested.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val dest = File(dir, f.name)
                    if (!dest.exists()) {
                        try { f.copyTo(dest, overwrite = false) } catch (_: Exception) {}
                    }
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
                        val base = name.substringAfterLast('/')
                        if (base.isBlank() || entry.isDirectory) {
                            entry = tarIn.nextEntry
                            continue
                        }
                        val outFile = File(destDir, base)
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
    fun load(context: Context, languageHint: String = "fa"): Boolean {
        val lang = when (languageHint.lowercase()) {
            "en", "english" -> "en"
            else -> "fa"
        }
        if (recognizer != null && loadedLang == lang) return true
        release()
        if (!isReady(context)) {
            lastError = "مدل Whisper نیست"
            return false
        }
        return try {
            val dir = modelDir(context)
            val enc = findFile(dir, "small-encoder.int8.onnx", "small-encoder.onnx")!!
            val dec = findFile(dir, "small-decoder.int8.onnx", "small-decoder.onnx")!!
            val tok = findFile(dir, "small-tokens.txt", "tokens.txt")!!
            System.gc()
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        language = lang,
                        task = "transcribe",
                        tailPaddings = 1200
                    ),
                    tokens = tok.absolutePath,
                    modelType = "whisper",
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            recognizer = OfflineRecognizer(config = config)
            loadedLang = lang
            lastError = ""
            true
        } catch (e: OutOfMemoryError) {
            lastError = "حافظه کم برای Whisper"
            Log.e(TAG, "OOM", e)
            false
        } catch (e: Exception) {
            lastError = e.message ?: "خطای Whisper"
            Log.e(TAG, "load fail", e)
            false
        }
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        loadedLang = ""
    }

    @Synchronized
    fun transcribe(pcm16: ShortArray, sampleRate: Int = 16000): String {
        val r = recognizer ?: return ""
        if (pcm16.isEmpty() || pcm16.size < sampleRate / 5) return ""
        val floats = FloatArray(pcm16.size) { i -> pcm16[i] / 32768.0f }
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(floats, sampleRate)
            r.decode(stream)
            cleanResult(r.getResult(stream).text.trim())
        } catch (_: Exception) {
            ""
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }

    private fun cleanResult(text: String): String {
        if (text.isBlank()) return ""
        if (BAD_SCRIPT.containsMatchIn(text)) return ""
        var t = text.replace(Regex("""[«»""]"""), "")
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        return NumberNormalizer.normalize(PersianPostProcess.fix(t)).trim()
    }
}
