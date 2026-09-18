package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * Offline AI layer dedicated to voice-typing post-ASR.
 * Runs after Vosk: phrase repair + SymSpell + Persian normalize.
 * Fully offline, no network.
 */
object OfflineVoiceAi {
    private const val TAG = "OfflineVoiceAi"

    /** Phonetic / ASR confusion pairs (bad → good), longest first */
    private val asrFixes: List<Pair<String, String>> = listOf(
        "عرض سلام و ادب و احترام خدمت تمام دوستان عزیز" to "عرض سلام و ادب و احترام خدمت تمام دوستان عزیز",
        "چخبر" to "چه خبر",
        "چ طوری" to "چطوری",
        "خوبی تو" to "خوبی تو",
        "سلام علیکم" to "سلام علیکم",
        "دمت گرم" to "دمت گرم",
        "قربونت" to "قربونت",
        "عرز سلام عدابه احترام خدمته تمام د شتانه عزی" to "عرض سلام و ادب و احترام خدمت تمام دوستان عزیز",
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
        "سلا م" to "سلام",
        "س لام" to "سلام",
        "عدابه احترام" to "ادب و احترام",
        "اتاب اختر" to "ادب و احترام",
        "عداب احترام" to "ادب و احترام",
        "د شتانه عزی" to "دوستان عزیز",
        "دشتانه عزیز" to "دوستان عزیز",
        "دوستان عزی" to "دوستان عزیز",
        "خدمته تمام" to "خدمت تمام",
        "خدا حافظ" to "خداحافظ",
        "خسته نباشی" to "خسته نباشید",
        "صبح بخیر" to "صبح بخیر",
        "شب بخیر" to "شب بخیر",
        "وقت بخیر" to "وقت بخیر",
        "روز بخیر" to "روز بخیر",
        "خیلی ممنون" to "خیلی ممنون",
        "خواهش میکنم" to "خواهش می‌کنم",
        "خواهش می کنم" to "خواهش می‌کنم",
        "می روم" to "می‌روم",
        "می کنم" to "می‌کنم",
        "می کنید" to "می‌کنید",
        "می شود" to "می‌شود",
        "می شه" to "می‌شه",
        "می خواهم" to "می‌خواهم",
        "می خوام" to "می‌خوام",
        "می تونم" to "می‌تونم",
        "می تونید" to "می‌تونید",
        "میکنم" to "می‌کنم",
        "میکنید" to "می‌کنید",
        "میروم" to "می‌روم",
        "میشود" to "می‌شود",
        "میخوام" to "می‌خوام",
        "هم اکنون" to "هم‌اکنون",
        "هماکنون" to "هم‌اکنون",
        "این که" to "اینکه",
        "آن که" to "آنکه"
    ).sortedByDescending { it.first.length }

    fun improve(context: Context, raw: String): String {
        if (raw.isBlank()) return raw
        try {
            SymSpell.ensureLoaded(context)
            OfflineAi.ensure(context)
        } catch (_: Exception) {}

        var t = raw.trim().replace(Regex("\\s+"), " ")
        // remove isolated junk single letters between spaces (common CTC garbage)
        t = t.replace(Regex("\\s+[آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءئ]\\s+"), " ")

        for ((bad, good) in asrFixes) {
            if (t.contains(bad)) t = t.replace(bad, good)
        }

        t = t.replace(Regex("""\bمی\s+([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی]+)"""), "می‌$1")

        try {
            val sym = SymSpell.correctSentence(t)
            if (sym.isNotBlank() && sym.length >= (t.length * 7) / 10) t = sym
        } catch (e: Exception) {
            Log.w(TAG, "symspell", e)
        }

        try {
            t = BrainLexicon.joinBroken(t)
        } catch (_: Exception) {}

        t = PersianPostProcess.fix(t)
        t = NumberNormalizer.normalize(t)
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
