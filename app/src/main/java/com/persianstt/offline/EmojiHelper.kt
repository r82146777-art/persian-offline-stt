package com.persianstt.offline

/**
 * Simple keyword → emoji enrichment for Persian text.
 */
object EmojiHelper {

    private val rules = listOf(
        listOf("سلام", "درود", "صبح بخیر", "عصر بخیر") to "👋",
        listOf("خداحافظ", "بای", "فعلا") to "👋",
        listOf("ممنون", "مرسی", "متشکر", "تشکر") to "🙏",
        listOf("خسته نباشید", "خسته نباشی") to "💪",
        listOf("عشق", "دوستت دارم", "عاشق") to "❤️",
        listOf("قلب", "عاشقانه") to "💕",
        listOf("خنده", "خندید", "جوک", "باحال") to "😂",
        listOf("خوشحالم", "خوشحال", "عالی", "فوق العاده", "عالیه") to "😊",
        listOf("غمگین", "ناراحت", "گریه") to "😢",
        listOf("عصبانی", "خشم") to "😠",
        listOf("خواب", "شب بخیر", "خسته") to "😴",
        listOf("غذا", "ناهار", "شام", "صبحانه") to "🍽️",
        listOf("چای", "قهوه") to "☕",
        listOf("سفر", "مسافرت") to "✈️",
        listOf("ماشین", "رانندگی") to "🚗",
        listOf("خانه", "خونه") to "🏠",
        listOf("کار", "اداره", "شرکت") to "💼",
        listOf("درس", "دانشگاه", "مدرسه") to "📚",
        listOf("تبریک", "مبارک") to "🎉",
        listOf("تولد", "سالگرد") to "🎂",
        listOf("موفق", "موفقیت") to "🌟",
        listOf("باران", "بارونی") to "🌧️",
        listOf("آفتاب", "هوای خوب") to "☀️",
        listOf("فوتبال", "گل") to "⚽",
        listOf("موسیقی", "آهنگ") to "🎵",
        listOf("عکس", "عکاسی") to "📷",
        listOf("تلفن", "زنگ") to "📞",
        listOf("پیام", "واتساپ") to "💬",
        listOf("بله", "آره") to "✅",
        listOf("نه", "نخیر") to "❌",
        listOf("لطفا", "خواهش") to "🙏",
        listOf("ببخشید", "معذرت") to "🙏",
        listOf("کمک") to "🆘",
        listOf("پول", "تومان", "قیمت") to "💰",
        listOf("وقت", "ساعت") to "⏰",
        listOf("ادب", "احترام") to "🙇"
    )

    fun enrich(text: String): String {
        if (text.isBlank()) return text
        val found = linkedSetOf<String>()
        val lower = text // Persian has no case
        for ((keys, emoji) in rules) {
            if (keys.any { lower.contains(it) }) found.add(emoji)
        }
        if (found.isEmpty()) return text.trim() + " ✨"
        return text.trim() + " " + found.joinToString(" ")
    }
}
