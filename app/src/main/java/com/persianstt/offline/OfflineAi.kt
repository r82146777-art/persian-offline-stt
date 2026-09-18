package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Fully offline Persian AI:
 * - spell/phrase fix via dictionary + Levenshtein + hard ASR maps
 * - smart emoji by keyword rules (inline)
 * No network.
 */
object OfflineAi {
    private const val TAG = "OfflineAi"
    private val ready = AtomicBoolean(false)

    fun ensure(context: Context) {
        if (ready.getAndSet(true)) return
        try {
            SimpleVocab.ensureLoaded(context)
            BrainLexicon.ensureLoaded(context)
        } catch (e: Exception) {
            Log.w(TAG, "ensure", e)
            ready.set(false)
        }
    }

    // Common ASR / typo fixes (longer first)
    private val hardFixes: List<Pair<String, String>> = listOf(
        "عرض سلام و ادب و احترام" to "عرض سلام و ادب و احترام",
        "عرز سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "عرضه سل م اتاب اختر" to "عرض سلام و ادب و احترام",
        "ارزی سلا م اتاب اختر" to "عرض سلام و ادب و احترام",
        "عرضسلامادباحتر" to "عرض سلام و ادب و احترام",
        "عرض سلام ادب احترام" to "عرض سلام و ادب و احترام",
        "سلام عرض ادب احترام" to "سلام و عرض ادب و احترام",
        "سلام و عرض ادب" to "سلام و عرض ادب",
        "عرضه سل" to "عرض سلام",
        "عرض سل" to "عرض سلام",
        "عرز سلام" to "عرض سلام",
        "عرضسلا" to "عرض سلام",
        "عدابه احترام" to "ادب و احترام",
        "اتاب اختر" to "ادب و احترام",
        "عداب احترام" to "ادب و احترام",
        "د شتانه عزی" to "دوستان عزیز",
        "دشتانه عزیز" to "دوستان عزیز",
        "خدمت تمام دوستان عزیز" to "خدمت تمام دوستان عزیز",
        "خدمته تمام" to "خدمت تمام",
        "خسته نباشی" to "خسته نباشید",
        "خدا حافظ" to "خداحافظ",
        "صبح بخیر" to "صبح بخیر",
        "شب بخیر" to "شب بخیر",
        "وقت بخیر" to "وقت بخیر",
        "روز بخیر" to "روز بخیر",
        "خیلی ممنون" to "خیلی ممنون",
        "دستت درد نکنه" to "دستت درد نکنه",
        "خواهش میکنم" to "خواهش می‌کنم",
        "خواهش می کنم" to "خواهش می‌کنم",
        "می روم" to "می‌روم",
        "می کنم" to "می‌کنم",
        "می کنید" to "می‌کنید",
        "می شود" to "می‌شود",
        "می خواهم" to "می‌خواهم",
        "می تونم" to "می‌تونم",
        "می تونید" to "می‌تونید",
        "میکنم" to "می‌کنم",
        "میکنید" to "می‌کنید",
        "میروم" to "می‌روم",
        "میشود" to "می‌شود",
        "هم اکنون" to "هم‌اکنون",
        "هماکنون" to "هم‌اکنون"
    ).sortedByDescending { it.first.length }

    private val wordFixes = mapOf(
        "سلام" to "سلام", "سلا" to "سلام", "سل" to "سلام",
        "درود" to "درود", "ممنون" to "ممنون", "ممنونم" to "ممنونم",
        "مرسی" to "مرسی", "متشکرم" to "متشکرم", "لطفا" to "لطفاً",
        "ببخشید" to "ببخشید", "بله" to "بله", "آره" to "آره", "نه" to "نه",
        "باشه" to "باشه", "چشم" to "چشم", "خوب" to "خوب", "عالی" to "عالی",
        "عالیه" to "عالیه", "بد" to "بد", "امروز" to "امروز", "فردا" to "فردا",
        "دیروز" to "دیروز", "خانه" to "خانه", "خونه" to "خونه", "کار" to "کار",
        "دوست" to "دوست", "دوستان" to "دوستان", "عزیز" to "عزیز",
        "احترام" to "احترام", "ادب" to "ادب", "عرض" to "عرض"
    )

    fun correctText(context: Context?, raw: String): String {
        if (raw.isBlank()) return raw
        if (context != null) ensure(context)

        var t = raw.trim()
            .replace('\u200c', '\u200c')
            .replace(Regex("[\\u064B-\\u065F]"), "") // strip diacritics noise
            .replace(Regex("\\s+"), " ")
            .trim()

        // strip garbage prefixes models sometimes inject
        t = t.replace(Regex("^(متن اصلاح[‌ ]*شده[:：]?\\s*)"), "")
        t = t.replace(Regex("^(خروجی[:：]?\\s*)"), "")
        t = t.replace(Regex("^(نتیجه[:：]?\\s*)"), "")

        for ((bad, good) in hardFixes) {
            if (t.contains(bad)) t = t.replace(bad, good)
        }

        // SimpleVocab / corrector maps
        try {
            t = PersianCorrector.fix(context, t)
        } catch (_: Exception) {}

        t = PersianPostProcess.fix(t)

        // token-level
        val tokens = t.split(' ').filter { it.isNotEmpty() }
        val fixedTokens = tokens.map { tok -> fixToken(tok) }
        t = fixedTokens.joinToString(" ")

        // join broken words with brain
        try {
            t = BrainLexicon.joinBroken(t)
        } catch (_: Exception) {}

        t = NumberNormalizer.normalize(t)
        t = t.replace(Regex("""\bمی\s+([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی]+)"""), "می‌$1")
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    private fun fixToken(tok: String): String {
        if (tok.length <= 1) return tok
        if (tok.all { it.isDigit() || it in ".,/\\-_%+۰۱۲۳۴۵۶۷۸۹" }) return tok
        wordFixes[tok]?.let { return it }
        val map = try { SimpleVocab.map } catch (_: Exception) { emptyMap() }
        map[tok]?.let { return it }
        map[tok.replace("\u200c", "")]?.let { return it }

        // Levenshtein among short candidates
        val len = tok.length
        if (len > 12) return tok
        var best = tok
        var bestD = 2
        val pool = LinkedHashSet<String>()
        pool.addAll(wordFixes.keys)
        pool.addAll(wordFixes.values)
        for (v in map.values.take(200)) {
            if (v.length in (len - 1)..(len + 1)) pool.add(v)
        }
        for (c in pool) {
            if (c.firstOrNull() != tok.firstOrNull() && len > 3) continue
            val d = levenshtein(tok, c)
            if (d in 1..bestD) {
                bestD = d
                best = wordFixes[c] ?: map[c] ?: c
                if (d == 1) break
            }
        }
        return best
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

    // ---------- Emoji ----------

    private data class EmojiRule(val keys: List<String>, val emoji: String)

    private val emojiRules = listOf(
        EmojiRule(listOf("عرض سلام و ادب و احترام", "عرض سلام", "ادب و احترام"), "🙇"),
        EmojiRule(listOf("صبح بخیر"), "☀️"),
        EmojiRule(listOf("شب بخیر"), "🌙"),
        EmojiRule(listOf("خسته نباشید", "خسته نباشی", "خدا قوت"), "💪"),
        EmojiRule(listOf("دوستت دارم", "عاشقتم", "عاشق"), "❤️"),
        EmojiRule(listOf("تولد", "تولدت"), "🎂"),
        EmojiRule(listOf("تبریک", "مبارک"), "🎉"),
        EmojiRule(listOf("خنده", "خندیدم", "جوک", "بامزه"), "😂"),
        EmojiRule(listOf("ناراحت", "غمگین", "گریه"), "😔"),
        EmojiRule(listOf("ممنونم", "ممنون", "متشکرم", "مرسی", "تشکر"), "🙏"),
        EmojiRule(listOf("خداحافظ", "فعلاً", "فعلا"), "👋"),
        EmojiRule(listOf("سلام", "درود"), "👋"),
        EmojiRule(listOf("عالی", "عالیه", "فوق العاده", "محشر"), "✨"),
        EmojiRule(listOf("خوب", "خوشحال", "خوشحالم"), "😊"),
        EmojiRule(listOf("باران"), "🌧️"),
        EmojiRule(listOf("برف"), "❄️"),
        EmojiRule(listOf("قهوه"), "☕"),
        EmojiRule(listOf("چای"), "🍵"),
        EmojiRule(listOf("غذا", "ناهار", "شام", "صبحانه"), "🍽️"),
        EmojiRule(listOf("سفر", "مسافرت"), "✈️"),
        EmojiRule(listOf("خانه", "خونه"), "🏠"),
        EmojiRule(listOf("کار", "اداره"), "💼"),
        EmojiRule(listOf("درس", "مدرسه", "دانشگاه"), "📚"),
        EmojiRule(listOf("فوتبال"), "⚽"),
        EmojiRule(listOf("موسیقی", "آهنگ"), "🎵"),
        EmojiRule(listOf("کمک"), "🆘"),
        EmojiRule(listOf("خواب", "خسته"), "😴")
    )

    fun addEmojis(text: String): String {
        if (text.isBlank()) return "😊"
        var t = text.trim()
        // remove previous trailing generic sparkles to re-apply cleanly
        t = t.replace(Regex("\\s*✨\\s*$"), "").trim()

        val used = mutableSetOf<String>()
        val sorted = emojiRules.sortedByDescending { r -> r.keys.maxOf { it.length } }
        for (rule in sorted) {
            for (k in rule.keys.sortedByDescending { it.length }) {
                if (!t.contains(k)) continue
                if (t.contains(rule.emoji)) continue
                if (rule.emoji in used) continue
                val idx = t.indexOf(k) + k.length
                t = t.substring(0, idx) + " " + rule.emoji + t.substring(idx)
                used.add(rule.emoji)
                break
            }
        }
        if (used.isEmpty()) {
            // mild sentiment
            when {
                listOf("بد", "افتضاح", "ناراحت").any { t.contains(it) } -> t = "$t 😔"
                listOf("خوب", "عالی", "مرسی", "ممنون").any { t.contains(it) } -> t = "$t 😊"
                else -> t = "$t ✨"
            }
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
