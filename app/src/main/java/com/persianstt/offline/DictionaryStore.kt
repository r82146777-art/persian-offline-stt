package com.persianstt.offline

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * User dictionary — boost layer for hybrid STT (not a hard grammar cage).
 */
object DictionaryStore {
    private const val PREFS = "vosk_dict"
    private const val KEY = "words"

    private val defaults = listOf(
        "سلام", "درود", "خداحافظ", "ممنون", "متشکرم", "بله", "نه", "باشه",
        "لطفا", "ببخشید", "خسته", "نباشید", "صبح", "بخیر", "شب",
        "عرض", "ادب", "احترام", "دوستان", "عزیز", "امروز", "فردا", "دیروز",
        "خانه", "کار", "خوب", "عالی", "تایپ", "صوتی", "آفلاین", "فارسی"
    )

    fun allWords(context: Context): List<String> {
        val set = LinkedHashSet<String>()
        defaults.forEach { set.add(it) }
        userWords(context).forEach { set.add(it) }
        return set.toList()
    }

    fun userWords(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet())?.map { it.trim() }?.filter { it.isNotEmpty() }?.sorted()
            ?: emptyList()
    }

    fun userCount(context: Context): Int = userWords(context).size

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

    /** Delete only user-imported dictionary (keeps built-in defaults). */
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
                        if (t.isNotEmpty() && t != "[unk]" && !t.startsWith("#")) words.add(t)
                    }
                }
            }
        }
        addWords(context, words)
        return words.size
    }

    /** One word per line — same format as import. */
    fun exportText(context: Context): String {
        return userWords(context).joinToString("\n")
    }

    fun writeExportFile(context: Context): File {
        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        val f = File(dir, "hamdel_dict_${userCount(context)}.txt")
        FileOutputStream(f).use { it.write(exportText(context).toByteArray(Charsets.UTF_8)) }
        return f
    }

    fun shareExport(context: Context) {
        val f = writeExportFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            f
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "دیکت فارسی")
            putExtra(Intent.EXTRA_TEXT, "فایل دیکت (${userCount(context)} واژه)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "ارسال دیکت"))
    }

    fun grammarJson(context: Context): String {
        val arr = JSONArray()
        allWords(context).forEach { arr.put(it) }
        arr.put("[unk]")
        return arr.toString()
    }
}
