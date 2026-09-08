package com.persianstt.offline

import android.content.Context
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
 * Offline Whisper tiny (int8) via sherpa-onnx.
 * Language is ALWAYS forced to "fa" or "en" (never auto) to prevent
 * Chinese / Japanese / other-language hallucinations.
 */
object WhisperEngine {

    private const val MODEL_DIR = "whisper-tiny"
    private const val ENC = "tiny-encoder.int8.onnx"
    private const val DEC = "tiny-decoder.int8.onnx"
    private const val TOK = "tiny-tokens.txt"

    private val FILES = mapOf(
        ENC to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
        DEC to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
        TOK to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt"
    )

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedLang: String = ""

    // CJK / Japanese / Korean / Hangul / fullwidth → reject
    private val BAD_SCRIPT = Regex(
        "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff" +
            "\\uac00-\\ud7af\\uff00-\\uffef\\u3000-\\u303f]"
    )

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "whisper-models/$MODEL_DIR")

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, ENC).exists() && File(dir, DEC).exists() && File(dir, TOK).exists()
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
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
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.connect()
        val total = conn.contentLengthLong.coerceAtLeast(1)
        var done = 0L
        var last = -1
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
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

    /**
     * Load (or reload) recognizer with a forced language.
     * languageHint must be "fa" or "en". Anything else defaults to "fa".
     */
    @Synchronized
    fun load(context: Context, languageHint: String = "fa"): Boolean {
        val lang = when (languageHint.lowercase()) {
            "en", "english" -> "en"
            else -> "fa"
        }
        // Already loaded with same language → reuse
        if (recognizer != null && loadedLang == lang) return true
        // Language changed → rebuild
        release()
        if (!isReady(context)) return false
        val dir = modelDir(context)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, ENC).absolutePath,
                    decoder = File(dir, DEC).absolutePath,
                    language = lang,          // ALWAYS forced
                    task = "transcribe",
                    tailPaddings = 1000
                ),
                tokens = File(dir, TOK).absolutePath,
                modelType = "whisper",
                numThreads = 2,
                provider = "cpu"
            )
        )
        recognizer = OfflineRecognizer(config = config)
        loadedLang = lang
        return true
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        loadedLang = ""
    }

    /**
     * Decode 16-bit mono PCM @ 16 kHz.
     * Cleans hallucinations: CJK scripts, runaway repetition.
     */
    @Synchronized
    fun transcribe(pcm16: ShortArray, sampleRate: Int = 16000): String {
        val r = recognizer ?: return ""
        if (pcm16.isEmpty()) return ""
        // Too short (< 0.3 s) → ignore (avoids garbage)
        if (pcm16.size < sampleRate / 3) return ""

        val floats = FloatArray(pcm16.size) { i -> pcm16[i] / 32768.0f }
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(floats, sampleRate)
            r.decode(stream)
            val raw = r.getResult(stream).text.trim()
            cleanResult(raw)
        } catch (_: Exception) {
            ""
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }

    /** Remove wrong-script output and collapse repetition loops. */
    private fun cleanResult(text: String): String {
        if (text.isBlank()) return ""

        // Reject if contains Chinese / Japanese / Korean characters
        if (BAD_SCRIPT.containsMatchIn(text)) return ""

        var t = text

        // Collapse "word word word …" (same token 3+ times)
        t = t.replace(Regex("(\\S+)(?:\\s+\\1){2,}"), "$1")

        // Collapse character-level loops: "هههههه" → "هه"
        t = t.replace(Regex("(.)\\1{4,}"), "$1$1")

        // Extra safety: if more than 60% of tokens are identical, keep only one
        val tokens = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size >= 4) {
            val mostCommon = tokens.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (mostCommon != null && mostCommon.value * 2 >= tokens.size) {
                t = mostCommon.key
            }
        }

        t = NumberNormalizer.normalize(t)
        return t.trim()
    }
}
