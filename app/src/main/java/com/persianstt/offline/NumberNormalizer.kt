package com.persianstt.offline

/**
 * Converts spoken Persian/English number words → digits.
 * «هزار و سیصد و هشتاد و یک» → 1381
 * Collapses scale hallucinations like «یک میلیارد ۱۳۸۱».
 */
object NumberNormalizer {

    private val wordVal = linkedMapOf(
        // ones
        "صفر" to 0L, "یک" to 1L, "یه" to 1L, "دو" to 2L, "سه" to 3L, "چهار" to 4L,
        "پنج" to 5L, "شش" to 6L, "شیش" to 6L, "هفت" to 7L, "هشت" to 8L, "نه" to 9L,
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        // teens
        "ده" to 10L, "یازده" to 11L, "دوازده" to 12L, "سیزده" to 13L, "چهارده" to 14L,
        "پانزده" to 15L, "شانزده" to 16L, "هفده" to 17L, "هجده" to 18L, "نوزده" to 19L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
        "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
        // tens
        "بیست" to 20L, "سی" to 30L, "چهل" to 40L, "پنجاه" to 50L,
        "شصت" to 60L, "هفتاد" to 70L, "هشتاد" to 80L, "نود" to 90L,
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
        // hundreds
        "صد" to 100L, "یکصد" to 100L, "دویست" to 200L, "سیصد" to 300L, "چهارصد" to 400L,
        "پانصد" to 500L, "ششصد" to 600L, "شیشصد" to 600L, "هفتصد" to 700L,
        "هشتصد" to 800L, "نهصد" to 900L, "hundred" to 100L
    )

    private val scales = mapOf(
        "هزار" to 1_000L, "میلیون" to 1_000_000L, "میلیارد" to 1_000_000_000L,
        "بیلیون" to 1_000_000_000L, "تریلیون" to 1_000_000_000_000L,
        "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L
    )

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var t = persianDigitsToLatin(text.trim())
        t = collapseScaleHallucination(t)
        t = convertSpokenNumbers(t)
        t = t.replace(
            Regex("\\b(میلیارد|میلیون|هزار|بیلیون|تریلیون|billion|million|thousand)\\b\\s*", RegexOption.IGNORE_CASE),
            ""
        )
        t = t.replace(Regex("(?<=\\d)\\s*و\\s*(?=\\d)"), "")
        t = t.replace(Regex("(?<=\\d)\\s+(?=\\d)"), "")
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun collapseScaleHallucination(text: String): String {
        var t = text
        t = Regex(
            "(?:^|\\s)(?:[0-9]|یک|دو|سه|چهار|پنج)?\\s*(?:میلیارد|میلیون|هزار|billion|million|thousand)\\s*(?:و\\s*)?([0-9]{3,5})\\b",
            RegexOption.IGNORE_CASE
        ).replace(t) { " " + it.groupValues[1] }
        t = Regex(
            "\\b([0-9]{3,5})\\s*(?:میلیارد|میلیون|هزار)\\b",
            RegexOption.IGNORE_CASE
        ).replace(t) { it.groupValues[1] }
        return t
    }

    private fun convertSpokenNumbers(text: String): String {
        val tokens = text.split(Regex("\\s+|\\s*و\\s*")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return text
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val (value, consumed) = tryParse(tokens, i)
            if (consumed > 0 && value != null) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                out.append(value)
                i += consumed
            } else {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                out.append(tokens[i])
                i++
            }
        }
        return out.toString()
    }

    private fun tryParse(tokens: List<String>, start: Int): Pair<Long?, Int> {
        var i = start
        var total = 0L
        var current = 0L
        var consumed = 0
        var saw = false
        while (i < tokens.size) {
            val w = tokens[i].lowercase()
            when {
                wordVal.containsKey(w) -> {
                    current += wordVal[w]!!
                    saw = true; consumed++; i++
                }
                scales.containsKey(w) -> {
                    val s = scales[w]!!
                    if (current == 0L) current = 1L
                    total += current * s
                    current = 0L
                    saw = true; consumed++; i++
                }
                w.matches(Regex("\\d+")) -> {
                    current += w.toLongOrNull() ?: 0L
                    saw = true; consumed++; i++
                }
                else -> break
            }
        }
        if (!saw) return null to 0
        return (total + current) to consumed
    }

    private fun persianDigitsToLatin(s: String): String {
        val map = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val sb = StringBuilder(s.length)
        for (c in s) {
            val idx = map.indexOf(c)
            sb.append(if (idx >= 0) ('0'.code + idx).toChar() else c)
        }
        return sb.toString()
    }
}
