package com.persianstt.offline

/**
 * Insert emojis inline next to matching Persian phrases (start/middle/end).
 */
object EmojiHelper {

    // Longer phrases first
    private val rules = listOf(
        "عرض سلام و ادب و احترام" to "🙇",
        "صبح بخیر" to "☀️",
        "عصر بخیر" to "🌆",
        "شب بخیر" to "🌙",
        "خسته نباشید" to "💪",
        "خسته نباشی" to "💪",
        "دوستت دارم" to "❤️",
        "فوق العاده" to "🤩",
        "خداحافظ" to "👋",
        "متشکرم" to "🙏",
        "متشکر" to "🙏",
        "ممنونم" to "🙏",
        "ممنون" to "🙏",
        "مرسی" to "🙏",
        "تشکر" to "🙏",
        "ببخشید" to "🙏",
        "معذرت" to "🙏",
        "خواهش" to "🙏",
        "لطفا" to "🙏",
        "درود" to "👋",
        "سلام" to "👋",
        "عشق" to "❤️",
        "عاشق" to "💕",
        "قلب" to "💕",
        "خنده" to "😂",
        "جوک" to "😂",
        "باحال" to "😎",
        "خوشحال" to "😊",
        "عالیه" to "✨",
        "عالی" to "✨",
        "غمگین" to "😢",
        "ناراحت" to "😔",
        "گریه" to "😢",
        "عصبانی" to "😠",
        "خواب" to "😴",
        "خسته" to "😮‍💨",
        "ناهار" to "🍽️",
        "صبحانه" to "🍳",
        "شام" to "🍽️",
        "غذا" to "🍽️",
        "قهوه" to "☕",
        "چای" to "☕",
        "مسافرت" to "✈️",
        "سفر" to "✈️",
        "ماشین" to "🚗",
        "خانه" to "🏠",
        "خونه" to "🏠",
        "اداره" to "💼",
        "شرکت" to "💼",
        "دانشگاه" to "🎓",
        "مدرسه" to "📚",
        "درس" to "📚",
        "تبریک" to "🎉",
        "مبارک" to "🎉",
        "تولد" to "🎂",
        "موفقیت" to "🌟",
        "موفق" to "🌟",
        "باران" to "🌧️",
        "آفتاب" to "☀️",
        "فوتبال" to "⚽",
        "موسیقی" to "🎵",
        "آهنگ" to "🎵",
        "عکس" to "📷",
        "تلفن" to "📞",
        "واتساپ" to "💬",
        "پیام" to "💬",
        "کمک" to "🆘",
        "پول" to "💰",
        "تومان" to "💰",
        "ساعت" to "⏰",
        "احترام" to "🙇",
        "ادب" to "🙇",
        "بله" to "✅",
        "آره" to "✅",
        "نخیر" to "❌"
    ).sortedByDescending { it.first.length }

    /**
     * Place emoji right after each matched phrase, anywhere in the text.
     * Avoid double-inserting the same emoji next to the same phrase.
     */
    fun enrich(text: String): String {
        if (text.isBlank()) return text
        var t = text
        for ((phrase, emoji) in rules) {
            if (!t.contains(phrase)) continue
            // insert emoji after each occurrence if not already there
            val replacement = "$phrase $emoji"
            if (!t.contains(replacement)) {
                t = t.replace(phrase, replacement)
            }
        }
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }
}
