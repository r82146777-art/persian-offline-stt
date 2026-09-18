package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Conservative offline corrector:
 * - Only fix KNOWN bad ASR phrases / clear typos
 * - Never "improve" already-correct text with aggressive Levenshtein
 * - Emoji: insert by keyword without wiping the sentence
 */
object OfflineAi {
    private const val TAG = "OfflineAi"
    private val ready = AtomicBoolean(false)

    fun ensure(context: Context) {
        if (ready.getAndSet(true)) return
        try {
            SimpleVocab.ensureLoaded(context)
            BrainLexicon.ensureLoaded(context)
            SymSpell.ensureLoaded(context)
        } catch (e: Exception) {
            Log.w(TAG, "ensure", e)
            ready.set(false)
        }
    }

    /** Only known-bad → good. Sorted longest-first. */
    private val hardFixes: List<Pair<String, String>> = listOf(
        "عرز سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "عرضه سل م اتاب اختر" to "عرض سلام و ادب و احترام",
        "ارزی سلا م اتاب اختر" to "عرض سلام و ادب و احترام",
        "عرضسلامادباحتر" to "عرض سلام و ادب و احترام",
        "عرض سلام ادب احترام" to "عرض سلام و ادب و احترام",
        "عرضه سل" to "عرض سلام",
        "عرض سل" to "عرض سلام",
        "عرز سلام" to "عرض سلام",
        "عرضسلا" to "عرض سلام",
        "عدابه احترام" to "ادب و احترام",
        "اتاب اختر" to "ادب و احترام",
        "عداب احترام" to "ادب و احترام",
        "د شتانه عزی" to "دوستان عزیز",
        "دشتانه عزیز" to "دوستان عزیز",
        "خدمته تمام" to "خدمت تمام",
        "خدا حافظ" to "خداحافظ",
        "خسته نباشی" to "خسته نباشید",
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

    /**
     * Safe correct: if text already looks fine, return as-is (only light normalize).
     */
    fun correctText(context: Context?, raw: String): String {
        if (raw.isBlank()) return raw
        if (context != null) ensure(context)

        val original = raw.trim().replace(Regex("\\s+"), " ")
        var t = original

        // strip model junk prefixes if any
        t = t.replace(Regex("^(متن اصلاح[‌ ]*شده[:：]?\\s*|خروجی[:：]?\\s*|نتیجه[:：]?\\s*)"), "")

        var changed = false
        for ((bad, good) in hardFixes) {
            if (t.contains(bad)) {
                t = t.replace(bad, good)
                changed = true
            }
        }

        // mi + space + verb → half-space (safe)
        val t2 = t.replace(Regex("""\bمی\s+([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی]+)"""), "می‌$1")
        if (t2 != t) {
            t = t2
            changed = true
        }

        // only light post-process if we already changed something, or always normalize numbers/spaces
        t = PersianPostProcess.fix(t)
        t = NumberNormalizer.normalize(t)
        t = t.replace(Regex("\\s+"), " ").trim()

        // If we didn't apply any hard fix and result is shorter/worse, keep original
        if (!changed && t.length < original.length * 0.8) return original
        // If nothing meaningful changed, keep original (don't invent damage)
        if (!changed && t == original) return original
        return t
    }

    private data class EmojiRule(val keys: List<String>, val emoji: String)

    private val emojiRules = listOf(
        EmojiRule(listOf("عرض سلام و ادب و احترام", "عرض سلام", "ادب و احترام"), "🙇"),
        EmojiRule(listOf("صبح بخیر"), "☀️"),
        EmojiRule(listOf("شب بخیر"), "🌙"),
        EmojiRule(listOf("خسته نباشید", "خسته نباشی", "خدا قوت"), "💪"),
        EmojiRule(listOf("دوستت دارم", "عاشقتم", "عاشق"), "❤️"),
        EmojiRule(listOf("تولد", "تولدت"), "🎂"),
        EmojiRule(listOf("تبریک", "مبارک"), "🎉"),
        EmojiRule(listOf("خنده", "جوک", "بامزه"), "😂"),
        EmojiRule(listOf("ناراحت", "غمگین", "گریه"), "😔"),
        EmojiRule(listOf("ممنونم", "ممنون", "متشکرم", "مرسی", "تشکر"), "🙏"),
        EmojiRule(listOf("خداحافظ"), "👋"),
        EmojiRule(listOf("سلام", "درود"), "👋"),
        EmojiRule(listOf("عالی", "عالیه", "فوق العاده"), "✨"),
        EmojiRule(listOf("خوب", "خوشحال"), "😊"),
        EmojiRule(listOf("باران"), "🌧️"),
        EmojiRule(listOf("قهوه"), "☕"),
        EmojiRule(listOf("چای"), "🍵"),
        EmojiRule(listOf("غذا", "ناهار", "شام"), "🍽️"),
        EmojiRule(listOf("سفر", "مسافرت"), "✈️"),
        EmojiRule(listOf("خانه", "خونه"), "🏠"),
        EmojiRule(listOf("کار"), "💼"),
        EmojiRule(listOf("درس", "مدرسه"), "📚"),
        EmojiRule(listOf("کمک"), "🆘")
    )

    fun addEmojis(text: String): String {
        if (text.isBlank()) return "😊"
        var t = text.trim()
        val had = t
        val used = mutableSetOf<String>()
        for (rule in emojiRules.sortedByDescending { r -> r.keys.maxOf { it.length } }) {
            for (k in rule.keys.sortedByDescending { it.length }) {
                if (!t.contains(k)) continue
                if (t.contains(rule.emoji)) {
                    used.add(rule.emoji)
                    continue
                }
                if (rule.emoji in used) continue
                val idx = t.indexOf(k) + k.length
                t = t.substring(0, idx) + " " + rule.emoji + t.substring(idx)
                used.add(rule.emoji)
                break
            }
        }
        if (used.isEmpty() && t == had) {
            t = "$t ✨"
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
