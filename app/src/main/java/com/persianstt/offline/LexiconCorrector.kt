package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Large Persian word lexicon (~70k+) downloaded with the engine.
 * Used to auto-fix spaced/broken ASR tokens.
 */
object LexiconCorrector {

    private const val TAG = "Lexicon"
    private const val NAME = "persian_lexicon.txt"
    // ~70k unique Persian words (UPC-based open lexicon)
    private const val URL_PRIMARY =
        "https://raw.githubusercontent.com/samanvp/persian-lexicon/master/data/persian-words.txt"

    @Volatile private var words: Set<String> = emptySet()
    @Volatile private var byCompact: Map<String, String> = emptyMap()
    @Volatile var lastError: String = ""

    private fun file(context: Context) =
        File(context.applicationContext.filesDir, NAME)

    fun isReady(context: Context): Boolean {
        val f = file(context)
        return f.exists() && f.length() > 100_000
    }

    fun ensureLexicon(context: Context, onProgress: (Int) -> Unit = {}) {
        if (isReady(context)) {
            onProgress(100)
            load(context)
            return
        }
        val dest = file(context)
        val tmp = File(dest.absolutePath + ".part")
        try {
            val conn = (URL(URL_PRIMARY).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.coerceAtLeast(1)
            var done = 0L
            var last = -1
            BufferedInputStream(conn.inputStream, 32 * 1024).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            conn.disconnect()
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            onProgress(100)
            lastError = ""
            load(context)
        } catch (e: Exception) {
            lastError = e.message ?: "خطای دیکشنری"
            Log.e(TAG, "download lexicon", e)
            try { tmp.delete() } catch (_: Exception) {}
            throw e
        }
    }

    fun load(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        val set = HashSet<String>(80_000)
        val compact = HashMap<String, String>(80_000)
        f.bufferedReader().useLines { lines ->
            lines.forEach { line0 ->
                val w = line0.trim()
                if (w.isEmpty() || w.startsWith("#")) return@forEach
                // only pure Persian-ish tokens
                if (w.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return@forEach
                set.add(w)
                val c = w.replace(" ", "").replace("\u200c", "")
                if (c.length in 2..20 && !compact.containsKey(c)) compact[c] = w
            }
        }
        words = set
        byCompact = compact
        Log.i(TAG, "lexicon loaded: ${words.size} words")
    }

    /**
     * Auto-fix ASR output using lexicon + user dict.
     */
    fun autoCorrect(context: Context, raw: String): String {
        if (raw.isBlank()) return raw
        if (words.isEmpty()) load(context)
        var t = DictCorrection.apply(context, raw)

        // Fix mid-word spaces: "سلا م" -> "سلام"
        t = joinBrokenWords(t)

        // Word-level: if token not in lexicon, try compact match
        val parts = t.split(Regex("\\s+")).toMutableList()
        for (i in parts.indices) {
            val w = parts[i].trim()
            if (w.isEmpty()) continue
            if (words.contains(w)) continue
            val c = w.replace("\u200c", "")
            val mapped = byCompact[c]
            if (mapped != null) {
                parts[i] = mapped
                continue
            }
            // try without Arabic Yeh/Kaf variants
            val norm = normalizeFa(c)
            val m2 = byCompact[norm]
            if (m2 != null) parts[i] = m2
        }
        t = parts.joinToString(" ")
        // second pass join
        t = joinBrokenWords(t)
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun normalizeFa(s: String): String =
        s.replace('ي', 'ی').replace('ك', 'ک').replace('ة', 'ه')

    private fun joinBrokenWords(text: String): String {
        if (words.isEmpty() && byCompact.isEmpty()) return text
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return text
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var best = tokens[i]
            var bestLen = 1
            // try merge up to 4 tokens
            var merged = tokens[i]
            for (n in 2..4) {
                if (i + n - 1 >= tokens.size) break
                merged += tokens[i + n - 1]
                val compact = merged.replace("\u200c", "")
                if (words.contains(merged) || byCompact.containsKey(compact)) {
                    best = byCompact[compact] ?: merged
                    bestLen = n
                }
            }
            out.add(best)
            i += bestLen
        }
        return out.joinToString(" ")
    }
}
