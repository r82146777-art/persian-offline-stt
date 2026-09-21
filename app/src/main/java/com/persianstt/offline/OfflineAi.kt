package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline pipeline (from design notes):
 * 1) hard ASR phrase maps
 * 2) SymSpell frequency dictionary (50k)
 * 3) light Persian post-process
 * Emoji: FastText-style keyword rules (no neural net needed)
 */
object OfflineAi {
    private const val TAG = "OfflineAi"
    private val ready = AtomicBoolean(false)

    fun ensure(context: Context) {
        if (ready.getAndSet(true)) return
        try {
            SimpleVocab.ensureLoaded(context)
            BrainLexicon.ensureLoaded(context)
            SymSpell.ensureLoaded(context)
        } catch (e: Exception) {
            Log.w(TAG, "ensure", e)
            ready.set(false)
        }
    }

    private val hardFixes: List<Pair<String, String>> = listOf(
        "عرز سلام عدابه احترام" to "عرض سلام و ادب و احترام",
        "عرضه سل م اتاب اختر" to "عرض سلام و ادب و احترام",
        "ارزی سلا م اتاب اختر" to "عرض سلام و ادب و احترام",
        "عرضسلامادباحتر" to "عرض سلام و ادب و احترام",
        "عرض سلام ادب احترام" to "عرض سلام و ادب و احترام",
        "عرضه سل" to "عرض سلام",
        "عرض سل" to "عرض سلام",
        "عرز سلام" to "عرض سلام",
        "عرضسلا" to "عرض سلام",
        "عدابه احترام" to "ادب و احترام",
        "اتاب اختر" to "ادب و احترام",
        "د شتانه عزی" to "دوستان عزیز",
        "دشتانه عزیز" to "دوستان عزیز",
        "خدمته تمام" to "خدمت تمام",
        "خدا حافظ" to "خداحافظ",
        "خسته نباشی" to "خسته نباشید",
        "خواهش میکنم" to "خواهش می‌کنم",
        "خواهش می کنم" to "خواهش می‌کنم",
        "می روم" to "می‌روم",
        "می کنم" to "می‌کنم",
        "می کنید" to "می‌کنید",
        "می شود" to "می‌شود",
        "می خواهم" to "می‌خواهم",
        "می تونم" to "می‌تونم",
        "میکنم" to "می‌کنم",
        "میکنید" to "می‌کنید",
        "میروم" to "می‌روم",
        "میشود" to "می‌شود",
        "هم اکنون" to "هم‌اکنون",
        "هماکنون" to "هم‌اکنون"
    ).sortedByDescending { it.first.length }

    fun correctText(context: Context?, raw: String): String {
        if (raw.isBlank()) return raw
        if (context != null) ensure(context)

        val original = raw.trim().replace(Regex("\\s+"), " ")
        var t = original
        t = t.replace(Regex("^(متن اصلاح[‌ ]*شده[:：]?\\s*|خروجی[:：]?\\s*|نتیجه[:：]?\\s*)"), "")

        var hardHit = false
        for ((bad, good) in hardFixes) {
            if (t.contains(bad)) {
                t = t.replace(bad, good)
                hardHit = true
            }
        }

        val tMi = t.replace(Regex("""\bمی\s+([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی]+)"""), "می‌$1")
        if (tMi != t) t = tMi

        // SymSpell only if still different from clean dictionary words
        try {
            val sym = SymSpell.correctSentence(t)
            // accept sym only if not dramatically shorter (avoid wiping)
            if (sym.isNotBlank() && sym.length >= t.length * 7 / 10) {
                t = sym
            }
        } catch (e: Exception) {
            Log.w(TAG, "symspell", e)
        }

        t = PersianPostProcess.fix(t)
        t = NumberNormalizer.normalize(t)
        t = t.replace(Regex("\\s+"), " ").trim()

        // if nothing useful and we only risk damage — keep original
        if (!hardHit && t == original) return original
        if (t.isBlank()) return original
        return t
    }

    private data class EmojiRule(val keys: List<String>, val emoji: String)

    private val emojiRules = listOf(
        EmojiRule(listOf("عرض سلام و ادب و احترام", "عرض سلام و ادب", "عرض ادب و احترام"), "🙇"),
        EmojiRule(listOf("صبح بخیر", "صبح‌بخیر"), "☀️"),
        EmojiRule(listOf("ظهر بخیر", "ظهر‌بخیر"), "🌤️"),
        EmojiRule(listOf("عصر بخیر", "عصر‌بخیر"), "🌇"),
        EmojiRule(listOf("شب بخیر", "شب‌بخیر"), "🌙"),
        EmojiRule(listOf("خسته نباشید", "خسته نباشی", "خدا قوت", "خداقوت"), "💪"),
        EmojiRule(listOf("دوستت دارم", "عاشقتم", "عاشقانه", "عاشق"), "❤️"),
        EmojiRule(listOf("تولدت مبارک", "تولد", "تولدت"), "🎂"),
        EmojiRule(listOf("تبریک", "مبارک", "پیروزی"), "🎉"),
        EmojiRule(listOf("خنده", "جوک", "بامزه", "خنده‌دار"), "😂"),
        EmojiRule(listOf("ناراحت", "غمگین", "گریه", "اشک"), "😔"),
        EmojiRule(listOf("عصبانی", "خشمگین", "عصبانیت"), "😠"),
        EmojiRule(listOf("ممنونم", "ممنون", "متشکرم", "مرسی", "تشکر", "سپاس"), "🙏"),
        EmojiRule(listOf("خداحافظ", "فعلا", "بدرود"), "👋"),
        EmojiRule(listOf("سلام", "درود", "سلام علیکم"), "👋"),
        EmojiRule(listOf("عالی", "عالیه", "فوق العاده", "فوق‌العاده", "محشر"), "✨"),
        EmojiRule(listOf("خوب", "خوبه", "خوشحال", "شاد"), "😊"),
        EmojiRule(listOf("باران", "بارونی"), "🌧️"),
        EmojiRule(listOf("برف", "برفی"), "❄️"),
        EmojiRule(listOf("آفتاب", "آفتابی", "آسمان"), "☀️"),
        EmojiRule(listOf("قهوه", "کافه"), "☕"),
        EmojiRule(listOf("چای"), "🍵"),
        EmojiRule(listOf("غذا", "ناهار", "شام", "صبحانه"), "🍽️"),
        EmojiRule(listOf("آب"), "💧"),
        EmojiRule(listOf("ماشین", "اتومبیل", "رانندگی"), "🚗"),
        EmojiRule(listOf("هواپیما", "پرواز", "فرودگاه"), "✈️"),
        EmojiRule(listOf("قطار", "مترو"), "🚇"),
        EmojiRule(listOf("خانه", "منزل", "خونه"), "🏠"),
        EmojiRule(listOf("مدرسه", "دانشگاه", "کلاس"), "📚"),
        EmojiRule(listOf("کتاب"), "📖"),
        EmojiRule(listOf("کار", "اداره", "شرکت"), "💼"),
        EmojiRule(listOf("پول", "بانک", "قیمت"), "💰"),
        EmojiRule(listOf("فوتبال", "ورزش", "بازی"), "⚽"),
        EmojiRule(listOf("موسیقی", "آهنگ", "کنسرت"), "🎵"),
        EmojiRule(listOf("فیلم", "سینما", "سریال"), "🎬"),
        EmojiRule(listOf("عکس", "دوربین"), "📷"),
        EmojiRule(listOf("تلفن", "تماس", "موبایل"), "📱"),
        EmojiRule(listOf("کامپیوتر", "رایانه", "لپ‌تاپ"), "💻"),
        EmojiRule(listOf("اینترنت", "آنلاین"), "🌐"),
        EmojiRule(listOf("قلب", "عشق"), "💖"),
        EmojiRule(listOf("گل", "گل‌ها"), "🌸"),
        EmojiRule(listOf("درخت", "جنگل", "طبیعت"), "🌳"),
        EmojiRule(listOf("دریا", "ساحل"), "🌊"),
        EmojiRule(listOf("کوه", "کوهستان"), "⛰️"),
        EmojiRule(listOf("آتش", "آتش‌سوزی"), "🔥"),
        EmojiRule(listOf("ستاره", "درخشان"), "⭐"),
        EmojiRule(listOf("موفقیت", "موفق", "پیروز"), "🏆"),
        EmojiRule(listOf("ایده", "فکر", "نوآوری"), "💡"),
        EmojiRule(listOf("هشدار", "مراقب", "احتیاط"), "⚠️"),
        EmojiRule(listOf("بله", "آره", "باشه"), "✅"),
        EmojiRule(listOf("نه", "خیر"), "❌"),
        EmojiRule(listOf("سوال", "پرسش", "چرا"), "❓"),
        EmojiRule(listOf("زمان", "ساعت", "دقیقه"), "⏰"),
        EmojiRule(listOf("تقویم", "تاریخ", "روز"), "📅"),
        EmojiRule(listOf("مکان", "آدرس", "نقشه"), "📍"),
        EmojiRule(listOf("ایران", "تهران"), "🇮🇷"),
        EmojiRule(listOf("کریسمس", "نوئل"), "🎄"),
        EmojiRule(listOf("عید", "نوروز"), "🎊"),
        EmojiRule(listOf("خواب", "خسته"), "😴"),
        EmojiRule(listOf("بیمار", "بیمارستان", "دکتر"), "🏥"),
        EmojiRule(listOf("دارو", "قرص"), "💊"),
        EmojiRule(listOf("سفر", "مسافرت", "گردش"), "🧳"),
        EmojiRule(listOf("خرید", "بازار", "فروشگاه"), "🛒"),
        EmojiRule(listOf("هدیه", "کادو"), "🎁"),
        EmojiRule(listOf("نامه", "پیام", "ایمیل"), "✉️"),
        EmojiRule(listOf("قفل", "امنیت", "رمز"), "🔒"),
        EmojiRule(listOf("کلید"), "🔑"),
        EmojiRule(listOf("چشم", "نگاه"), "👀"),
        EmojiRule(listOf("دست", "مصافحه"), "🤝"),
        EmojiRule(listOf("انگشت", "اشاره"), "👆"),
        EmojiRule(listOf("خورشید", "ماه", "ستاره"), "🌙"),
        EmojiRule(listOf("رعد", "برق", "طوفان"), "⛈️"),
        EmojiRule(listOf("گلابی", "سیب", "موز", "میوه"), "🍎"),
        EmojiRule(listOf("کیک", "شیرینی", "شکلات"), "🍰"),
        EmojiRule(listOf("پیتزا", "همبرگر", "ساندویچ"), "🍕"),
        EmojiRule(listOf("سگ", "گربه", "حیوان"), "🐾"),
        EmojiRule(listOf("پرنده", "گنجشک"), "🐦"),
        EmojiRule(listOf("ماهی"), "🐟"),
        EmojiRule(listOf("ماشین حساب", "عدد", "ریاضی"), "🔢"),
        EmojiRule(listOf("پرچم"), "🚩"),
        EmojiRule(listOf("انرژی", "برق"), "⚡"),
        EmojiRule(listOf("بازی", "گیم"), "🎮"),
        EmojiRule(listOf("کتابخانه", "مطالعه"), "📚"),
        EmojiRule(listOf("نوشتار", "تایپ", "نوشتن"), "✍️"),
        EmojiRule(listOf("صدا", "صوتی", "میکروفون"), "🎙️"),
        EmojiRule(listOf("گوش دادن", "شنیدن"), "👂")
    )


    fun addEmojis(text: String): String {
        if (text.isBlank()) return "😊"
        var t = text.trim()
        val used = mutableSetOf<String>()
        for (rule in emojiRules.sortedByDescending { r -> r.keys.maxOf { it.length } }) {
            for (k in rule.keys.sortedByDescending { it.length }) {
                if (!t.contains(k)) continue
                if (t.contains(rule.emoji) || rule.emoji in used) continue
                val idx = t.indexOf(k) + k.length
                t = t.substring(0, idx) + " " + rule.emoji + t.substring(idx)
                used.add(rule.emoji)
                break
            }
        }
        if (used.isEmpty()) t = "$t ✨"
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
