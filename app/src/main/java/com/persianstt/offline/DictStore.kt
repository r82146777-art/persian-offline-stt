package com.persianstt.offline

import android.content.Context
import org.json.JSONArray

object DictStore {
    private const val PREFS = "hamdel_dict"
    private const val KEY_WORDS = "words"

    private val DEFAULTS = listOf(
        "سلام", "خداحافظ", "بله", "خیر", "نه", "ممنون", "لطفا", "خواهش",
        "من", "تو", "او", "ما", "شما", "آنها", "این", "آن",
        "است", "هست", "بود", "شد", "می‌شود", "می‌کنم", "می‌کنی", "می‌کند",
        "امروز", "فردا", "دیروز", "وقت", "ساعت", "روز", "شب",
        "خانه", "کار", "مدرسه", "دانشگاه", "اداره", "فروشگاه",
        "آب", "نان", "غذا", "چای", "قهوه", "صبحانه", "ناهار", "شام",
        "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه", "ده",
        "تایپ", "صوتی", "پیام", "تلفن", "موبایل", "برنامه", "کیبورد",
        "همدل", "آکادمی", "فناوری", "گروه",
        "خوب", "بد", "عالی", "ممنونم", "ببخشید", "چطور", "کجا", "کی", "چه",
        "برو", "بیا", "بده", "بگیر", "بخوان", "بنویس", "بفرست", "باز", "بسته",
        "[unk]"
    )

    fun getWords(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_WORDS, null)
        val set = linkedSetOf<String>()
        if (raw.isNullOrBlank()) {
            set.addAll(DEFAULTS)
        } else {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val w = arr.optString(i).trim()
                    if (w.isNotEmpty()) set.add(w)
                }
            } catch (_: Exception) {
                set.addAll(DEFAULTS)
            }
            set.addAll(DEFAULTS)
        }
        if (!set.contains("[unk]")) set.add("[unk]")
        return set.toMutableList()
    }

    fun saveWords(context: Context, words: Collection<String>) {
        val set = linkedSetOf<String>()
        words.map { it.trim() }.filter { it.isNotEmpty() }.forEach { set.add(it) }
        if (!set.contains("[unk]")) set.add("[unk]")
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WORDS, arr.toString()).apply()
    }

    fun addWord(context: Context, word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val list = getWords(context)
        if (!list.contains(w)) {
            list.add(w)
            saveWords(context, list)
        }
    }

    fun addWords(context: Context, words: Collection<String>) {
        val list = getWords(context)
        var changed = false
        for (w in words) {
            val t = w.trim()
            if (t.isNotEmpty() && !list.contains(t)) {
                list.add(t)
                changed = true
            }
        }
        if (changed) saveWords(context, list)
    }

    fun removeWord(context: Context, word: String) {
        val list = getWords(context)
        list.remove(word)
        saveWords(context, list)
    }

    fun toGrammarJson(context: Context): String {
        val arr = JSONArray()
        getWords(context).forEach { arr.put(it) }
        return arr.toString()
    }
}
