package com.persianstt.offline

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VoskEngine {

    private const val FA_NAME = "vosk-model-small-fa-0.42"
    private const val EN_NAME = "vosk-model-small-en-us-0.15"
    private const val FA_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"
    private const val EN_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    @Volatile private var faModel: Model? = null
    @Volatile private var enModel: Model? = null
    @Volatile private var loadedLang: String = ""

    private fun modelsRoot(context: Context): File =
        File(context.applicationContext.filesDir, "vosk-models")

    fun isReady(context: Context, lang: String = "fa"): Boolean {
        val name = if (lang.startsWith("en")) EN_NAME else FA_NAME
        val dir = File(modelsRoot(context), name)
        return dir.isDirectory && (dir.list()?.isNotEmpty() == true)
    }

    fun isAnyReady(context: Context): Boolean =
        isReady(context, "fa") || isReady(context, "en")

    fun ensureModels(context: Context, onProgress: (Int) -> Unit = {}) {
        val root = modelsRoot(context)
        if (!root.exists()) root.mkdirs()
        val jobs = listOf(
            Triple(FA_NAME, FA_URL, 0),
            Triple(EN_NAME, EN_URL, 50)
        )
        jobs.forEach { (name, url, base) ->
            val dest = File(root, name)
            if (dest.isDirectory && dest.list()?.isNotEmpty() == true) {
                onProgress(base + 50)
                return@forEach
            }
            downloadAndUnzip(url, root, name) { pct ->
                onProgress(base + pct / 2)
            }
        }
        onProgress(100)
    }

    private fun downloadAndUnzip(urlStr: String, root: File, folderName: String, onProgress: (Int) -> Unit) {
        val zipFile = File(root, "$folderName.zip")
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            instanceFollowRedirects = true
        }
        conn.connect()
        val total = conn.contentLengthLong.coerceAtLeast(1)
        var done = 0L
        var last = -1
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(zipFile).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                    if (pct != last) {
                        last = pct
                        onProgress(pct)
                    }
                }
            }
        }
        conn.disconnect()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                val outFile = File(root, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zipFile.delete()
        onProgress(100)
        val dest = File(root, folderName)
        if (!dest.exists()) {
            root.listFiles()?.firstOrNull {
                it.isDirectory && it.name.startsWith(folderName.take(12))
            }?.renameTo(dest)
        }
    }

    @Synchronized
    fun load(context: Context, languageHint: String = "fa"): Boolean {
        val lang = if (languageHint.lowercase().startsWith("en")) "en" else "fa"
        if (loadedLang == lang) {
            val m = if (lang == "en") enModel else faModel
            if (m != null) return true
        }
        if (!isReady(context, lang)) return false
        return try {
            val name = if (lang == "en") EN_NAME else FA_NAME
            val dir = File(modelsRoot(context), name)
            val model = Model(dir.absolutePath)
            if (lang == "en") {
                try { enModel?.close() } catch (_: Exception) {}
                enModel = model
            } else {
                try { faModel?.close() } catch (_: Exception) {}
                faModel = model
            }
            loadedLang = lang
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun release() {
        try { faModel?.close() } catch (_: Exception) {}
        try { enModel?.close() } catch (_: Exception) {}
        faModel = null
        enModel = null
        loadedLang = ""
    }

    private fun decodeWith(model: Model, pcm16: ShortArray, sampleRate: Int): String {
        return try {
            val rec = Recognizer(model, sampleRate.toFloat())
            val chunk = sampleRate / 2
            var off = 0
            while (off < pcm16.size) {
                val n = minOf(chunk, pcm16.size - off)
                val piece = pcm16.copyOfRange(off, off + n)
                rec.acceptWaveForm(piece, piece.size)
                off += n
            }
            val json = rec.finalResult
            rec.close()
            JSONObject(json).optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    fun transcribe(pcm16: ShortArray, sampleRate: Int = 16000): String {
        if (pcm16.isEmpty() || pcm16.size < sampleRate / 4) return ""
        val primary = if (loadedLang == "en") enModel else faModel
        val secondary = if (loadedLang == "en") faModel else enModel
        if (primary == null && secondary == null) return ""

        var text = if (primary != null) decodeWith(primary, pcm16, sampleRate) else ""
        if (text.length < 2 && secondary != null) {
            val alt = decodeWith(secondary, pcm16, sampleRate)
            if (alt.length > text.length) text = alt
        }
        return NumberNormalizer.normalize(text)
    }
}
