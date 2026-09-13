package com.persianstt.offline

/**
 * Our own Persian post-ASR layer — rules we improve over time.
 * Order: longer phrases first.
 */
object PersianCorrector {

    // Full-phrase replacements (garbled ASR → correct)
    private val phrases = listOf(
        // user test phrases & common greetings
        "عرز سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "عرز سلام عداب احترام" to "عرض سلام و ادب و احترام",
        "عرض سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "ارزی سلا م اتاب اختر" to "عرض سلام و ادب و احترام",
        "ارزی سلام اتاب اختر" to "عرض سلام و ادب و احترام",
        "عرضسلامادباحتر" to "عرض سلام و ادب و احترام",
        "عرض سلام ادب احترام" to "عرض سلام و ادب و احترام",
        "عرض سلام و ادب احترام" to "عرض سلام و ادب و احترام",
        "خدمته تمام د شتانه عزی" to "خدمت تمام دوستان عزیز",
        "خدمته تمام دوستان عزی" to "خدمت تمام دوستان عزیز",
        "خدمت تمام د شتانه عزی" to "خدمت تمام دوستان عزیز",
        "د شتانه عزی" to "دوستان عزیز",
        "دشتانه عزیز" to "دوستان عزیز",
        "شتانه عزی" to "دوستان عزیز",
        "سلام ها عرضه ا" to "سلام و عرض ادب",
        "سلام عرض ادب" to "سلام و عرض ادب",
        "عرضسلام" to "عرض سلام",
        "عدابه احترام" to "ادب و احترام",
        "عداب احترام" to "ادب و احترام",
        "اتاب اختر" to "ادب و احترام",
        "اتاب احترام" to "ادب و احترام",
        "خسته نباشید" to "خسته نباشید",
        "خسته نباشی" to "خسته نباشی",
        "صبح بخیر" to "صبح بخیر",
        "شب بخیر" to "شب بخیر",
        "خداحافظ" to "خداحافظ",
        "ممنونم" to "ممنونم",
        "متشکرم" to "متشکرم",
        "خواهش میکنم" to "خواهش می‌کنم",
        "خواهش می کنم" to "خواهش می‌کنم"
    ).sortedByDescending { it.first.length }

    // Token-level fixes
    private val tokens = listOf(
        "عرز" to "عرض",
        "عدابه" to "ادب",
        "عداب" to "ادب",
        "اتاب" to "ادب",
        "اختر" to "احترام",
        "خدمته" to "خدمت",
        "عزی" to "عزیز",
        "سلا" to "سلام",
        "م" to "م", // keep single letters careful - skip most
        "درود" to "درود",
        "سلام" to "سلام"
    )

    fun fix(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw.trim()
        t = t.replace('\u200c', ' ')
        t = t.replace(Regex("[\\u064B-\\u065F]"), "") // diacritics noise
        t = t.replace(Regex("\\s+"), " ").trim()

        // join broken chars inside words: "س ل ا م" style light pass
        t = joinSpacedLetters(t)

        for ((bad, good) in phrases) {
            if (t.contains(bad)) t = t.replace(bad, good)
        }

        // token pass
        val parts = t.split(' ').toMutableList()
        for (i in parts.indices) {
            val w = parts[i]
            for ((bad, good) in tokens) {
                if (w == bad) {
                    parts[i] = good
                    break
                }
            }
        }
        t = parts.joinToString(" ")

        // second phrase pass after token fixes
        for ((bad, good) in phrases) {
            if (t.contains(bad)) t = t.replace(bad, good)
        }

        t = NumberNormalizer.normalize(PersianPostProcess.fix(t))
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** "س ل ا م" → try "سلام" if no spaces meaningful */
    private fun joinSpacedLetters(s: String): String {
        // collapse sequences of single-char tokens into one word
        val parts = s.split(' ')
        if (parts.size < 3) return s
        val out = mutableListOf<String>()
        var i = 0
        while (i < parts.size) {
            if (parts[i].length == 1 && i + 1 < parts.size && parts[i + 1].length == 1) {
                val buf = StringBuilder()
                while (i < parts.size && parts[i].length == 1) {
                    buf.append(parts[i])
                    i++
                }
                out.add(buf.toString())
            } else {
                out.add(parts[i])
                i++
            }
        }
        return out.joinToString(" ")
    }
}
