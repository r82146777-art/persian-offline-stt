package com.persianstt.offline

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Core rules always available. Full ~50k map loads in background (never on UI / prepare).
 */
object SimpleVocab {

    private const val TAG = "SimpleVocab"
    private const val ASSET = "hamdel_vocab_map.txt"
    private const val FILE = "hamdel_vocab_map.txt"

    @Volatile private var mapInternal: Map<String, String> = emptyMap()
    @Volatile private var phrasesInternal: List<String> = emptyList()
    private val loading = AtomicBoolean(false)
    private val fullLoaded = AtomicBoolean(false)

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
        get() = if (mapInternal.isNotEmpty()) mapInternal else coreMap()

    val correctPhrases: List<String>
        get() = phrasesInternal.ifEmpty {
            coreRules.map { it.second }.distinct().sortedByDescending { it.length }
        }

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

    /** Non-blocking: core ready now; full file in background. */
    fun ensureLoaded(context: Context) {
        if (mapInternal.isEmpty()) {
            mapInternal = coreMap()
            phrasesInternal = coreRules.map { it.second }.distinct().sortedByDescending { it.length }
        }
        if (fullLoaded.get() || !loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "vocab-load", isDaemon = true) {
            try {
                loadFull(app)
                fullLoaded.set(true)
            } catch (e: Throwable) {
                Log.e(TAG, "full vocab skip", e)
            } finally {
                loading.set(false)
            }
        }
    }

    private fun loadFull(context: Context) {
        val f = File(context.filesDir, FILE)
        if (!f.exists() || f.length() < 100_000) {
            try {
                context.assets.open(ASSET).use { inp ->
                    f.outputStream().use { out -> inp.copyTo(out) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "asset copy fail", e)
                return
            }
        }
        val m = LinkedHashMap<String, String>(64_000)
        m.putAll(coreMap())
        val phraseSet = LinkedHashSet<String>()
        // Stream line-by-line — avoid loading whole file as one string
        BufferedReader(InputStreamReader(f.inputStream(), Charsets.UTF_8), 32 * 1024).use { br ->
            var line: String?
            var n = 0
            while (br.readLine().also { line = it } != null) {
                val s = line!!.trim()
                if (s.isEmpty() || s.startsWith("#")) continue
                if (s.contains("=")) {
                    val parts = s.split("=", limit = 2)
                    val wrong = parts[0].trim()
                    val correct = parts.getOrNull(1)?.trim().orEmpty()
                    if (wrong.isNotEmpty() && correct.isNotEmpty()) {
                        m.putIfAbsent(wrong, correct)
                        m.putIfAbsent(wrong.replace(" ", ""), correct)
                        m.putIfAbsent(correct, correct)
                        phraseSet.add(correct)
                    }
                } else {
                    m.putIfAbsent(s, s)
                    m.putIfAbsent(s.replace(" ", ""), s)
                    phraseSet.add(s)
                }
                n++
                // yield occasionally to reduce jank
                if (n % 5000 == 0) {
                    try { Thread.sleep(1) } catch (_: Exception) {}
                }
            }
        }
        mapInternal = m
        phrasesInternal = phraseSet.sortedByDescending { it.length }
        Log.i(TAG, "full vocab ready size=${m.size}")
        System.gc()
    }

    fun normalizeKey(s: String): String =
        s.trim()
            .replace('\u200c', ' ')
            .replace(Regex("[\\u064B-\\u065F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
