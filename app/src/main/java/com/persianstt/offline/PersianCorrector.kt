package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.io.File

/**
 * مغز متنی موتور همدل — حدود ۵۰ هزار کلمه/عبارت درست + قواعد اصلاح.
 */
object PersianCorrector {

    private const val TAG = "PersianCorrector"
    private const val BRAIN_ASSET = "hamdel_brain.txt"
    private const val BRAIN_FILE = "hamdel_brain.txt"

    @Volatile private var words: Set<String> = emptySet()
    @Volatile private var byCompact: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    private val phrases = listOf(
        "عرز سلام عدابه احترام خدمته تمام د شتانه عزی" to "عرض سلام و ادب و احترام خدمت تمام دوستان عزیز",
        "عرز سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "عرز سلام عداب احترام" to "عرض سلام و ادب و احترام",
        "عرض سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "ارزی سلا م اتاب اختر" to "عرض سلام و ادب و احترام",
        "ارزی سلام اتاب اختر" to "عرض سلام و ادب و احترام",
        "عرضسلامادباحتر" to "عرض سلام و ادب و احترام",
        "عرض سلام ادب احترام" to "عرض سلام و ادب و احترام",
        "عرض سلام و ادب احترام" to "عرض سلام و ادب و احترام",
        "خدمته تمام د شتانه عزی" to "خدمت تمام دوستان عزیز",
        "خدمته تمام دوستان عزی" to "خدمت تمام دوستان عزیز",
        "خدمت تمام د شتانه عزی" to "خدمت تمام دوستان عزیز",
        "د شتانه عزی" to "دوستان عزیز",
        "دشتانه عزیز" to "دوستان عزیز",
        "شتانه عزی" to "دوستان عزیز",
        "سلام ها عرضه ا" to "سلام و عرض ادب",
        "سلام عرض ادب" to "سلام و عرض ادب",
        "عرضسلام" to "عرض سلام",
        "عدابه احترام" to "ادب و احترام",
        "عداب احترام" to "ادب و احترام",
        "اتاب اختر" to "ادب و احترام",
        "اتاب احترام" to "ادب و احترام",
        "خواهش میکنم" to "خواهش می‌کنم",
        "خواهش می کنم" to "خواهش می‌کنم"
    ).sortedByDescending { it.first.length }

    private val tokens = listOf(
        "عرز" to "عرض",
        "عدابه" to "ادب",
        "عداب" to "ادب",
        "اتاب" to "ادب",
        "اختر" to "احترام",
        "خدمته" to "خدمت",
        "عزی" to "عزیز"
    )

    fun ensureLoaded(context: Context) {
        if (loaded && words.isNotEmpty()) return
        synchronized(this) {
            if (loaded && words.isNotEmpty()) return
            try {
                val f = File(context.applicationContext.filesDir, BRAIN_FILE)
                if (!f.exists() || f.length() < 100_000) {
                    context.applicationContext.assets.open(BRAIN_ASSET).use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val set = HashSet<String>(60_000)
                val compact = HashMap<String, String>(60_000)
                f.bufferedReader().useLines { lines ->
                    lines.forEach { line0 ->
                        val w = line0.trim()
                        if (w.isEmpty() || w.startsWith("#")) return@forEach
                        set.add(w)
                        val c = w.replace(" ", "").replace("\u200c", "")
                        if (c.length in 2..40 && !compact.containsKey(c)) {
                            compact[c] = w
                        }
                    }
                }
                words = set
                byCompact = compact
                loaded = true
                Log.i(TAG, "brain loaded: ${words.size} entries")
            } catch (e: Exception) {
                Log.e(TAG, "brain load failed", e)
                loaded = true // avoid retry storm
            }
        }
    }

    fun fix(context: Context?, raw: String): String {
        if (raw.isBlank()) return raw
        if (context != null) ensureLoaded(context)
        var t = raw.trim()
            .replace('\u200c', ' ')
            .replace(Regex("[\\u064B-\\u065F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        t = joinSpacedLetters(t)

        for ((bad, good) in phrases) {
            if (t.contains(bad)) t = t.replace(bad, good)
        }

        // join broken words using brain (سلا م → سلام)
        t = joinBrokenWords(t)

        val parts = t.split(' ').toMutableList()
        for (i in parts.indices) {
            val w = parts[i]
            if (w.isEmpty()) continue
            if (words.contains(w)) continue
            var hit = false
            for ((bad, good) in tokens) {
                if (w == bad) {
                    parts[i] = good
                    hit = true
                    break
                }
            }
            if (hit) continue
            val c = w.replace("\u200c", "")
            val mapped = byCompact[c]
            if (mapped != null) parts[i] = mapped
        }
        t = parts.joinToString(" ")
        t = joinBrokenWords(t)

        for ((bad, good) in phrases) {
            if (t.contains(bad)) t = t.replace(bad, good)
        }

        t = NumberNormalizer.normalize(PersianPostProcess.fix(t))
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Backward-compatible without context */
    fun fix(raw: String): String = fix(null, raw)

    private fun joinBrokenWords(text: String): String {
        if (byCompact.isEmpty() && words.isEmpty()) return text
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return text
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var best = tokens[i]
            var bestLen = 1
            var merged = tokens[i]
            for (n in 2..4) {
                if (i + n - 1 >= tokens.size) break
                merged += tokens[i + n - 1]
                val compact = merged.replace("\u200c", "")
                when {
                    words.contains(merged) -> {
                        best = merged; bestLen = n
                    }
                    byCompact.containsKey(compact) -> {
                        best = byCompact[compact]!!; bestLen = n
                    }
                }
            }
            out.add(best)
            i += bestLen
        }
        return out.joinToString(" ")
    }

    private fun joinSpacedLetters(s: String): String {
        val parts = s.split(' ')
        if (parts.size < 3) return s
        val out = mutableListOf<String>()
        var i = 0
        while (i < parts.size) {
            if (parts[i].length == 1 && i + 1 < parts.size && parts[i + 1].length == 1) {
                val buf = StringBuilder()
                while (i < parts.size && parts[i].length == 1) {
                    buf.append(parts[i]); i++
                }
                val joined = buf.toString()
                out.add(byCompact[joined] ?: joined)
            } else {
                out.add(parts[i]); i++
            }
        }
        return out.joinToString(" ")
    }
}
