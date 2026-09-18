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
 * Lightweight SymSpell-style corrector for Persian (offline).
 * Uses frequency dictionary from assets/hamdel_brain.txt (~50k).
 * Edit distance 1–2, RAM-friendly, no neural net.
 */
object SymSpell {
    private const val TAG = "SymSpell"
    private const val ASSET = "hamdel_brain.txt"
    private const val FILE = "symspell_freq.txt"

    @Volatile private var freq: Map<String, Int> = emptyMap()
    @Volatile private var deletes: Map<String, List<String>> = emptyMap()
    private val loading = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    fun ensureLoaded(context: Context) {
        if (ready.get()) return
        if (!loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "symspell", isDaemon = true) {
            try {
                load(app)
                ready.set(true)
            } catch (e: Throwable) {
                Log.e(TAG, "load fail", e)
            } finally {
                loading.set(false)
            }
        }
        // also try sync light core for immediate use
        try {
            if (freq.isEmpty()) loadCore()
        } catch (_: Exception) {}
    }

    private fun loadCore() {
        val core = listOf(
            "سلام", "درود", "عرض", "ادب", "احترام", "ممنون", "متشکرم", "خسته",
            "نباشید", "خداحافظ", "بله", "نه", "باشه", "لطفا", "ببخشید", "دوستان",
            "عزیز", "امروز", "فردا", "خانه", "کار", "خوب", "عالی", "صبح", "بخیر",
            "شب", "می‌روم", "می‌کنم", "می‌شود", "می‌خواهم"
        )
        val f = LinkedHashMap<String, Int>()
        core.forEachIndexed { i, w -> f[w] = 10000 - i }
        freq = f
    }

    private fun load(context: Context) {
        val dest = File(context.filesDir, FILE)
        if (!dest.exists() || dest.length() < 10_000) {
            context.assets.open(ASSET).use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
        }
        val f = HashMap<String, Int>(60_000)
        var rank = 50_000
        BufferedReader(InputStreamReader(dest.inputStream(), Charsets.UTF_8), 64 * 1024).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val w = line!!.trim()
                if (w.isEmpty() || w.startsWith("#")) continue
                // support "word" or "word freq"
                val parts = w.split(Regex("\\s+"))
                val word = parts[0]
                val fr = if (parts.size > 1) parts[1].toIntOrNull() ?: rank else rank
                if (word.length in 2..30) {
                    f[word] = maxOf(f[word] ?: 0, fr)
                    rank = (rank - 1).coerceAtLeast(1)
                }
            }
        }
        // generate deletes (edit distance 1) for words up to reasonable length
        val del = HashMap<String, ArrayList<String>>(f.size * 2)
        var n = 0
        for (word in f.keys) {
            if (word.length > 14) continue
            for (d in deletes1(word)) {
                val list = del.getOrPut(d) { ArrayList(2) }
                if (list.size < 8 && word !in list) list.add(word)
            }
            n++
            if (n % 4000 == 0) try { Thread.sleep(1) } catch (_: Exception) {}
        }
        freq = f
        deletes = del
        Log.i(TAG, "loaded freq=${f.size} deletes=${del.size}")
        System.gc()
    }

    private fun deletes1(word: String): List<String> {
        val out = ArrayList<String>(word.length)
        for (i in word.indices) {
            out.add(word.removeRange(i, i + 1))
        }
        return out
    }

    fun correctWord(word: String): String {
        if (word.length < 2) return word
        if (word.all { it.isDigit() || it in ".,/\\-_%+۰۱۲۳۴۵۶۷۸۹" }) return word
        val f = freq
        if (f.isEmpty()) return word
        if (f.containsKey(word)) return word

        var best = word
        var bestScore = -1

        // exact delete match → candidates
        val cands = LinkedHashSet<String>()
        deletes[word]?.let { cands.addAll(it) }
        for (d in deletes1(word)) {
            if (f.containsKey(d)) cands.add(d)
            deletes[d]?.let { cands.addAll(it) }
        }
        // also try words with same prefix
        val pref = word.take(2)
        var checked = 0
        for ((w, fr) in f) {
            if (w.startsWith(pref) && kotlin.math.abs(w.length - word.length) <= 2) {
                cands.add(w)
            }
            if (++checked > 3000) break
        }

        for (c in cands) {
            val dist = editDistance(word, c, max = 2)
            if (dist < 0) continue
            val score = (f[c] ?: 1) - dist * 500
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /** Correct only tokens that look wrong; keep known-good words. */
    fun correctSentence(text: String): String {
        if (text.isBlank() || freq.isEmpty()) return text
        return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { tok ->
            val core = tok.trim('،', '.', '!', '؟', ':', ';', ',', '"', '\'')
            if (core.length < 2) tok
            else {
                val fixed = correctWord(core)
                if (fixed == core) tok else tok.replace(core, fixed)
            }
        }
    }

    private fun editDistance(a: String, b: String, max: Int): Int {
        if (a == b) return 0
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
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > max) return -1
            val tmp = prev; prev = cur; cur = tmp
        }
        val d = prev[n]
        return if (d <= max) d else -1
    }
}
