package com.persianstt.offline

import android.content.Context
import java.io.File

/**
 * User dictionary for offline correction.
 * File: filesDir/user_dict.txt
 * Formats (one per line):
 *   کلمه_درست
 *   اشتباه = درست
 *   اشتباه -> درست
 * Blank lines and # comments ignored.
 */
object DictCorrection {

    private const val FILE = "user_dict.txt"
    @Volatile private var phrases: List<String> = emptyList()
    @Volatile private var maps: List<Pair<String, String>> = emptyList()
    @Volatile private var loadedFor: String? = null

    fun dictFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE)

    fun reload(context: Context) {
        val f = dictFile(context)
        if (!f.exists()) {
            // seed useful defaults once
            f.writeText(
                """
                # هر خط یک کلمه/عبارت درست — یا اشتباه = درست
                # مثال:
                # ارزی سلا م = عرض سلام
                # اتاب اختر = ادب و احترام
                عرض سلام و ادب و احترام
                سلام
                درود
                خسته نباشید
                ممنون
                لطفا
                """.trimIndent() + "\n"
            )
        }
        val phraseList = mutableListOf<String>()
        val mapList = mutableListOf<Pair<String, String>>()
        f.readLines().forEach { line0 ->
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val sep = when {
                line.contains("=") -> "="
                line.contains("->") -> "->"
                else -> null
            }
            if (sep != null) {
                val parts = line.split(sep, limit = 2)
                if (parts.size == 2) {
                    val a = parts[0].trim()
                    val b = parts[1].trim()
                    if (a.isNotEmpty() && b.isNotEmpty()) mapList.add(a to b)
                }
            } else {
                phraseList.add(line)
            }
        }
        // longer first for replace
        maps = mapList.sortedByDescending { it.first.length }
        phrases = phraseList.sortedByDescending { it.length }
        loadedFor = f.absolutePath
    }

    fun ensureLoaded(context: Context) {
        val path = dictFile(context).absolutePath
        if (loadedFor != path || phrases.isEmpty() && maps.isEmpty()) {
            reload(context)
        }
    }

    fun apply(context: Context, text: String): String {
        if (text.isBlank()) return text
        ensureLoaded(context)
        var t = text

        // 1) explicit wrong -> right maps
        for ((wrong, right) in maps) {
            if (wrong.isNotEmpty() && t.contains(wrong)) {
                t = t.replace(wrong, right)
            }
        }

        // 2) if whole (or near) text is a broken version of a dictionary phrase, replace
        val compact = t.replace(" ", "").replace("\u200c", "")
        for (phrase in phrases) {
            val pCompact = phrase.replace(" ", "").replace("\u200c", "")
            if (pCompact.isEmpty()) continue
            // exact compact match
            if (compact == pCompact) return phrase
            // high overlap of characters (simple recovery for spaced-out letters)
            if (compact.length in (pCompact.length - 2)..(pCompact.length + 4)) {
                val ratio = similarity(compact, pCompact)
                if (ratio >= 0.72f) return phrase
            }
        }

        // 3) replace substrings that match phrase compact form
        for (phrase in phrases) {
            val pCompact = phrase.replace(" ", "").replace("\u200c", "")
            if (pCompact.length < 4) continue
            if (compact.contains(pCompact) && !t.contains(phrase)) {
                // try to find spaced form in t and replace with phrase
                val pattern = pCompact.toCharArray().joinToString("\\s*") { Regex.escape(it.toString()) }
                try {
                    t = t.replace(Regex(pattern), phrase)
                } catch (_: Exception) {}
            }
        }
        return t
    }

    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val longer = if (a.length >= b.length) a else b
        val shorter = if (a.length >= b.length) b else a
        var matches = 0
        var j = 0
        for (i in shorter.indices) {
            val c = shorter[i]
            while (j < longer.length && longer[j] != c) j++
            if (j < longer.length && longer[j] == c) {
                matches++
                j++
            }
        }
        return matches.toFloat() / longer.length
    }

    fun importFromUri(context: Context, uri: android.net.Uri): Int {
        val resolver = context.applicationContext.contentResolver
        val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("خواندن فایل ممکن نشد")
        val f = dictFile(context)
        // append imported lines
        if (f.exists()) f.appendText("\n")
        f.appendText(text)
        if (!text.endsWith("\n")) f.appendText("\n")
        reload(context)
        return text.lines().count { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
    }

    fun appendEntry(context: Context, line: String) {
        val f = dictFile(context)
        if (!f.exists()) reload(context)
        f.appendText(line.trim() + "\n")
        reload(context)
    }
}
