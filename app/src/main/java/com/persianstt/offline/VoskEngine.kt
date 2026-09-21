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
 * Vosk small-fa with Grammar Adaptation (JSON word list).
 * Model: copy from assets if present, else download vosk-model-small-fa-0.42 (~45MB).
 */
object VoskEngine {

    private const val TAG = "VoskEngine"
    private const val FA_NAME = "vosk-model-small-fa-0.42"
    private const val ASSET_NAME = "vosk-model-small-fa-0.5"
    private val URLS = listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip",
        "https://github.com/alphacep/vosk-api/releases/download/v0.3.42/vosk-model-small-fa-0.42.zip"
    )

    @Volatile private var model: Model? = null
    @Volatile private var grammarSnapshot: String = ""
    @Volatile var lastError: String = ""

    private fun modelsRoot(context: Context) =
        File(context.applicationContext.filesDir, "vosk-models")

    private fun modelDir(context: Context): File {
        val a = File(modelsRoot(context), ASSET_NAME)
        if (a.isDirectory && a.listFiles()?.isNotEmpty() == true) return a
        return File(modelsRoot(context), FA_NAME)
    }

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return false
        return dir.listFiles()?.isNotEmpty() == true &&
            (File(dir, "am").exists() || File(dir, "conf").exists() ||
                File(dir, "graph").exists() || File(dir, "ivector").exists())
    }

    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        // try assets first (vosk-model-small-fa-0.5.zip or folder)
        try {
            if (copyFromAssets(context, onProgress)) {
                onProgress(100)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "assets copy fail", e)
        }
        val root = modelsRoot(context)
        root.mkdirs()
        val zip = File(root, "$FA_NAME.zip")
        var last: Exception? = null
        for (url in URLS) {
            try {
                download(url, zip, onProgress)
                onProgress(90)
                unzip(zip, root)
                onProgress(98)
                try { zip.delete() } catch (_: Exception) {}
                if (isReady(context)) {
                    onProgress(100)
                    lastError = ""
                    return
                }
            } catch (e: Exception) {
                last = e
                Log.e(TAG, "dl $url", e)
            }
        }
        lastError = last?.message ?: "دانلود مدل ناموفق"
        throw IllegalStateException(lastError)
    }

    private fun copyFromAssets(context: Context, onProgress: (Int) -> Unit): Boolean {
        val am = context.assets
        val names = try { am.list("")?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        // zip in assets
        val zipName = names.firstOrNull {
            it.contains("vosk-model-small-fa") && it.endsWith(".zip")
        }
        if (zipName != null) {
            onProgress(5)
            val root = modelsRoot(context)
            root.mkdirs()
            val outZip = File(root, zipName)
            am.open(zipName).use { inp ->
                FileOutputStream(outZip).use { out -> inp.copyTo(out) }
            }
            onProgress(50)
            unzip(outZip, root)
            try { outZip.delete() } catch (_: Exception) {}
            onProgress(95)
            return isReady(context)
        }
        // folder in assets
        val folder = names.firstOrNull { it == ASSET_NAME || it == FA_NAME } ?: return false
        val dest = File(modelsRoot(context), folder)
        dest.mkdirs()
        copyAssetDir(context, folder, dest)
        return isReady(context)
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val list = context.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // file
            context.assets.open(assetPath).use { inp ->
                FileOutputStream(dest).use { out -> inp.copyTo(out) }
            }
            return
        }
        dest.mkdirs()
        for (name in list) {
            val childAsset = "$assetPath/$name"
            val childDest = File(dest, name)
            val sub = context.assets.list(childAsset)
            if (sub.isNullOrEmpty()) {
                context.assets.open(childAsset).use { inp ->
                    FileOutputStream(childDest).use { out -> inp.copyTo(out) }
                }
            } else {
                copyAssetDir(context, childAsset, childDest)
            }
        }
    }

    private fun download(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
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
            existing = 0; tmp.delete()
        }
        if (code !in 200..299 && code != 206) throw IllegalStateException("HTTP $code")
        val totalHdr = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (code == 206 && totalHdr > 0) existing + totalHdr else if (totalHdr > 0) totalHdr else -1L
        BufferedInputStream(conn.inputStream, 256 * 1024).use { input ->
            FileOutputStream(tmp, existing > 0 && code == 206).use { out ->
                var done = existing
                var last = -1
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = ((done * 90) / total).toInt().coerceIn(0, 90)
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true); tmp.delete()
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), 64 * 1024)).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                val outFile = File(destDir, entry.name)
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
            lastError = "حافظه کم"
            false
        } catch (e: Exception) {
            model = null
            lastError = e.message ?: "بارگذاری ناموفق"
            false
        }
    }

    /**
     * Free-vocabulary recognizer (full model).
     * Dictionary is applied later via HybridCorrector — not as hard grammar
     * (grammar-only mode was destroying normal Persian words).
     */
    @Synchronized
    fun createRecognizer(context: Context): Recognizer? {
        if (!load(context)) return null
        val m = model ?: return null
        return try {
            Recognizer(m, 16000.0f)
        } catch (e: Exception) {
            lastError = e.message ?: "Recognizer"
            Log.e(TAG, "recognizer", e)
            null
        }
    }

    /** Call when dictionary changes — releases so next create uses new grammar. */
    @Synchronized
    fun resetGrammar(context: Context) {
        grammarSnapshot = ""
        // model can stay loaded; new Recognizer picks new grammar
        Log.i(TAG, "grammar reset, words=${DictionaryStore.allWords(context).size}")
    }

    @Synchronized
    fun release() {
        try { model?.close() } catch (_: Exception) {}
        model = null
        grammarSnapshot = ""
        System.gc()
    }

    /** One-shot batch (legacy DualAsr). Prefer streaming Recognizer in Activity. */
    fun transcribe(context: Context, pcm16: ShortArray, sampleRate: Int = 16000): String {
        val rec = createRecognizer(context) ?: return ""
        return try {
            val bytes = ShortArrayToBytes.pcm16ToBytes(pcm16)
            rec.acceptWaveForm(bytes, bytes.size)
            val finalJson = rec.finalResult
            parseText(finalJson)
        } catch (e: Exception) {
            lastError = e.message ?: ""
            ""
        } finally {
            try { rec.close() } catch (_: Exception) {}
        }
    }

    fun parsePartial(json: String): String {
        return try {
            JSONObject(json).optString("partial", "")
        } catch (_: Exception) { "" }
    }

    fun parseText(json: String): String {
        return try {
            val o = JSONObject(json)
            o.optString("text", "").ifBlank { o.optString("partial", "") }
        } catch (_: Exception) { "" }
    }
}

object ShortArrayToBytes {
    fun pcm16ToBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
