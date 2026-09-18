package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

object OfflineAi {
    private const val TAG = "OfflineAi"
    private val ready = AtomicBoolean(false)

    fun ensure(context: Context) {
        if (ready.get()) return
        try {
            SimpleVocab.ensureLoaded(context)
            BrainLexicon.ensureLoaded(context)
            ready.set(true)
        } catch (e: Exception) {
            Log.w(TAG, "ensure", e)
        }
    }

    private val hardFixes = listOf(
        "عرز سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "عرضه سل" to "عرض سلام",
        "عرز سلام" to "عرض سلام",
        "عدابه احترام" to "ادب و احترام",
        "اتاب اختر" to "ادب و احترام",
        "د شتانه عزی" to "دوستان عزیز",
        "دشتانه عزیز" to "دوستان عزیز",
        "خدمته تمام" to "خدمت تمام",
        "سلام عرض ادب" to "سلام و عرض ادب",
        "عرضسلام" to "عرض سلام",
        "خسته نباشی" to "خسته نباشید",
        "خدا حافظ" to "خداحافظ",
        "می روم" to "می‌روم",
        "می کنم" to "می‌کنم",
        "می کنید" to "می‌کنید",
        "می شود" to "می‌شود",
        "می خواهم" to "می‌خواهم",
        "میکنم" to "می‌کنم",
        "میکنید" to "می‌کنید",
        "میروم" to "می‌روم"
    ).sortedByDescending { it.first.length }

    fun correctText(context: Context?, raw: String): String {
        if (raw.isBlank()) return raw
        if (context != null) ensure(context)
        var t = SimpleVocab.normalizeKey(raw)
        for ((bad, good) in hardFixes) {
            if (t.contains(bad)) t = t.replace(bad, good)
        }
        t = PersianCorrector.fix(context, t)
        t = PersianPostProcess.fix(t)

        val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = tokens.map { tok -> correctToken(tok) }
        t = out.joinToString(" ")
        t = NumberNormalizer.normalize(PersianPostProcess.fix(t))
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun correctToken(tok: String): String {
        if (tok.length <= 1) return tok
        if (tok.all { it.isDigit() || it in ".,/\\-_%+۰۱۲۳۴۵۶۷۸۹" }) return tok
        val map = SimpleVocab.map
        map[tok]?.let { return it }
        map[tok.replace("\u200c", "")]?.let { return it }
        val candidates = candidateList(tok)
        var best = tok
        var bestDist = 2
        for (c in candidates) {
            val d = levenshtein(tok, c)
            if (d in 1..bestDist) {
                bestDist = d
                best = c
                if (d == 1) break
            }
        }
        return best
    }

    private fun candidateList(tok: String): List<String> {
        val map = SimpleVocab.map
        val pool = LinkedHashSet<String>()
        val first = tok.firstOrNull()
        val len = tok.length
        for (v in map.values) {
            if (v.length in (len - 2)..(len + 2) && (first == null || v.firstOrNull() == first)) {
                pool.add(v)
            }
            if (pool.size > 100) break
        }
        return pool.toList()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 2) return 99
        val m = a.length; val n = b.length
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..n) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[n]
    }

    private data class Rule(val keys: List<String>, val emoji: String)

    private val emojiRules = listOf(
        Rule(listOf("تولد"), "🎂"),
        Rule(listOf("خنده", "جوک", "بامزه"), "😂"),
        Rule(listOf("ناراحت", "غمگین", "گریه"), "😔"),
        Rule(listOf("عاشق", "دوستت دارم", "عشق"), "❤️"),
        Rule(listOf("صبح بخیر", "صبح"), "☀️"),
        Rule(listOf("شب بخیر"), "🌙"),
        Rule(listOf("خسته نباشید", "خسته نباشی"), "💪"),
        Rule(listOf("ممنون", "متشکر", "مرسی", "تشکر"), "🙏"),
        Rule(listOf("سلام", "درود", "عرض سلام"), "👋"),
        Rule(listOf("خداحافظ"), "👋"),
        Rule(listOf("عالی", "عالیه", "فوق العاده"), "✨"),
        Rule(listOf("باران"), "🌧️"),
        Rule(listOf("قهوه"), "☕"),
        Rule(listOf("چای"), "🍵"),
        Rule(listOf("غذا", "ناهار", "شام"), "🍽️"),
        Rule(listOf("سفر", "مسافرت"), "✈️"),
        Rule(listOf("خانه", "خونه"), "🏠"),
        Rule(listOf("تبریک", "مبارک"), "🎉"),
        Rule(listOf("احترام", "ادب"), "🙇"),
        Rule(listOf("خوب", "خوشحال"), "😊")
    )

    fun addEmojis(text: String): String {
        if (text.isBlank()) return "😊"
        var t = text.trim()
        var added = false
        val sorted = emojiRules.sortedByDescending { r -> r.keys.maxOf { it.length } }
        for (rule in sorted) {
            for (k in rule.keys.sortedByDescending { it.length }) {
                if (t.contains(k) && !t.contains(rule.emoji)) {
                    // insert after first occurrence
                    val idx = t.indexOf(k) + k.length
                    t = t.substring(0, idx) + " " + rule.emoji + t.substring(idx)
                    added = true
                    break
                }
            }
            if (added) break
        }
        if (!added) {
            // always append something so user sees change
            t = "$t ✨"
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
