package com.persianstt.offline

import android.content.Context
import android.util.Log
import org.json.JSONArray
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
 * Vosk با محدودسازی دایره لغات (Grammar).
 * مدل: vosk-model-small-fa-0.5
 * Recognizer(model, 16000f, grammarJson)
 */
object GrammarVoskEngine {
    private const val TAG = "GrammarVosk"
    const val MODEL_NAME = "vosk-model-small-fa-0.5"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.5.zip"

    @Volatile private var model: Model? = null
    @Volatile var lastError: String = ""

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "vosk-models/$MODEL_NAME")

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return false
        return dir.listFiles()?.isNotEmpty() == true &&
            (File(dir, "am").exists() || File(dir, "conf").exists() ||
                File(dir, "graph").exists() || File(dir, "ivector").exists())
    }

    /** اول assets، بعد دانلود */
    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            return
        }
        // try copy from assets/model
        try {
            val am = context.assets
            val assetsList = try { am.list("model") } catch (_: Exception) { null }
            if (assetsList != null && assetsList.isNotEmpty()) {
                onProgress(10)
                val dest = modelDir(context)
                if (dest.exists()) dest.deleteRecursively()
                dest.mkdirs()
                copyAssetDir(context, "model", dest)
                if (isReady(context)) {
                    onProgress(100)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "assets copy failed", e)
        }
        // download
        val root = File(context.applicationContext.filesDir, "vosk-models")
        if (!root.exists()) root.mkdirs()
        val zip = File(root, "$MODEL_NAME.zip")
        download(MODEL_URL, zip, onProgress)
        onProgress(95)
        if (modelDir(context).exists()) modelDir(context).deleteRecursively()
        unzip(zip, root)
        zip.delete()
        // zip usually extracts to folder named MODEL_NAME
        if (!isReady(context)) {
            // sometimes nested
            root.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.contains("fa")) {
                    if (f.absolutePath != modelDir(context).absolutePath) {
                        f.copyRecursively(modelDir(context), overwrite = true)
                    }
                }
            }
        }
        onProgress(100)
        if (!isReady(context)) lastError = "مدل بعد از دانلود کامل نیست"
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val am = context.assets
        val list = am.list(assetPath) ?: return
        if (list.isEmpty()) {
            // file
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (name in list) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childDest = File(dest, name)
            val sub = am.list(childAsset)
            if (sub != null && sub.isNotEmpty()) {
                copyAssetDir(context, childAsset, childDest)
            } else {
                am.open(childAsset).use { input ->
                    FileOutputStream(childDest).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun download(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 180000
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var n: Int
                var last = -1
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = ((done * 90) / total).toInt().coerceIn(0, 90)
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    @Synchronized
    fun load(context: Context) {
        if (model != null) return
        if (!isReady(context)) throw IllegalStateException("مدل آماده نیست")
        model = Model(modelDir(context).absolutePath)
    }

    @Synchronized
    fun release() {
        try { model?.close() } catch (_: Exception) {}
        model = null
    }

    fun getModel(): Model? = model

    /**
     * ساخت Recognizer با grammar محدود.
     * grammarJson مثال: ["سلام","تایپ","[unk]"]
     */
    fun createRecognizer(grammarWords: List<String>): Recognizer {
        val m = model ?: throw IllegalStateException("مدل بارگذاری نشده")
        val words = grammarWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val arr = JSONArray()
        for (w in words) arr.put(w)
        if (!words.contains("[unk]")) arr.put("[unk]")
        val grammar = arr.toString()
        return try {
            Recognizer(m, 16000.0f, grammar)
        } catch (e: Exception) {
            Log.w(TAG, "grammar failed, fallback free", e)
            lastError = e.message ?: "grammar"
            Recognizer(m, 16000.0f)
        }
    }

    fun extractText(json: String): String =
        try { JSONObject(json).optString("text", "").trim() } catch (_: Exception) { "" }

    fun extractPartial(json: String): String =
        try { JSONObject(json).optString("partial", "").trim() } catch (_: Exception) { "" }
}
