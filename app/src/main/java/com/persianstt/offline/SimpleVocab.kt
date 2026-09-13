package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.io.File

/**
 * واژگان گسترده (~۵۰هزار کلمه + واریانت‌های رایج ASR).
 * فایل: assets/hamdel_vocab_map.txt
 */
object SimpleVocab {

    private const val TAG = "SimpleVocab"
    private const val ASSET = "hamdel_vocab_map.txt"
    private const val FILE = "hamdel_vocab_map.txt"

    @Volatile private var mapInternal: Map<String, String> = emptyMap()
    @Volatile private var phrasesInternal: List<String> = emptyList()
    @Volatile private var loaded = false

    /** hand-tuned high priority (always on, even before file load) */
    private val coreRules: List<Pair<List<String>, String>> = listOf(
        listOf("سلام", "سلا", "سل", "س لام", "سلا م") to "سلام",
        listOf("عرضه سل", "عرضه سلام", "عرض سل", "عرز سلام", "عرض سلا", "عرضسلا") to "عرض سلام",
        listOf(
            "عرض سلام و ادب و احترام",
            "عرز سلام عدابه احترام",
            "عرضسلامادباحتر",
            "ارزی سلا م اتاب اختر"
        ) to "عرض سلام و ادب و احترام",
        listOf("عدابه احترام", "اتاب اختر", "عداب احترام") to "ادب و احترام",
        listOf("د شتانه عزی", "دشتانه عزیز") to "دوستان عزیز",
        listOf("خدمته تمام د شتانه عزی") to "خدمت تمام دوستان عزیز",
        listOf("ممنون", "ممنونم", "مرسی", "متشکرم") to "ممنون",
        listOf("خسته نباشید", "خسته نباشی") to "خسته نباشید",
        listOf("صبح بخیر") to "صبح بخیر",
        listOf("شب بخیر") to "شب بخیر",
        listOf("خداحافظ", "خدا حافظ") to "خداحافظ",
        listOf("بله", "آره") to "بله",
        listOf("نه", "نخیر") to "نه",
        listOf("باشه", "چشم") to "باشه",
        listOf("لطفا", "لطفاً") to "لطفا",
        listOf("ببخشید") to "ببخشید"
    )

    val map: Map<String, String>
        get() = mapInternal.ifEmpty { coreMap() }

    val correctPhrases: List<String>
        get() = phrasesInternal.ifEmpty { coreRules.map { it.second }.distinct().sortedByDescending { it.length } }

    private fun coreMap(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((wrongs, correct) in coreRules) {
            m[correct] = correct
            m[correct.replace(" ", "")] = correct
            for (w in wrongs) {
                m[w] = correct
                m[w.replace(" ", "")] = correct
            }
        }
        return m
    }

    fun ensureLoaded(context: Context) {
        if (loaded && mapInternal.isNotEmpty()) return
        synchronized(this) {
            if (loaded && mapInternal.isNotEmpty()) return
            val m = LinkedHashMap<String, String>(80_000)
            // core first
            m.putAll(coreMap())
            try {
                val f = File(context.applicationContext.filesDir, FILE)
                if (!f.exists() || f.length() < 100_000) {
                    context.applicationContext.assets.open(ASSET).use { inp ->
                        f.outputStream().use { out -> inp.copyTo(out) }
                    }
                }
                val phraseSet = LinkedHashSet<String>()
                f.bufferedReader().useLines { lines ->
                    lines.forEach { line0 ->
                        val line = line0.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        if (line.contains("=")) {
                            val parts = line.split("=", limit = 2)
                            val wrong = parts[0].trim()
                            val correct = parts[1].trim()
                            if (wrong.isNotEmpty() && correct.isNotEmpty()) {
                                m.putIfAbsent(wrong, correct)
                                m.putIfAbsent(wrong.replace(" ", ""), correct)
                                m.putIfAbsent(correct, correct)
                                phraseSet.add(correct)
                            }
                        } else {
                            m.putIfAbsent(line, line)
                            m.putIfAbsent(line.replace(" ", ""), line)
                            phraseSet.add(line)
                        }
                    }
                }
                phrasesInternal = phraseSet.sortedByDescending { it.length }
                mapInternal = m
                Log.i(TAG, "vocab loaded entries=${m.size} phrases=${phrasesInternal.size}")
            } catch (e: Exception) {
                Log.e(TAG, "vocab load fail", e)
                mapInternal = coreMap()
                phrasesInternal = coreRules.map { it.second }.distinct().sortedByDescending { it.length }
            } finally {
                loaded = true
            }
        }
    }

    fun normalizeKey(s: String): String =
        s.trim()
            .replace('\u200c', ' ')
            .replace(Regex("[\\u064B-\\u065F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
