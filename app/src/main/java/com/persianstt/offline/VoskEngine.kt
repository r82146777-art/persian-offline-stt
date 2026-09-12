package com.persianstt.offline

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Vosk Persian **large** model (vosk-model-fa-0.42 ~1.6GB).
 * Official table: better WER than small-fa (16.7 vs 23.4 on CV).
 * This is a different engine path from Shenava/Whisper/Qwen3.
 */
object VoskEngine {

    private const val TAG = "VoskEngine"
    private const val FA_NAME = "vosk-model-fa-0.42"
    private const val FA_URL = "https://alphacephei.com/vosk/models/vosk-model-fa-0.42.zip"
    // mirror sometimes needed
    private const val FA_URL_ALT =
        "https://huggingface.co/alphacep/vosk-model-fa-0.42/resolve/main/vosk-model-fa-0.42.zip"

    @Volatile private var model: Model? = null
    @Volatile var lastError: String = ""

    private fun modelsRoot(context: Context): File =
        File(context.applicationContext.filesDir, "vosk-models")

    private fun modelDir(context: Context): File =
        File(modelsRoot(context), FA_NAME)

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        // vosk models need am/ / graph/ or conf/
        if (!dir.isDirectory) return false
        val marker = File(dir, "am/final.mdl")
        val marker2 = File(dir, "conf/model.conf")
        val marker3 = File(dir, "graph/Gr.fst")
        return (marker.exists() || marker2.exists() || marker3.exists()) &&
            dir.walkTopDown().any { it.isFile && it.length() > 1_000_000 }
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        val root = modelsRoot(context)
        if (!root.exists()) root.mkdirs()
        // remove old small model to free space
        try {
            File(root, "vosk-model-small-fa-0.42").deleteRecursively()
            File(root, "vosk-model-small-en-us-0.15").deleteRecursively()
        } catch (_: Exception) {}

        val zipFile = File(root, "$FA_NAME.zip")
        try {
            try {
                downloadResumable(FA_URL, zipFile) { pct -> onProgress((pct * 85) / 100) }
            } catch (e: Exception) {
                Log.w(TAG, "primary URL failed, try alt", e)
                downloadResumable(FA_URL_ALT, zipFile) { pct -> onProgress((pct * 85) / 100) }
            }
            onProgress(86)
            if (!zipFile.exists() || zipFile.length() < 50_000_000) {
                lastError = "دانلود ناقص"
                throw IllegalStateException(lastError)
            }
            unzip(zipFile, root) { p -> onProgress(86 + (p * 12) / 100) }
            onProgress(98)
            try { zipFile.delete() } catch (_: Exception) {}
            // normalize folder name
            root.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith("vosk-model-fa") && f.name != FA_NAME) {
                    val dest = File(root, FA_NAME)
                    if (!dest.exists()) f.renameTo(dest)
                }
            }
            if (!isReady(context)) {
                lastError = "استخراج مدل Vosk ناقص"
                throw IllegalStateException(lastError)
            }
            onProgress(100)
            lastError = ""
        } catch (e: java.net.UnknownHostException) {
            lastError = "اینترنت/DNS قطع"
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "خطای دانلود Vosk"
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
                var last = -1
                val buf = ByteArray(64 * 1024)
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
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun unzip(zipFile: File, destRoot: File, onProgress: (Int) -> Unit) {
        val total = zipFile.length().coerceAtLeast(1)
        var written = 0L
        var last = -1
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), 64 * 1024)).use { zis ->
            val buf = ByteArray(64 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.contains("..")) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val outFile = File(destRoot, name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var n: Int
                        while (zis.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                }
                zis.closeEntry()
                val pct = ((written * 100) / (total * 3)).toInt().coerceIn(0, 99) // zip expands
                if (pct != last) {
                    last = pct
                    onProgress(pct)
                }
                entry = zis.nextEntry
            }
        }
    }

    @Synchronized
    fun load(context: Context): Boolean {
        if (model != null) return true
        if (!isReady(context)) {
            lastError = "مدل Vosk نیست"
            return false
        }
        return try {
            System.gc()
            model = Model(modelDir(context).absolutePath)
            lastError = ""
            true
        } catch (e: OutOfMemoryError) {
            model = null
            lastError = "حافظه کم برای مدل بزرگ Vosk"
            Log.e(TAG, "OOM", e)
            false
        } catch (e: Exception) {
            model = null
            lastError = e.message ?: "خطای بارگذاری Vosk"
            Log.e(TAG, "load", e)
            false
        }
    }

    @Synchronized
    fun release() {
        try { model?.close() } catch (_: Exception) {}
        model = null
    }

    @Synchronized
    fun transcribe(pcm16: ShortArray, sampleRate: Int = 16000): String {
        val m = model ?: return ""
        if (pcm16.isEmpty() || pcm16.size < sampleRate / 6) return ""
        return try {
            val rec = Recognizer(m, sampleRate.toFloat())
            var off = 0
            val chunk = sampleRate / 2
            while (off < pcm16.size) {
                val n = minOf(chunk, pcm16.size - off)
                rec.acceptWaveForm(pcm16.copyOfRange(off, off + n), n)
                off += n
            }
            val json = rec.finalResult
            rec.close()
            val raw = JSONObject(json).optString("text", "").trim()
            NumberNormalizer.normalize(PersianPostProcess.fix(raw))
        } catch (_: Exception) {
            ""
        }
    }
}
