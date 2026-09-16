package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Offline "edge AI" for Persian:
 * 1) Spell/grammar-ish fix via dictionary + Levenshtein
 * 2) Smart emoji from keyword / sentiment rules
 * No internet required.
 */
object OfflineAi {
    private const val TAG = "OfflineAi"
    private val ready = AtomicBoolean(false)

    /** Call once after vocab/brain assets exist */
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

    // ---------- 1) Text correction ----------

    fun correctText(context: Context?, raw: String): String {
        if (raw.isBlank()) return raw
        if (context != null) ensure(context)

        var t = SimpleVocab.normalizeKey(raw)
        // known phrase / token maps first
        t = PersianCorrector.fix(context, t)
        t = PersianPostProcess.fix(t)

        val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return t

        val out = tokens.map { tok -> correctToken(tok) }
        var joined = out.joinToString(" ")
        joined = PersianPostProcess.fix(joined)
        joined = NumberNormalizer.normalize(joined)
        return joined.replace(Regex("\\s+"), " ").trim()
    }

    private fun correctToken(tok: String): String {
        if (tok.length <= 1) return tok
        // keep pure numbers / punctuation
        if (tok.all { it.isDigit() || it in ".,/\\-_%+۰۱۲۳۴۵۶۷۸۹" }) return tok

        val map = SimpleVocab.map
        map[tok]?.let { return it }
        map[tok.replace("\u200c", "")]?.let { return it }

        // exact in brain
        val compact = tok.replace(" ", "").replace("\u200c", "")
        // already correct if short and mapped
        if (map.containsKey(tok) || map.containsValue(tok)) return tok

        // Levenshtein nearest among candidates with same first char / similar length
        val candidates = candidateList(tok)
        if (candidates.isEmpty()) return tok

        var best = tok
        var bestDist = 3 // max edit distance
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
        val phrases = SimpleVocab.correctPhrases
        val pool = LinkedHashSet<String>()
        val first = tok.firstOrNull()
        val len = tok.length
        // from map values (correct forms)
        for (v in map.values) {
            if (v.length in (len - 2)..(len + 2)) {
                if (first == null || v.firstOrNull() == first || v.firstOrNull() == tok.getOrNull(0)) {
                    pool.add(v)
                }
            }
            if (pool.size > 80) break
        }
        for (p in phrases) {
            if (p.length in (len - 2)..(len + 2)) pool.add(p)
            if (pool.size > 120) break
        }
        // also keys that are "correct-looking"
        return pool.toList()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > 3) return 99
        val m = a.length
        val n = b.length
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

    // ---------- 2) Smart emoji ----------

    private data class Rule(val keys: List<String>, val emoji: String, val weight: Int = 1)

    private val emojiRules = listOf(
        Rule(listOf("تولد", "تولدت", "birthday"), "🎂", 3),
        Rule(listOf("خنده", "خندیدم", "جوک", "بامزه", "خنده‌دار"), "😂", 3),
        Rule(listOf("ناراحت", "غمگین", "گریه", "دلم گرفته"), "😔", 3),
        Rule(listOf("عاشق", "دوستت دارم", "عشق", "قلب"), "❤️", 3),
        Rule(listOf("صبح بخیر", "صبح"), "☀️", 2),
        Rule(listOf("شب بخیر", "شب خوش"), "🌙", 2),
        Rule(listOf("خسته نباشید", "خسته نباشی", "خدا قوت"), "💪", 2),
        Rule(listOf("ممنون", "متشکر", "مرسی", "تشکر"), "🙏", 2),
        Rule(listOf("سلام", "درود", "عرض سلام"), "👋", 2),
        Rule(listOf("خداحافظ", "فعلا"), "👋", 2),
        Rule(listOf("عالی", "عالیه", "فوق العاده", "محشر"), "✨", 2),
        Rule(listOf("هوا", "آفتابی", "آفتاب"), "☀️", 1),
        Rule(listOf("باران", "بارونی"), "🌧️", 2),
        Rule(listOf("برف"), "❄️", 2),
        Rule(listOf("قهوه"), "☕", 2),
        Rule(listOf("چای"), "🍵", 2),
        Rule(listOf("غذا", "ناهار", "شام", "صبحانه"), "🍽️", 2),
        Rule(listOf("سفر", "مسافرت", "پرواز"), "✈️", 2),
        Rule(listOf("ماشین", "رانندگی"), "🚗", 1),
        Rule(listOf("خانه", "خونه"), "🏠", 1),
        Rule(listOf("کار", "اداره", "شرکت"), "💼", 1),
        Rule(listOf("درس", "مدرسه", "دانشگاه", "امتحان"), "📚", 2),
        Rule(listOf("تبریک", "مبارک"), "🎉", 3),
        Rule(listOf("فوتبال", "گل"), "⚽", 2),
        Rule(listOf("موسیقی", "آهنگ"), "🎵", 2),
        Rule(listOf("پول", "تومان", "قیمت"), "💰", 1),
        Rule(listOf("کمک", "اورژانس"), "🆘", 2),
        Rule(listOf("خواب", "خسته"), "😴", 1),
        Rule(listOf("عصبانی", "خشم"), "😠", 2),
        Rule(listOf("موفق", "موفقیت"), "🌟", 2),
        Rule(listOf("احترام", "ادب"), "🙇", 1)
    )

    /**
     * Insert emojis near matched phrases (not only at end).
     */
    fun addEmojis(text: String): String {
        if (text.isBlank()) return text
        var t = text
        // avoid doubling if already has emoji
        val hits = mutableListOf<Pair<IntRange, String>>()
        for (rule in emojiRules.sortedByDescending { r -> r.keys.maxOf { it.length } }) {
            for (k in rule.keys.sortedByDescending { it.length }) {
                var idx = t.indexOf(k)
                while (idx >= 0) {
                    val end = idx + k.length
                    // skip if emoji already right after
                    val after = t.substring(end, min(t.length, end + 3))
                    if (after.none { Character.getType(it) == Character.SURROGATE.toInt() || it.code > 0x1F000 }) {
                        hits.add((idx until end) to rule.emoji)
                    }
                    idx = t.indexOf(k, idx + 1)
                }
            }
        }
        if (hits.isEmpty()) {
            // light sentiment fallback
            val pos = listOf("خوب", "عالی", "خوش", "عالیه", "خوشحال")
            val neg = listOf("بد", "افتضاح", "ناراحت", "خراب")
            when {
                pos.any { t.contains(it) } -> return "$t 😊"
                neg.any { t.contains(it) } -> return "$t 😔"
                else -> return t
            }
        }
        // apply from end to start
        val ordered = hits.distinctBy { it.first.first }.sortedByDescending { it.first.first }
        val sb = StringBuilder(t)
        for ((range, emo) in ordered) {
            val insertAt = range.last + 1
            if (insertAt <= sb.length) {
                // don't insert twice same place
                val peek = if (insertAt < sb.length) sb[insertAt] else ' '
                if (peek.toString() != emo && !emo.contains(peek)) {
                    sb.insert(insertAt, " $emo")
                }
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
