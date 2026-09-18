package com.persianstt.offline

/**
 * Stage 3 — Post-processing after ASR (regex cleanup + common ASR fixes).
 */
object PersianPostProcess {

    private val phraseFixes = listOf(
        Regex("عرضه\\s*سل") to "عرض سلام",
        Regex("عرز\\s*سلام") to "عرض سلام",
        Regex("عدابه") to "ادب",
        Regex("د\\s*شتانه") to "دوستان",

        Regex("""مارض""") to "با عرض",
        Regex("""باعرض""") to "با عرض",
        Regex("""عرضه\s*سل""") to "عرض سلام",
        Regex("""عرز\s*سلام""") to "عرض سلام",
        Regex("""عدابه""") to "ادب",
        Regex("""اتاب\s*اختر""") to "ادب و احترام",
        Regex("""د\s*شتانه""") to "دوستان",
        Regex("""آفلینه""") to "آفلاین",
        Regex("""می\s+روم""") to "می‌روم",
        Regex("""می\s+کنم""") to "می‌کنم",
        Regex("""می\s+کنید""") to "می‌کنید",
        Regex("""می\s+شود""") to "می‌شود",
        Regex("""می\s+خواهم""") to "می‌خواهم",
        Regex("""میکنم""") to "می‌کنم",
        Regex("""میکنید""") to "می‌کنید",
        Regex("""میروم""") to "می‌روم",
        Regex("""هم\s*اکنون""") to "هم‌اکنون",
        Regex("""هماکنون""") to "هم‌اکنون",
        Regex("""در\s*حال""") to "در حال",
        Regex("""خواهش\s*میکنم""") to "خواهش می‌کنم",
        Regex("""خدا\s*حافظ""") to "خداحافظ",
        Regex("""صبح\s*بخیر""") to "صبح بخیر",
        Regex("""شب\s*بخیر""") to "شب بخیر"
    )

    fun fix(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw.trim()
            .replace('\u200c', '\u200c') // keep ZWNJ
            .replace(Regex("""[«»""]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // mi + space + verb → mi- verb (half space)
        t = t.replace(Regex("""\bمی\s+([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءئ]+)"""), "می‌$1")

        for ((re, rep) in phraseFixes) {
            t = re.replace(t, rep)
        }

        // collapse multi spaces again
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        // drop lone trailing junk letter only if very short artifact
        if (t.length > 3) {
            t = t.replace(Regex("""\s+[آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءئ]$"""), "")
        }
        return t.trim()
    }
}
