package com.persianstt.offline

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shenava Koochik v1.0 — flagship Persian FastConformer CTC (114M).
 * WER ~7.5% on golden-6669 (much stronger than Rizeh 32M / Vosk).
 * ~100 MB int8, fully offline, fa-only.
 */
object ShenavaEngine {

    private const val FOLDER = "shenava-koochik-int8"
    private const val MODEL = "model.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private const val TAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-nemo-ctc-fa-shenava-koochik-v1.0-non-streaming-int8-2026-06-26.tar.bz2"

    @Volatile private var recognizer: OfflineRecognizer? = null

    private fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "shenava-models/$FOLDER")

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        val m = File(dir, MODEL)
        val t = File(dir, TOKENS)
        return m.exists() && t.exists() && m.length() > 10_000_000
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        // remove old rizeh if present
        try {
            File(context.applicationContext.filesDir, "shenava-models/shenava-rizeh-int8")
                .deleteRecursively()
        } catch (_: Exception) {}

        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        val tarFile = File(dir, "model.tar.bz2")
        downloadResumable(TAR_URL, tarFile) { pct -> onProgress((pct * 90) / 100) }
        onProgress(92)
        extractTarBz2(tarFile, dir)
        tarFile.delete()
        // flatten nested folder if any
        val nested = dir.listFiles()?.firstOrNull {
            it.isDirectory && (it.name.contains("shenava") || it.name.contains("koochik") || it.name.contains("nemo"))
        }
        if (nested != null) {
            nested.listFiles()?.forEach { f ->
                val dest = File(dir, f.name)
                if (!dest.exists()) f.renameTo(dest)
                else if (f.isFile) f.delete()
            }
            nested.deleteRecursively()
        }
        onProgress(100)
    }

    /** Resume-friendly download with stable progress updates. */
    private fun downloadResumable(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val tmp = File(dest.absolutePath + ".part")
        var existing = if (tmp.exists()) tmp.length() else 0L

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 300_000
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()
        val code = conn.responseCode
        // server ignored range → restart
        if (code == 200 && existing > 0) {
            existing = 0
            tmp.delete()
        }
        val totalFromHeader = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (code == 206 && totalFromHeader > 0) existing + totalFromHeader
        else if (totalFromHeader > 0) totalFromHeader
        else -1L

        val input = BufferedInputStream(conn.inputStream)
        val out = if (existing > 0 && code == 206)
            FileOutputStream(tmp, true)
        else
            FileOutputStream(tmp, false)

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
        onProgress(100)
    }

    private fun extractTarBz2(tarBz2: File, destDir: File) {
        FileInputStream(tarBz2).use { fis ->
            BZip2CompressorInputStream(BufferedInputStream(fis)).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    val buf = ByteArray(256 * 1024)
                    while (entry != null) {
                        val name = entry.name
                        val relative = name.substringAfter('/', name)
                        if (relative.isBlank()) {
                            entry = tarIn.nextEntry
                            continue
                        }
                        val outFile = File(destDir, relative)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                while (true) {
                                    val n = tarIn.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                }
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
        if (!isReady(context)) return false
        return try {
            val dir = modelDir(context)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        model = File(dir, MODEL).absolutePath
                    ),
                    tokens = File(dir, TOKENS).absolutePath,
                    modelType = "nemo_ctc",
                    numThreads = 4,
                    provider = "cpu"
                ),
                decodingMethod = "greedy_search"
            )
            recognizer = OfflineRecognizer(config = config)
            true
        } catch (e: Exception) {
            e.printStackTrace()
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
        if (pcm16.isEmpty() || pcm16.size < sampleRate / 4) return ""
        val floats = FloatArray(pcm16.size) { i -> pcm16[i] / 32768.0f }
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(floats, sampleRate)
            r.decode(stream)
            val raw = r.getResult(stream).text.trim()
            NumberNormalizer.normalize(raw)
        } catch (_: Exception) {
            ""
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }
}
