package com.persianstt.offline

/**
 * Converts spoken number words (Persian + English) into digit sequences
 * and fixes common Vosk hallucinations on years / short numbers
 * (e.g. «یک میلیارد و ۳۸۰۱» → «۱۳۸۱»).
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
        "نهصد" to "900", "هزار" to "1000"
    )

    private val enMap = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
        "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19",
        "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
        "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90",
        "hundred" to "100", "thousand" to "1000"
    )

    private val scaleWords = listOf(
        "میلیارد", "میلیون", "هزار", "بیلیون", "تریلیون",
        "billion", "million", "thousand"
    )

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var result = text.trim()

        result = persianDigitsToLatin(result)
        result = fixScaleHallucination(result)

        val all = (faMap + enMap).entries.sortedByDescending { it.key.length }
        for ((word, digit) in all) {
            result = result.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE), digit)
        }

        result = result.replace(Regex("(?<=\\d)\\s*(و)?\\s*(?=\\d)"), "")
        result = stripOrphanScales(result)
        result = result.replace(Regex("\\s{2,}"), " ").trim()
        return result
    }

    private fun fixScaleHallucination(text: String): String {
        var t = text
        t = t.replace(
            Regex("""یک\\s*میلیارد\\s*و\\s*([0-9]{3,5})""", RegexOption.IGNORE_CASE),
            "1$1"
        )
        t = t.replace(
            Regex("""یک\\s*میلیون\\s*و\\s*([0-9]{3,5})""", RegexOption.IGNORE_CASE),
            "1$1"
        )
        t = t.replace(
            Regex("""یک\\s*هزار\\s*و\\s*([0-9]{3,5})""", RegexOption.IGNORE_CASE),
            "1$1"
        )

        val scalePattern = Regex(
            """(?:^|\\s)(یک|دو|سه|چهار|پنج|شش|هفت|هشت|نه|1|2|3|4|5|6|7|8|9)?\\s*""" +
                """(میلیارد|میلیون|هزار|بیلیون|تریلیون|billion|million|thousand)\\s*(?:و\\s*)?""" +
                """([0-9]{3,5})""",
            RegexOption.IGNORE_CASE
        )

        t = scalePattern.replace(t) { match ->
            val prefix = match.groupValues[1].ifBlank { "1" }
            val digits = match.groupValues[3]
            val prefixDigit = when (prefix.lowercase()) {
                "یک", "1" -> "1"
                "دو", "2" -> "2"
                "سه", "3" -> "3"
                "چهار", "4" -> "4"
                else -> if (prefix.matches(Regex("\\d"))) prefix else "1"
            }
            if (digits.startsWith("13") || digits.startsWith("14") || digits.length == 4) {
                digits
            } else {
                prefixDigit + digits
            }
        }
        return t
    }

    private fun stripOrphanScales(text: String): String {
        var t = text
        for (scale in scaleWords) {
            t = t.replace(
                Regex("""\\b$scale\\b\\s*(?=\\d{1,5}\\b)""", RegexOption.IGNORE_CASE),
                ""
            )
            t = t.replace(
                Regex("""(?<=\\b\\d{1,5})\\s*\\b$scale\\b""", RegexOption.IGNORE_CASE),
                ""
            )
        }
        return t
    }

    private fun persianDigitsToLatin(s: String): String {
        val map = mapOf(
            '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
            '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9'
        )
        return s.map { map[it] ?: it }.joinToString("")
    }
}
