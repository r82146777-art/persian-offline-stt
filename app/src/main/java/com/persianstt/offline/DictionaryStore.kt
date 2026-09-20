package com.persianstt.offline

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * User dictionary for Vosk Grammar Adaptation.
 * Stored in SharedPreferences as a unique word set.
 */
object DictionaryStore {
    private const val PREFS = "vosk_dict"
    private const val KEY = "words"

    private val defaults = listOf(
        "سلام", "درود", "خداحافظ", "ممنون", "متشکرم", "بله", "نه", "باشه",
        "لطفا", "ببخشید", "خسته", "نباشید", "صبح", "بخیر", "شب", "بخیر",
        "عرض", "ادب", "احترام", "دوستان", "عزیز", "امروز", "فردا", "دیروز",
        "خانه", "کار", "خوب", "عالی", "تایپ", "صوتی", "آفلاین", "فارسی"
    )

    fun allWords(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY, null)
        val set = LinkedHashSet<String>()
        defaults.forEach { set.add(it) }
        raw?.forEach { w ->
            val t = w.trim()
            if (t.isNotEmpty()) set.add(t)
        }
        return set.toList()
    }

    fun addWord(context: Context, word: String) {
        val t = word.trim()
        if (t.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(t)
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun addWords(context: Context, words: Collection<String>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        words.forEach { w ->
            val t = w.trim()
            if (t.isNotEmpty()) set.add(t)
        }
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun clearUser(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun importFromUri(context: Context, uri: Uri): Int {
        val words = ArrayList<String>()
        context.contentResolver.openInputStream(uri)?.use { inp ->
            BufferedReader(InputStreamReader(inp, Charsets.UTF_8)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    line!!.split(Regex("[,;\\s]+")).forEach { part ->
                        val t = part.trim()
                        if (t.isNotEmpty() && t != "[unk]") words.add(t)
                    }
                }
            }
        }
        addWords(context, words)
        return words.size
    }

    /** JSON grammar for Vosk Recognizer third argument. Always includes [unk]. */
    fun grammarJson(context: Context): String {
        val arr = JSONArray()
        allWords(context).forEach { arr.put(it) }
        arr.put("[unk]")
        return arr.toString()
    }
}
