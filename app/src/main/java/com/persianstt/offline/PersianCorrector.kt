package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * مرحله ۱: تمرکز روی کلمات ساده.
 * اول SimpleVocab، بعد مغز ۵۰هزارتایی برای چسباندن کلمات شکسته.
 */
object PersianCorrector {

    private const val TAG = "PersianCorrector"

    fun ensureLoaded(context: Context) {
        try { SimpleVocab.ensureLoaded(context) } catch (e: Exception) {
            Log.w(TAG, "vocab fail", e)
        }
        try { BrainLexicon.ensureLoaded(context) } catch (e: Exception) {
            Log.w(TAG, "brain optional fail", e)
        }
    }

    fun fix(context: Context?, raw: String): String {
        if (raw.isBlank()) return raw
        if (context != null) ensureLoaded(context)

        var t = SimpleVocab.normalizeKey(raw)
        t = joinSpacedLetters(t)

        // 1) exact / compact map from simple vocab
        val compact = t.replace(" ", "")
        SimpleVocab.map[t]?.let { return finalize(it) }
        SimpleVocab.map[compact]?.let { return finalize(it) }

        // 2) if whole text is almost a simple phrase, complete it
        completePrefix(t)?.let { return finalize(it) }

        // 3) token-by-token simple vocab
        val parts = t.split(' ').filter { it.isNotEmpty() }.toMutableList()
        for (i in parts.indices) {
            val w = parts[i]
            val fixed = SimpleVocab.map[w] ?: SimpleVocab.map[w.replace(" ", "")]
            if (fixed != null) parts[i] = fixed
        }
        t = parts.joinToString(" ")

        // 4) phrase rules again after tokens
        SimpleVocab.map[t]?.let { return finalize(it) }
        completePrefix(t)?.let { return finalize(it) }

        // 5) join broken via big brain if available
        t = BrainLexicon.joinBroken(t)

        // 6) final simple map
        SimpleVocab.map[t]?.let { return finalize(it) }
        completePrefix(t)?.let { return finalize(it) }

        return finalize(t)
    }

    fun fix(raw: String): String = fix(null, raw)

    /** اگر متن کوتاه پیشوند یک عبارت ساده باشد → کاملش کن */
    private fun completePrefix(t: String): String? {
        if (t.length < 2) return null
        val c = t.replace(" ", "")
        // direct partials users hit
        val hard = mapOf(
            "عرضه سل" to "عرض سلام",
            "عرضهسلا" to "عرض سلام",
            "عرض سل" to "عرض سلام",
            "عرز سلا" to "عرض سلام",
            "عرض سلا" to "عرض سلام",
            "سلا" to "سلام",
            "سل" to "سلام"
        )
        hard[t]?.let { return it }
        hard[c]?.let { return it }

        for (phrase in SimpleVocab.correctPhrases) {
            val pc = phrase.replace(" ", "")
            if (pc.startsWith(c) && c.length * 2 >= pc.length) {
                // e.g. typed half of phrase
                return phrase
            }
            if (c.startsWith(pc.take(c.length.coerceAtMost(pc.length))) &&
                c.length >= 4 && phrase.startsWith(t.take(2))
            ) {
                // weak — only if very close length
                if (kotlin.math.abs(pc.length - c.length) <= 3) return phrase
            }
        }
        return null
    }

    private fun joinSpacedLetters(s: String): String {
        val parts = s.split(' ')
        if (parts.size < 2) return s
        val out = mutableListOf<String>()
        var i = 0
        while (i < parts.size) {
            if (parts[i].length == 1 && i + 1 < parts.size && parts[i + 1].length == 1) {
                val buf = StringBuilder()
                while (i < parts.size && parts[i].length == 1) {
                    buf.append(parts[i]); i++
                }
                val j = buf.toString()
                out.add(SimpleVocab.map[j] ?: j)
            } else {
                out.add(parts[i]); i++
            }
        }
        return out.joinToString(" ")
    }

    private fun finalize(s: String): String {
        var t = NumberNormalizer.normalize(PersianPostProcess.fix(s))
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
