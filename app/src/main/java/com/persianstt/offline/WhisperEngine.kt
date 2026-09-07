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
 * Offline multilingual Whisper (tiny int8) via sherpa-onnx.
 * Better accuracy + auto language (fa/en) compared to Vosk small.
 */
object WhisperEngine {

    private const val MODEL_DIR = "whisper-tiny"
    private const val ENC = "tiny-encoder.int8.onnx"
    private const val DEC = "tiny-decoder.int8.onnx"
    private const val TOK = "tiny-tokens.txt"

    // HuggingFace direct (multilingual tiny)
    private val FILES = mapOf(
        ENC to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
        DEC to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
        TOK to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt"
    )

    @Volatile private var recognizer: OfflineRecognizer? = null

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "whisper-models/$MODEL_DIR")

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, ENC).exists() && File(dir, DEC).exists() && File(dir, TOK).exists()
    }

    /** Download missing model files. onProgress 0..100 overall. */
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
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 300000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download failed HTTP ${conn.responseCode} for $urlStr")
        }
        val total = conn.contentLengthLong
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(tmp).use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                var last = -1
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
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
    fun load(context: Context, languageHint: String = ""): Boolean {
        if (recognizer != null) return true
        if (!isReady(context)) return false
        val dir = modelDir(context)
        // empty language => multilingual auto-detect; "fa" / "en" force language
        val lang = when (languageHint) {
            "fa" -> "fa"
            "en" -> "en"
            else -> "" // auto
        }
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, ENC).absolutePath,
                    decoder = File(dir, DEC).absolutePath,
                    language = lang,
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
        return true
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }

    /**
     * Decode 16-bit mono PCM samples at 16 kHz.
     */
    @Synchronized
    fun transcribe(pcm16: ShortArray, sampleRate: Int = 16000): String {
        val r = recognizer ?: return ""
        if (pcm16.isEmpty()) return ""
        val floats = FloatArray(pcm16.size) { i -> pcm16[i] / 32768.0f }
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(floats, sampleRate)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            NumberNormalizer.normalize(text)
        } catch (_: Exception) {
            ""
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }
}
