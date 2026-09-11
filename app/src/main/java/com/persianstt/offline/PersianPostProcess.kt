package com.persianstt.offline

/**
 * Light, SAFE post-processing only.
 * DO NOT collapse single-letter tokens like «و» — that destroys real words.
 */
object PersianPostProcess {

    private val phraseFixes = listOf(
        Regex("""مارض""") to "با عرض",
        Regex("""باعرض""") to "با عرض",
        Regex("""آفلینه""") to "آفلاین",
        Regex("""می\s*کنید""") to "می‌کنید",
        Regex("""میکنید""") to "می‌کنید",
        Regex("""هم\s*اکنون""") to "هم‌اکنون",
        Regex("""هماکنون""") to "هم‌اکنون",
    )

    fun fix(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw.trim()
        // only strip trailing single junk letter (common CTC artifact at end)
        t = t.replace(Regex("""[\s،.]*[آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءئ]$"""), "")
        for ((re, rep) in phraseFixes) {
            t = re.replace(t, rep)
        }
        // normalize whitespace only — never glue real words
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        return t
    }
}
