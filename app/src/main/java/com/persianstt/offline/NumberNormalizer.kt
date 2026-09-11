package com.persianstt.offline

/**
 * Converts spoken number words to digits and fixes Vosk scale hallucinations
 * e.g. «یک میلیارد و ۳۸۰۱» → «۱۳۸۱»
 */
object NumberNormalizer {

    private val faMap = mapOf(
        "صفر" to "0", "یک" to "1", "دو" to "2", "سه" to "3", "چهار" to "4",
        "پنج" to "5", "شش" to "6", "هفت" to "7", "هشت" to "8", "نه" to "9",
        "ده" to "10", "یازده" to "11", "دوازده" to "12", "سیزده" to "13",
        "چهارده" to "14", "پانزده" to "15", "شانزده" to "16", "هفده" to "17",
        "هجده" to "18", "نوزده" to "19",
        "بیست" to "20", "سی" to "30", "چهل" to "40", "پنجاه" to "50",
        "شصت" to "60", "هفتاد" to "70", "هشتاد" to "80", "نود" to "90",
        "صد" to "100", "دویست" to "200", "سیصد" to "300", "چهارصد" to "400",
        "پانصد" to "500", "ششصد" to "600", "هفتصد" to "700", "هشتصد" to "800",
        "نهصد" to "900"
    )

    private val enMap = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
        "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19",
        "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
        "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90",
        "hundred" to "100"
    )

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var t = persianDigitsToLatin(text.trim())
        t = fixScaleHallucination(t)
        val all = (faMap + enMap).entries.sortedByDescending { it.key.length }
        for ((word, digit) in all) {
            t = t.replace(Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE), digit)
        }
        t = t.replace(Regex("(?<=\\d)\\s*و?\\s*(?=\\d)"), "")
        t = t.replace(
            Regex("\\b(میلیارد|میلیون|هزار|بیلیون|تریلیون|billion|million|thousand)\\b\\s*", RegexOption.IGNORE_CASE),
            ""
        )
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun fixScaleHallucination(text: String): String {
        var t = text
        // یک میلیارد و 3801
        t = Regex(
            "(?:یک|1)\\s*(?:میلیارد|میلیون|هزار|billion|million|thousand)\\s*(?:و\\s*)?([0-9]{3,5})",
            RegexOption.IGNORE_CASE
        ).replace(t) { m ->
            val digits = m.groupValues[1]
            if (digits.length == 4 || digits.startsWith("13") || digits.startsWith("14")) digits
            else "1" + digits
        }
        // prefix + scale + digits
        t = Regex(
            "(?:^|\\s)([0-9]|یک|دو|سه|چهار|پنج)?\\s*(میلیارد|میلیون|هزار|billion|million|thousand)\\s*(?:و\\s*)?([0-9]{3,5})",
            RegexOption.IGNORE_CASE
        ).replace(t) { m ->
            val digits = m.groupValues[3]
            if (digits.length in 3..5) " " + digits else m.value
        }
        // scale + digits alone
        t = Regex(
            "\\b(?:میلیارد|میلیون|هزار)\\s*(?:و\\s*)?([0-9]{3,5})\\b",
            RegexOption.IGNORE_CASE
        ).replace(t) { m -> m.groupValues[1] }
        return t
    }

    private fun persianDigitsToLatin(s: String): String {
        val map = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val sb = StringBuilder(s.length)
        for (c in s) {
            val i = map.indexOf(c)
            sb.append(if (i >= 0) ('0'.code + i).toChar() else c)
        }
        return sb.toString()
    }
}
