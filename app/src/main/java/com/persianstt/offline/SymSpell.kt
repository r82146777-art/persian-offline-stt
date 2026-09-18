package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * SymSpell-style offline spell correction for Persian.
 * Loads frequency dictionary from assets/hamdel_brain.txt (word per line).
 * Very light RAM; safe for mid-range phones.
 */
object SymSpell {
    private const val TAG = "SymSpell"
    private const val MAX_EDIT = 2

    @Volatile private var dictionary: Map<String, Long> = emptyMap()
    @Volatile private var deletes: Map<String, List<String>> = emptyMap()
    private val loading = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    fun ensureLoaded(context: Context) {
        if (ready.get() || !loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "symspell-load", isDaemon = true) {
            try {
                load(app)
                ready.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "load fail", e)
            } finally {
                loading.set(false)
            }
        }
    }

    fun isReady(): Boolean = ready.get()

    private fun load(context: Context) {
        val words = LinkedHashMap<String, Long>(80_000)
        // asset brain
        try {
            context.assets.open("hamdel_brain.txt").bufferedReader().useLines { lines ->
                var i = 0L
                lines.forEach { line ->
                    val w = line.trim()
                    if (w.isEmpty() || w.startsWith("#")) return@forEach
                    // higher frequency for earlier lines (common words first in file)
                    val freq = (100_000L - i).coerceAtLeast(1)
                    words[w] = maxOf(words[w] ?: 0L, freq)
                    i++
                    if (i % 8000L == 0L) try { Thread.sleep(1) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no asset brain", e)
        }
        // files dir copy
        try {
            val f = File(context.filesDir, "hamdel_brain.txt")
            if (f.exists()) {
                f.bufferedReader().useLines { lines ->
                    var i = 0L
                    lines.forEach { line ->
                        val w = line.trim()
                        if (w.isEmpty() || w.startsWith("#")) return@forEach
                        words.putIfAbsent(w, 10_000L - (i % 9000))
                        i++
                    }
                }
            }
        } catch (_: Exception) {}

        // core high-priority
        val core = listOf(
            "سلام", "درود", "عرض", "ادب", "احترام", "ممنون", "متشکرم", "لطفاً", "ببخشید",
            "خسته", "نباشید", "خداحافظ", "صبح", "بخیر", "شب", "بله", "نه", "باشه",
            "می‌روم", "می‌کنم", "می‌کنید", "می‌شود", "می‌خواهم", "دوستان", "عزیز",
            "امروز", "فردا", "دیروز", "خانه", "کار", "خوب", "عالی"
        )
        core.forEach { words[it] = 500_000L }

        dictionary = words

        // build delete index for edit distance 1 only (memory safe)
        val del = HashMap<String, MutableList<String>>(words.size * 2)
        for (w in words.keys) {
            if (w.length < 2 || w.length > 18) continue
            for (d in edits1(w)) {
                val list = del.getOrPut(d) { ArrayList(2) }
                if (list.size < 6 && w !in list) list.add(w)
            }
        }
        deletes = del
        Log.i(TAG, "SymSpell ready words=${words.size} deletes=${del.size}")
        System.gc()
    }

    private fun edits1(word: String): Set<String> {
        val res = HashSet<String>()
        for (i in word.indices) {
            res.add(word.removeRange(i, i + 1))
        }
        return res
    }

    fun correctWord(word: String): String {
        if (word.length <= 1) return word
        if (word.all { it.isDigit() || it in ".,/\\-_%+۰۱۲۳۴۵۶۷۸۹" }) return word
        val dict = dictionary
        if (dict.isEmpty()) return word
        if (dict.containsKey(word)) return word

        // candidates from deletes
        val candidates = LinkedHashSet<String>()
        if (deletes.containsKey(word)) {
            deletes[word]?.let { candidates.addAll(it) }
        }
        for (e in edits1(word)) {
            if (dict.containsKey(e)) candidates.add(e)
            deletes[e]?.let { candidates.addAll(it) }
        }

        var best = word
        var bestFreq = -1L
        var bestDist = MAX_EDIT + 1
        for (c in candidates) {
            val d = levenshtein(word, c, MAX_EDIT)
            if (d < 0 || d > MAX_EDIT) continue
            val f = dict[c] ?: 0L
            if (d < bestDist || (d == bestDist && f > bestFreq)) {
                bestDist = d
                bestFreq = f
                best = c
            }
        }
        return best
    }

    fun correctSentence(text: String): String {
        if (text.isBlank() || dictionary.isEmpty()) return text
        return text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { correctWord(it) }
    }

    private fun levenshtein(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return -1
        val m = a.length; val n = b.length
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
            if (rowMin > max) return -1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[n]
    }
}
