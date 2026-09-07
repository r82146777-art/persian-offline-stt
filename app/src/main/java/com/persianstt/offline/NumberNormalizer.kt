package com.persianstt.offline

/**
 * Converts spoken number words (Persian + English) into digit sequences
 * and removes spaces between consecutive digits.
 * Example: «یک دو سه» or «one two three» → 123
 *          «صد و بیست» → 120 (best-effort)
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

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var result = text

        // Replace known number words (longer first to avoid partial matches)
        val all = (faMap + enMap).entries.sortedByDescending { it.key.length }
        for ((word, digit) in all) {
            result = result.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE), digit)
        }

        // Remove spaces / "و" between consecutive pure digits
        result = result.replace(Regex("(?<=\\d)\\s*(و)?\\s*(?=\\d)"), "")

        // Clean extra spaces
        result = result.replace(Regex("\\s{2,}"), " ").trim()
        return result
    }
}
