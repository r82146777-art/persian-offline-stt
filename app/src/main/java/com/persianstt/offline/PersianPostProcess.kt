package com.persianstt.offline

/**
 * Fixes common CTC artifacts on Persian text:
 * - mid-word spaces: «تکن ل ژی» → «تکنولوژی»
 * - split short fragments
 * - frequent phonetic confusions
 */
object PersianPostProcess {

    // frequent full phrases / words the app users say
    private val lexicon = listOf(
        "باعرض", "با عرض", "سلام", "ادب", "احترام", "دوستان", "عزیز",
        "این", "متنی", "که", "هم‌اکنون", "هماکنون", "هم اکنون", "مشاهده",
        "می‌کنید", "میکنید", "توسط", "تایپ", "صوتی", "آفلاین", "گروه",
        "تکنولوژی", "همدل", "نوشته", "شده", "است", "کنید", "بفرمایید",
        "لطفا", "خواهش", "ممنون", "خداحافظ", "صبح", "بخیر", "عصر",
        "شب", "خوب", "بد", "بله", "خیر", "نه", "آره", "چطور", "هستید",
        "هستم", "امروز", "فردا", "دیروز", "ساعت", "دقیقه", "شماره",
        "تلفن", "پیام", "واتساپ", "تلگرام", "ایمیل", "آدرس", "کد",
        "ملی", "پستی", "قیمت", "تومان", "هزار", "میلیون", "میلیارد"
    ).sortedByDescending { it.length }

    // fragments that should be glued (order matters: longer first)
    private val glueFixes = listOf(
        Regex("""ت\s*ک\s*ن\s*ل\s*و?\s*ژ\s*ی""") to "تکنولوژی",
        Regex("""ت\s*ک\s*ن\s*و\s*ل\s*و\s*ژ\s*ی""") to "تکنولوژی",
        Regex("""ص\s*و?\s*ت\s*ی""") to "صوتی",
        Regex("""آ\s*ف\s*ل\s*ا?\s*ی\s*ن""") to "آفلاین",
        Regex("""آفلینه""") to "آفلاین",
        Regex("""گ\s*ر\s*و?\s*ه""") to "گروه",
        Regex("""ن\s*و?\s*ش\s*ت\s*ه""") to "نوشته",
        Regex("""د\s*و?\s*س\s*ت\s*ا?\s*ن""") to "دوستان",
        Regex("""ه\s*م\s*ا?\s*ک\s*ن\s*و?\s*ن""") to "هم‌اکنون",
        Regex("""ت\s*و?\s*س\s*ط""") to "توسط",
        Regex("""م\s*ش\s*ا?\s*ه\s*د\s*ه""") to "مشاهده",
        Regex("""ا\s*ح\s*ت\s*ر\s*ا?\s*م""") to "احترام",
        Regex("""ع\s*ز\s*ی\s*ز""") to "عزیز",
        Regex("""م\s*ی\s*ک\s*ن\s*ی\s*د""") to "می‌کنید",
        Regex("""م\s*ی\s*‌?\s*ک\s*ن\s*ی\s*د""") to "می‌کنید",
        Regex("""ب\s*ا\s*ع\s*ر\s*ض""") to "با عرض",
        Regex("""مارض""") to "با عرض",
        Regex("""با\s*عرض""") to "با عرض",
        Regex("""ت\s*ا?\s*ی\s*پ""") to "تایپ",
        Regex("""ه\s*م\s*د\s*ل""") to "همدل",
        Regex("""ش\s*د\s*ه""") to "شده",
        Regex("""ا\s*س\s*ت""") to "است",
    )

    fun fix(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw.trim()
        // remove trailing junk single letters often added by CTC
        t = t.replace(Regex("""[\s،.]*[آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءئ]{1}[\s.]*$"""), "")
        t = t.replace(Regex("""می\s*کنید"""), "می‌کنید")
        t = t.replace(Regex("""میکنید"""), "می‌کنید")
        // apply glue patterns
        for ((re, rep) in glueFixes) {
            t = re.replace(t, rep)
        }
        // collapse spaces between single Persian letters: «د س ت» → try join
        t = joinLetterFragments(t)
        // normalize multiple spaces
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        // light lexicon pass: if a spaced version of a lexicon word appears, glue it
        for (w in lexicon) {
            if (w.length < 3) continue
            val spaced = w.toCharArray().joinToString("""\s*""")
            t = Regex(spaced).replace(t, w)
        }
        return t.replace(Regex("""\s{2,}"""), " ").trim()
    }

    /** Join runs of 1–2 char tokens that look like a broken word */
    private fun joinLetterFragments(text: String): String {
        val parts = text.split(Regex("""\s+"""))
        if (parts.size < 2) return text
        val out = ArrayList<String>()
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            // collect runs of short Persian fragments (CTC often splits words)
            if (p.length <= 3 && isPersianWord(p)) {
                val buf = StringBuilder(p)
                var j = i + 1
                while (j < parts.size && parts[j].length <= 3 && isPersianWord(parts[j]) && buf.length < 16) {
                    buf.append(parts[j])
                    j++
                }
                if (j - i >= 2) {
                    out.add(buf.toString())
                    i = j
                    continue
                }
            }
            out.add(p)
            i++
        }
        return out.joinToString(" ")
    }

    private fun isPersianWord(s: String): Boolean {
        if (s.isEmpty()) return false
        for (c in s) {
            if (c !in 'آ'..'ی' && c != '‌' && c != 'ة' && c != 'ؤ' && c != 'إ' && c != 'أ') return false
        }
        return true
    }
}
