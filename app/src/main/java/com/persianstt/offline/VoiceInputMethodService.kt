package com.persianstt.offline

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList

class VoiceInputMethodService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var statusView: TextView? = null
    private var clipboardPanel: LinearLayout? = null
    private var keysContainer: LinearLayout? = null
    private var currentLayer = 0 // 0=letters 1=numbers 2=symbols
    private var isShift = false
    private var imeLang = "fa" // fa | en
    private var isListening = false
    private var showClipboard = false
    private var listenJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val pcmChunks = CopyOnWriteArrayList<ShortArray>()
    private val stopLock = Any()
    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var clipboardManager: ClipboardManager? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val LONG_PRESS_MS = 550L
        private const val KEY_H = 128 // taller keys like Gboard
        private const val PREFS = "hamdel_stt"
        private const val KEY_CLIPBOARD = "clipboard_history"
        private const val MAX_CLIPS = 20
        private const val BG = 0xFF1A1A1A.toInt()
        private const val KEY_BG = 0xFF2C2C2C.toInt()
        private const val KEY_BG_SP = 0xFF3A3A3A.toInt()
        private const val ACCENT = 0xFF4A90D9.toInt()
        private const val TEXT = 0xFFFFFFFF.toInt()
        private const val SUB = 0xFFAAAAAA.toInt()
    }

    // Persian (standard-ish order)
    private val FA1 = listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "چ")
    private val FA2 = listOf("ش", "س", "ی", "ب", "ل", "ا", "ت", "ن", "م", "ک", "گ")
    private val FA3 = listOf("ظ", "ط", "ز", "ر", "ذ", "د", "پ", "و", "ژ")
    // English QWERTY
    private val EN1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val EN2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val EN3 = listOf("z", "x", "c", "v", "b", "n", "m")
    // Phone-pad numbers (3x4) + side symbols
    private val NUM_PAD = listOf(
        listOf("+", "۱", "۲", "۳", "-"),
        listOf("*", "۴", "۵", "۶", "/"),
        listOf("#", "۷", "۸", "۹", "%"),
        listOf(",", "٫", "۰", ".", "=")
    )
    private val SYM1 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val SYM2 = listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", ":")
    private val SYM3 = listOf("\"", "'", "،", ".", "/", "\\", "؟", "؛", "«", "»")

    private val letterVariants = mapOf(
        "ا" to listOf("ا", "آ", "أ", "إ", "ء"),
        "و" to listOf("و", "ؤ"),
        "ی" to listOf("ی", "ي", "ئ"),
        "ه" to listOf("ه", "ة", "ۀ"),
        "ر" to listOf("ر", "ژ"),
        "ز" to listOf("ز", "ژ", "ض"),
        "ک" to listOf("ک", "ك"),
        "a" to listOf("a", "á", "à", "â"),
        "e" to listOf("e", "é", "è", "ê"),
        "i" to listOf("i", "í", "ì"),
        "o" to listOf("o", "ó", "ò", "ô"),
        "u" to listOf("u", "ú", "ù"),
        "n" to listOf("n", "ñ"),
        "c" to listOf("c", "ç")
    )
    private val symbolNames = mapOf(
        "!" to "علامت تعجب", "@" to "ات ساین", "#" to "هشتگ", "$" to "دلار",
        "%" to "درصد", "^" to "هشتک", "&" to "و", "*" to "ستاره",
        "(" to "پرانتز باز", ")" to "پرانتز بسته", "-" to "خط تیره", "_" to "زیرخط",
        "=" to "مساوی", "+" to "به‌علاوه", "[" to "کروشه باز", "]" to "کروشه بسته",
        "{" to "آکولاد باز", "}" to "آکولاد بسته", ";" to "نقطه‌ویرگول", ":" to "دونقطه",
        "\"" to "گیومه", "'" to "آپاستروف", "،" to "ویرگول", "." to "نقطه",
        "/" to "اسلش", "\\" to "بک‌اسلش", "؟" to "علامت سؤال", "؛" to "نقطه‌ویرگول فارسی",
        "«" to "گیومه باز", "»" to "گیومه بسته", "٫" to "ممیز",
        "٪" to "درصد", "×" to "ضرب", "÷" to "تقسیم", "," to "ویرگول انگلیسی",
        "۱" to "یک", "۲" to "دو", "۳" to "سه", "۴" to "چهار", "۵" to "پنج",
        "۶" to "شش", "۷" to "هفت", "۸" to "هشت", "۹" to "نه", "۰" to "صفر"
    )

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { saveCurrentClipboard() }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try { toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (_: Exception) {}
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        saveCurrentClipboard()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
            setPadding(4, 6, 4, 6)
        }
        statusView = TextView(this).apply {
            text = "آفلاین تایپ — Vosk · فشار طولانی فاصله = زبان"
            setTextColor(SUB); textSize = 11f; setPadding(12, 2, 12, 4); gravity = Gravity.CENTER
        }
        root.addView(statusView, lpMW())
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(4, 2, 4, 4)
        }
        toolbar.addView(tbBtn("📋") { toggleClipboard() })
        toolbar.addView(tbBtn("🎤") { if (!isListening) startVoice() else stopVoice() })
        toolbar.addView(tbBtn("✏️") { editLastCommitted() })
        toolbar.addView(tbBtn("😊") { insertSmartEmoji() })
        toolbar.addView(tbBtn("⌫") { deleteLast() })
        root.addView(toolbar, lpMW())
        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setBackgroundColor(0xFF252525.toInt()); setPadding(8, 6, 8, 6)
        }
        root.addView(clipboardPanel, lpMW())
        keysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(keysContainer, lpMW())
        rebuildKeys()
        return root
    }

    private fun lpMW() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun tbBtn(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; setTextColor(TEXT); setBackgroundColor(KEY_BG_SP); isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, 72, 1f).apply { setMargins(3, 2, 3, 2) }
        setOnClickListener { playClick(); onTap() }
    }

    private fun saveCurrentClipboard() {
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return
            if (text.isBlank() || text.length > 2000) return
            val list = loadClips().toMutableList()
            list.removeAll { it == text }; list.add(0, text)
            while (list.size > MAX_CLIPS) list.removeAt(list.lastIndex)
            saveClips(list)
            if (showClipboard) mainHandler.post { renderClipboard() }
        } catch (_: Exception) {}
    }

    private fun loadClips(): List<String> = try {
        val arr = JSONArray(prefs.getString(KEY_CLIPBOARD, "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun saveClips(list: List<String>) {
        val arr = JSONArray(); list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_CLIPBOARD, arr.toString()).apply()
    }

    private fun toggleClipboard() {
        showClipboard = !showClipboard
        clipboardPanel?.visibility = if (showClipboard) View.VISIBLE else View.GONE
        if (showClipboard) { saveCurrentClipboard(); renderClipboard(); statusView?.text = "کلیپ‌بورد — ضربه = جایگذاری" }
        else statusView?.text = "آفلاین تایپ — Vosk · فشار طولانی فاصله = زبان"
    }

    private fun renderClipboard() {
        val panel = clipboardPanel ?: return
        panel.removeAllViews()
        panel.addView(TextView(this).apply {
            text = "تاریخچه کلیپ‌بورد (ضربه = جایگذاری)"
            setTextColor(ACCENT); textSize = 12f; setPadding(8, 4, 8, 8); gravity = Gravity.CENTER
        }, lpMW())
        val clips = loadClips()
        if (clips.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "هنوز چیزی کپی نشده"; setTextColor(SUB); textSize = 12f
                setPadding(12, 16, 12, 16); gravity = Gravity.CENTER
            }, lpMW()); return
        }
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 280) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        clips.forEachIndexed { i, item ->
            val preview = if (item.length > 80) item.take(80) + "…" else item
            list.addView(Button(this).apply {
                text = preview; textSize = 13f; setTextColor(TEXT); isAllCaps = false
                setBackgroundColor(if (i % 2 == 0) KEY_BG else 0xFF333333.toInt())
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(2, 2, 2, 2) }
                setOnClickListener {
                    commitText(item); playClick(); showClipboard = false
                    clipboardPanel?.visibility = View.GONE; statusView?.text = "جایگذاری شد ✓"
                }
                setOnLongClickListener {
                    val u = loadClips().toMutableList(); u.remove(item); saveClips(u); renderClipboard(); playClick(); true
                }
            })
        }
        scroll.addView(list); panel.addView(scroll)
    }

    private fun rebuildKeys() {
        val c = keysContainer ?: return; c.removeAllViews()
        when (currentLayer) {
            0 -> {
                if (imeLang == "en") {
                    addRow(c, if (isShift) EN1.map { it.uppercase() } else EN1)
                    addRow(c, if (isShift) EN2.map { it.uppercase() } else EN2)
                    addRow(c, if (isShift) EN3.map { it.uppercase() } else EN3, withShiftBksp = true)
                } else {
                    addRow(c, FA1)
                    addRow(c, FA2)
                    addRow(c, FA3, withShiftBksp = true)
                }
            }
            1 -> {
                addNumberPad(c)
            }
            else -> {
                addSymbolRow(c, SYM1)
                addSymbolRow(c, SYM2)
                addSymbolRow(c, SYM3)
            }
        }
        addBottom(c)
    }

    /** LTR rows so ⌫ and ↵ sit on the RIGHT like normal keyboards */
    private fun addRow(parent: LinearLayout, keys: List<String>, withShiftBksp: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = lpMW().apply { setMargins(2, 2, 2, 2) }
        }
        if (withShiftBksp) {
            row.addView(makeKey("⇧", 1.2f, true) {
                isShift = !isShift
                rebuildKeys()
            })
        }
        keys.forEach { l ->
            row.addView(makeKey(l, 1f) {
                val out = if (isShift && l.length == 1 && l[0] in 'a'..'z') l.uppercase() else l
                commitText(out)
                if (isShift && imeLang == "en") {
                    isShift = false
                    rebuildKeys()
                }
            })
        }
        if (withShiftBksp) {
            row.addView(makeKey("⌫", 1.2f, true) { deleteLast() })
        }
        parent.addView(row)
    }


    private fun addSymbolRow(parent: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = lpMW().apply { setMargins(2, 2, 2, 2) }
        }
        keys.forEach { l ->
            row.addView(makeKey(l, 1f) { commitRaw(l) })
        }
        parent.addView(row)
    }

    /** Number pad: 3x4 digits + right column ⌫ / ۱۲۳|فا / فاصله / ↵ */
    private fun addNumberPad(parent: LinearLayout) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = lpMW()
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f)
        }
        // Western digits type reliably in all apps; labels can stay Persian-looking
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )
        val faLabel = mapOf(
            "0" to "۰", "1" to "۱", "2" to "۲", "3" to "۳", "4" to "۴",
            "5" to "۵", "6" to "۶", "7" to "۷", "8" to "۸", "9" to "۹"
        )
        rows.forEach { keys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = lpMW().apply { setMargins(2, 2, 2, 2) }
            }
            keys.forEach { k ->
                val btn = makeKey(faLabel[k] ?: k, 1f) { commitRaw(k) }
                row.addView(btn)
            }
            left.addView(row)
        }
        // side symbols under pad
        val sideSym = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = lpMW().apply { setMargins(2, 2, 2, 2) }
        }
        listOf("+", "-", "/", ".", ",", "،").forEach { s ->
            sideSym.addView(makeKey(s, 1f) { commitRaw(s) })
        }
        left.addView(sideSym)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f)
        }
        right.addView(makeKey("⌫", 1f, true) { deleteLast() }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KEY_H).apply { setMargins(3, 3, 3, 3) }
        })
        right.addView(makeKey("فا", 1f, true) {
            currentLayer = 0; rebuildKeys()
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KEY_H).apply { setMargins(3, 3, 3, 3) }
        })
        right.addView(makeKey("فاصله", 1f, true) { commitText(" ") }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KEY_H).apply { setMargins(3, 3, 3, 3) }
        })
        right.addView(makeKey("↵", 1f, true) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KEY_H).apply { setMargins(3, 3, 3, 3) }
        })

        outer.addView(left)
        outer.addView(right)
        parent.addView(outer)
    }

    private fun addBottom(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = lpMW().apply { setMargins(2, 4, 2, 2) }
        }
        row.addView(makeKey("#+=", 1.1f, true) {
            currentLayer = if (currentLayer == 2) 0 else 2
            rebuildKeys()
        })
        row.addView(makeKey("۱۲۳", 1.1f, true) {
            currentLayer = if (currentLayer == 1) 0 else 1
            rebuildKeys()
        })
        // comma & period beside space
        row.addView(makeKey("،", 0.7f) { commitText("،") })
        row.addView(makeKey(".", 0.7f) { commitText(".") })
        val spaceLabel = if (imeLang == "en") "space" else "فاصله"
        val space = makeKey(spaceLabel, 2.8f) { }
        val longFired = booleanArrayOf(false)
        val longRun = Runnable {
            longFired[0] = true
            showLanguagePicker()
        }
        space.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    longFired[0] = false
                    v.isPressed = true
                    mainHandler.postDelayed(longRun, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longRun)
                    v.isPressed = false
                    if (!longFired[0] && e.action == MotionEvent.ACTION_UP) {
                        commitText(" "); playClick()
                    }
                    true
                }
                else -> false
            }
        }
        space.setOnClickListener(null)
        row.addView(space)
        row.addView(makeKey("↵", 1.2f, true) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        })
        parent.addView(row)
    }

    private fun showPopupVariants(anchor: View, variants: List<String>) {
        try {
            val popup = android.widget.PopupWindow(this)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF333333.toInt())
                setPadding(8, 8, 8, 8)
            }
            variants.forEach { v ->
                box.addView(makeKey(v, 1f) {
                    commitText(v)
                    popup.dismiss()
                }.also { b ->
                    b.layoutParams = LinearLayout.LayoutParams(96, KEY_H).apply { setMargins(4, 0, 4, 0) }
                })
            }
            popup.contentView = box
            popup.isOutsideTouchable = true
            popup.isFocusable = true
            popup.elevation = 12f
            popup.showAsDropDown(anchor, 0, -anchor.height - 120)
        } catch (_: Exception) {}
    }

    private fun showLanguagePicker() {
        imeLang = if (imeLang == "fa") "en" else "fa"
        currentLayer = 0
        isShift = false
        rebuildKeys()
        statusView?.text = if (imeLang == "en") "English" else "فارسی"
        try {
            android.widget.Toast.makeText(this, if (imeLang == "en") "English" else "فارسی", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun makeKey(label: String, w: Float, special: Boolean = false, onTap: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 15f else 20f
        setTextColor(TEXT)
        setBackgroundColor(if (special) KEY_BG_SP else KEY_BG)
        isAllCaps = false
        // TalkBack: only letter/name, not "دکمه"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = when {
            label == "فاصله" || label == "space" -> "فاصله"
            label == "⌫" -> "پاک کردن"
            label == "↵" -> "خط جدید"
            label == "⇧" -> "شیفت"
            label == "#+=" -> "علائم"
            label == "۱۲۳" -> "اعداد"
            label == "فا" -> "حروف"
            symbolNames.containsKey(label) -> symbolNames[label]
            else -> label
        }
        // prevent Button class name being appended in some TalkBack modes
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(0, KEY_H, w).apply { setMargins(2, 2, 2, 2) }
        setOnClickListener { onTap(); playClick() }
        val variants = letterVariants[label.lowercase()] ?: letterVariants[label]
        if (variants != null && variants.size > 1) {
            setOnLongClickListener {
                showPopupVariants(this, variants)
                playClick()
                true
            }
        }
    }

    /** Guaranteed insert for digits/symbols (no shift logic). */
    private fun commitRaw(text: String) {
        val ic = currentInputConnection ?: return
        try {
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            ic.endBatchEdit()
        } catch (_: Exception) {
            try { ic.commitText(text, 1) } catch (_: Exception) {}
        }
        playClick()
    }

    private fun commitText(text: String) {
        val ic: InputConnection = currentInputConnection ?: return
        val out = if (isShift && text.length == 1 && text[0] in 'a'..'z') text.uppercase() else text
        try {
            ic.beginBatchEdit()
            ic.commitText(out, 1)
            ic.endBatchEdit()
        } catch (_: Exception) {
            try { ic.commitText(out, 1) } catch (_: Exception) {}
        }
        if (isShift && imeLang == "en") {
            isShift = false
            rebuildKeys()
        }
    }

    private fun deleteLast() { currentInputConnection?.deleteSurroundingText(1, 0) }

    private fun playClick() {
        if (prefs.getBoolean("key_sound", true)) {
            val vol = prefs.getInt("sound_volume", 60)
            val effect = prefs.getInt("sound_effect", 0)
            KeySoundPlayer.play(this, effect, vol)
        }
        if (prefs.getBoolean("key_vibe", true)) try {
            val strength = prefs.getInt("vibe_strength", 60).coerceIn(10, 100)
            val ms = 10L + (strength / 10)
            if (android.os.Build.VERSION.SDK_INT >= 26)
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, (255 * strength / 100).coerceIn(1, 255)))
            else { @Suppress("DEPRECATION") vibrator?.vibrate(ms) }
        } catch (_: Exception) {}
    }

    private var lastCommitted: String = ""

    private fun insertSmartEmoji() {
        val base = lastCommitted.ifBlank {
            try {
                currentInputConnection?.getTextBeforeCursor(120, 0)?.toString() ?: ""
            } catch (_: Exception) { "" }
        }
        if (base.isBlank()) {
            commitText("😊")
            return
        }
        statusView?.text = "ایموجی هوشمند…"
        scope.launch(Dispatchers.IO) {
            val enriched = try {
                kotlinx.coroutines.runBlocking { OfflineLlm.addEmojis(this@VoiceInputMethodService, base.trim()) }
            } catch (_: Exception) { "" }.ifBlank { OfflineAi.addEmojis(base.trim()) }
            withContext(Dispatchers.Main) {
                val ic = currentInputConnection ?: return@withContext
                try {
                    val before = ic.getTextBeforeCursor(base.length + 5, 0)?.toString() ?: ""
                    if (before.endsWith(base.trim())) {
                        ic.deleteSurroundingText(base.trim().length, 0)
                        ic.commitText(enriched, 1)
                    } else {
                        val extra = enriched.removePrefix(base.trim()).trim()
                        if (extra.isNotBlank()) ic.commitText(" $extra", 1)
                        else ic.commitText(enriched, 1)
                    }
                } catch (_: Exception) {
                    commitText(" ✨")
                }
                lastCommitted = enriched
                statusView?.text = "ایموجی AI آفلاین"
            }
        }
    }

    private fun editLastCommitted() {
        val ic = currentInputConnection ?: return
        val before = try { ic.getTextBeforeCursor(400, 0)?.toString() ?: "" } catch (_: Exception) { "" }
        if (before.isBlank()) return
        statusView?.text = "اصلاح هوشمند…"
        scope.launch(Dispatchers.IO) {
            val fixed = try {
                kotlinx.coroutines.runBlocking { OfflineLlm.correctText(this@VoiceInputMethodService, before) }
            } catch (_: Exception) { "" }.ifBlank {
                OfflineVoiceAi.improve(this@VoiceInputMethodService, before).ifBlank {
                    OfflineAi.correctText(this@VoiceInputMethodService, before)
                }
            }
            withContext(Dispatchers.Main) {
                val conn = currentInputConnection ?: return@withContext
                if (fixed == before) {
                    statusView?.text = "اصلاحی لازم نبود"
                    return@withContext
                }
                try {
                    conn.deleteSurroundingText(before.length, 0)
                    conn.commitText(fixed, 1)
                } catch (_: Exception) {}
                lastCommitted = fixed
                statusView?.text = "اصلاح AI آفلاین"
            }
        }
    }

    private fun startVoice() {
        if (isListening) return
        isListening = true; pcmChunks.clear()
        statusView?.text = "🎤 گوش می‌دهم… (توقف خودکار با سکوت)"
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 80) } catch (_: Exception) {}
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            statusView?.text = "خطا در میکروفون"; isListening = false; return
        }
        audioRecord?.startRecording()
        listenJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(SAMPLE_RATE / 10) // 100ms frames
            var speechSeen = false
            var silentFrames = 0
            val silenceLimit = 12 // ~1.2s silence after speech → auto stop
            val energyThr = 900
            while (isActive && isListening) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) continue
                pcmChunks.add(buf.copyOf(n))
                var peak = 0
                for (i in 0 until n) {
                    val a = kotlin.math.abs(buf[i].toInt())
                    if (a > peak) peak = a
                }
                if (peak >= energyThr) {
                    speechSeen = true
                    silentFrames = 0
                } else if (speechSeen) {
                    silentFrames++
                    if (silentFrames >= silenceLimit) {
                        // auto-stop like Google
                        withContext(Dispatchers.Main) {
                            try { toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 100) } catch (_: Exception) {}
                            statusView?.text = "توقف خودکار…"
                            stopVoice()
                        }
                        break
                    }
                }
            }
        }
    }

    private fun stopVoice() {
        synchronized(stopLock) {
            if (!isListening) return
        }
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 60) } catch (_: Exception) {}
        statusView?.text = "در حال تشخیص…"
        scope.launch(Dispatchers.IO) {
            try { kotlinx.coroutines.delay(500) } catch (_: Exception) {}
            val job = listenJob
            synchronized(stopLock) { isListening = false }
            try { job?.join() } catch (_: Exception) {}
            synchronized(stopLock) {
                try {
                    val ar = audioRecord
                    if (ar != null) {
                        try {
                            val tail = ShortArray(SAMPLE_RATE / 2)
                            val n = ar.read(tail, 0, tail.size)
                            if (n > 0) pcmChunks.add(tail.copyOf(n))
                        } catch (_: Exception) {}
                    }
                    try { audioRecord?.stop() } catch (_: Exception) {}
                    try { audioRecord?.release() } catch (_: Exception) {}
                    audioRecord = null
                } catch (_: Exception) {}
                listenJob = null
            }
            val raw = pcmChunks.flatMap { it.toList() }.toShortArray(); pcmChunks.clear()
            val (text0, engine) = DualAsr.transcribe(this@VoiceInputMethodService, raw, SAMPLE_RATE)
            var text = text0
            val secs = raw.size.toFloat() / SAMPLE_RATE
            withContext(Dispatchers.Main) {
                if (text.isNotBlank()) {
                    lastCommitted = text
                    currentInputConnection?.commitText(text + " ", 1)
                    statusView?.text = "✓ [$engine ${"%.1f".format(secs)}s] $text"
                } else statusView?.text = "چیزی تشخیص داده نشد (${"%.1f".format(secs)}s صدا=$engine)"
                mainHandler.postDelayed({
                    statusView?.text = "آفلاین تایپ — Vosk · فشار طولانی فاصله = زبان"
                }, 2500)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        saveCurrentClipboard()
        scope.launch(Dispatchers.IO) {
            try {
                if (VoskEngine.isReady(this@VoiceInputMethodService)) {
                    VoskEngine.load(this@VoiceInputMethodService)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        stopVoice()
        try { clipboardManager?.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        try { toneGen?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
