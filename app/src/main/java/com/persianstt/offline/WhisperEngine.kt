package com.persianstt.offline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whisper **small** int8, language forced to fa (or en).
 * Better word boundaries than pure CTC for short phrases.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"
    private const val MODEL_DIR = "whisper-small"
    private const val ENC = "small-encoder.int8.onnx"
    private const val DEC = "small-decoder.int8.onnx"
    private const val TOK = "small-tokens.txt"

    private val FILES = mapOf(
        ENC to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
        DEC to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
        TOK to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt"
    )

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedLang: String = ""
    @Volatile var lastError: String = ""

    private val BAD_SCRIPT = Regex(
        "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff" +
            "\\uac00-\\ud7af\\uff00-\\uffef\\u3000-\\u303f]"
    )

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "whisper-models/$MODEL_DIR")

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, ENC).exists() && File(dir, ENC).length() > 1_000_000 &&
            File(dir, DEC).exists() && File(dir, DEC).length() > 1_000_000 &&
            File(dir, TOK).exists()
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        // wipe old base
        try {
            File(context.applicationContext.filesDir, "whisper-models/whisper-base")
                .deleteRecursively()
        } catch (_: Exception) {}
        val entries = FILES.entries.toList()
        entries.forEachIndexed { index, (name, url) ->
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 1000) {
                onProgress(((index + 1) * 100) / entries.size)
                return@forEachIndexed
            }
            download(url, dest) { filePct ->
                val base = (index * 100) / entries.size
                val span = 100 / entries.size
                onProgress(base + (filePct * span) / 100)
            }
        }
        onProgress(100)
    }

    private fun download(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val tmp = File(dest.absolutePath + ".part")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 600_000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("دانلود Whisper ناموفق: ${conn.responseCode}")
        }
        val total = conn.contentLengthLong.coerceAtLeast(1)
        var done = 0L
        var last = -1
        BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    done += read
                    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                    if (pct != last) {
                        last = pct
                        onProgress(pct)
                    }
                }
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
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
            System.gc()
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, ENC).absolutePath,
                        decoder = File(dir, DEC).absolutePath,
                        language = lang,
                        task = "transcribe",
                        tailPaddings = 1200
                    ),
                    tokens = File(dir, TOK).absolutePath,
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
        var t = text
        // drop whisper punctuation noise
        t = t.replace(Regex("""[«»""]"""), "")
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        // reject pure english if we forced fa and text is only latin (optional soft)
        t = NumberNormalizer.normalize(PersianPostProcess.fix(t))
        return t.trim()
    }
}
