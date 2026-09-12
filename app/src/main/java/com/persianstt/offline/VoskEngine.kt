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
 * Vosk Persian **small** model only (~53MB).
 * Large fa-0.42 caused OOM / whole-phone freeze during prepare.
 */
object VoskEngine {

    private const val TAG = "VoskEngine"
    private const val FA_NAME = "vosk-model-small-fa-0.42"
    private const val FA_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"

    @Volatile private var model: Model? = null
    @Volatile var lastError: String = ""
    @Volatile var lastPhase: String = ""

    private fun modelsRoot(context: Context): File =
        File(context.applicationContext.filesDir, "vosk-models")

    private fun modelDir(context: Context): File =
        File(modelsRoot(context), FA_NAME)

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return false
        // small model structure
        return dir.listFiles()?.isNotEmpty() == true &&
            (File(dir, "am").exists() || File(dir, "conf").exists() ||
                File(dir, "graph").exists() || File(dir, "ivector").exists())
    }

    /** Download + unzip only. Does NOT load Model into RAM. */
    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            lastPhase = "ready"
            return
        }
        val root = modelsRoot(context)
        if (!root.exists()) root.mkdirs()

        // Delete the huge model that freezes phones
        try {
            File(root, "vosk-model-fa-0.42").deleteRecursively()
            File(root, "vosk-model-fa-0.42.zip").delete()
            File(root, "vosk-model-fa-0.42.zip.part").delete()
        } catch (_: Exception) {}

        val zipFile = File(root, "$FA_NAME.zip")
        try {
            lastPhase = "download"
            downloadResumable(FA_URL, zipFile) { pct -> onProgress((pct * 85) / 100) }
            onProgress(86)
            if (!zipFile.exists() || zipFile.length() < 1_000_000) {
                lastError = "دانلود ناقص"
                throw IllegalStateException(lastError)
            }
            lastPhase = "extract"
            unzip(zipFile, root) { p -> onProgress(86 + (p * 12) / 100) }
            onProgress(98)
            try { zipFile.delete() } catch (_: Exception) {}
            // rename if zip used different folder name
            root.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.contains("small-fa") && f.name != FA_NAME) {
                    val dest = File(root, FA_NAME)
                    if (!dest.exists()) f.renameTo(dest)
                }
            }
            System.gc()
            if (!isReady(context)) {
                lastError = "استخراج ناقص"
                throw IllegalStateException(lastError)
            }
            onProgress(100)
            lastError = ""
            lastPhase = "extracted"
        } catch (e: OutOfMemoryError) {
            lastError = "حافظه کم هنگام آماده‌سازی"
            try { System.gc() } catch (_: Throwable) {}
            throw e
        } catch (e: java.net.UnknownHostException) {
            lastError = "اینترنت/DNS قطع"
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "خطای دانلود"
            throw e
        }
    }

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
        if (code == 200 && existing > 0) {
            existing = 0
            tmp.delete()
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val totalFromHeader = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (code == 206 && totalFromHeader > 0) existing + totalFromHeader
        else if (totalFromHeader > 0) totalFromHeader else -1L
        BufferedInputStream(conn.inputStream, 32 * 1024).use { input ->
            FileOutputStream(tmp, existing > 0 && code == 206).use { out ->
                var done = existing
                var last = -1
                val buf = ByteArray(32 * 1024)
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
        var entries = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), 32 * 1024)).use { zis ->
            val buf = ByteArray(32 * 1024)
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
                        while (zis.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                entries++
                if (entries % 5 == 0) {
                    onProgress((entries * 2).coerceIn(0, 99))
                }
                entry = zis.nextEntry
            }
        }
        onProgress(99)
    }

    @Synchronized
    fun load(context: Context): Boolean {
        if (model != null) return true
        if (!isReady(context)) {
            lastError = "مدل نیست"
            return false
        }
        lastPhase = "load"
        return try {
            System.gc()
            Thread.sleep(150)
            model = Model(modelDir(context).absolutePath)
            lastError = ""
            lastPhase = "loaded"
            true
        } catch (e: OutOfMemoryError) {
            model = null
            lastError = "حافظه کم — برنامه‌های دیگر را ببندید"
            Log.e(TAG, "OOM load", e)
            try { System.gc() } catch (_: Throwable) {}
            false
        } catch (e: Throwable) {
            model = null
            lastError = e.message ?: "خطای بارگذاری"
            Log.e(TAG, "load", e)
            false
        }
    }

    @Synchronized
    fun release() {
        try { model?.close() } catch (_: Exception) {}
        model = null
        lastPhase = "released"
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
        } catch (e: OutOfMemoryError) {
            lastError = "حافظه کم هنگام تشخیص"
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
