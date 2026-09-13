package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.io.File

/** واژه‌نامهٔ بزرگ برای چسباندن کلمات شکسته — مکمل مرحله ۱ */
object BrainLexicon {
    private const val TAG = "BrainLexicon"
    private const val ASSET = "hamdel_brain.txt"
    private const val FILE = "hamdel_brain.txt"

    @Volatile private var words: Set<String> = emptySet()
    @Volatile private var byCompact: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val f = File(context.applicationContext.filesDir, FILE)
                if (!f.exists() || f.length() < 50_000) {
                    context.applicationContext.assets.open(ASSET).use { inp ->
                        f.outputStream().use { out -> inp.copyTo(out) }
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
                        if (c.length in 2..40) compact.putIfAbsent(c, w)
                    }
                }
                words = set
                byCompact = compact
                Log.i(TAG, "loaded ${words.size}")
            } catch (e: Exception) {
                Log.e(TAG, "load fail", e)
            } finally {
                loaded = true
            }
        }
    }

    fun joinBroken(text: String): String {
        if (words.isEmpty() && byCompact.isEmpty()) return text
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
                val c = merged.replace("\u200c", "")
                when {
                    words.contains(merged) -> { best = merged; bestLen = n }
                    byCompact.containsKey(c) -> { best = byCompact[c]!!; bestLen = n }
                }
            }
            // single token compact
            if (bestLen == 1) {
                val c = tokens[i].replace("\u200c", "")
                byCompact[c]?.let { best = it }
            }
            out.add(best)
            i += bestLen
        }
        return out.joinToString(" ")
    }
}
