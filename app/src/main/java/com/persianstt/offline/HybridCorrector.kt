package com.persianstt.offline

import android.content.Context
import kotlin.math.min

/**
 * Hybrid: keep free Vosk output; only fix tokens that look wrong
 * using user dictionary + small Levenshtein. Does NOT cage recognition
 * to dictionary-only (that broke normal Persian typing).
 */
object HybridCorrector {

    fun improve(context: Context, raw: String): String {
        if (raw.isBlank()) return raw
        val dict = DictionaryStore.allWords(context)
        if (dict.isEmpty()) return raw.trim()

        val dictSet = dict.toHashSet()
        val byFirst = HashMap<Char, ArrayList<String>>()
        for (w in dict) {
            val c = w.firstOrNull() ?: continue
            byFirst.getOrPut(c) { ArrayList() }.add(w)
        }

        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = tokens.map { tok ->
            val core = tok.trim('،', '.', '!', '؟', ':', ';', ',', '"', '\'')
            if (core.length < 2) return@map tok
            if (core in dictSet) return@map tok // engine/dict already correct — keep
            // digits keep
            if (core.all { it.isDigit() || it in "۰۱۲۳۴۵۶۷۸۹" }) return@map tok

            val candidates = byFirst[core.firstOrNull()] ?: emptyList()
            var best = core
            var bestD = 2
            for (c in candidates) {
                if (kotlin.math.abs(c.length - core.length) > 2) continue
                val d = editDist(core, c, 2)
                if (d in 1..bestD) {
                    bestD = d
                    best = c
                    if (d == 1) break
                }
            }
            if (best != core) tok.replace(core, best) else tok
        }
        return out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun editDist(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return 99
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..n) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return 99
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[n]
    }
}
