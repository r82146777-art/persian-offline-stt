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
    private var currentLayer = 0
    private var isShift = false
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

    private val ROW1 = listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "چ")
    private val ROW2 = listOf("ش", "س", "ی", "ب", "ل", "ا", "ت", "ن", "م", "ک", "گ")
    private val ROW3 = listOf("ظ", "ط", "ز", "ر", "ذ", "د", "پ", "و")
    private val ROW_NUM = listOf("۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹", "۰")
    private val SYM1 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val SYM2 = listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", ":")
    private val SYM3 = listOf("\"", "'", ",", ".", "/", "\\", "؟", "،", "؛", "«")

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { saveCurrentClipboard() }
    private val longPress = Runnable { if (!isListening) startVoice() else stopVoice() }

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
            text = "آفلاین تایپ — موتور Shenava Koochik · فشار طولانی فاصله = صوت"
            setTextColor(SUB); textSize = 11f; setPadding(12, 2, 12, 4); gravity = Gravity.CENTER
        }
        root.addView(statusView, lpMW())
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(4, 2, 4, 4)
        }
        toolbar.addView(tbBtn("📋 کلیپ‌بورد") { toggleClipboard() })
        toolbar.addView(tbBtn("🎤 صوت") { if (!isListening) startVoice() else stopVoice() })
        toolbar.addView(tbBtn("⌫") { deleteLast() })
        root.addView(toolbar, lpMW())
        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setBackgroundColor(0xFF252525.toInt()); setPadding(8, 6, 8, 6)
        }
        root.addView(clipboardPanel, lpMW())
        keysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
        else statusView?.text = "آفلاین تایپ — موتور Shenava Koochik · فشار طولانی فاصله = صوت"
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
            0 -> { addRow(c, ROW1); addRow(c, ROW2); addRow(c, ROW3, true) }
            1 -> { addRow(c, ROW_NUM); addRow(c, listOf(".", ",", "؟", "!", ":", ";", "ـ", "٪", "×", "÷")); addRow(c, listOf("(", ")", "[", "]", "{", "}", "<", ">")) }
            else -> { addRow(c, SYM1); addRow(c, SYM2); addRow(c, SYM3) }
        }
        addBottom(c)
    }

    private fun addRow(parent: LinearLayout, keys: List<String>, shift: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = lpMW().apply { setMargins(2, 2, 2, 2) }
        }
        if (shift) row.addView(makeKey("⇧", 1.2f, true) { isShift = !isShift; rebuildKeys() })
        keys.forEach { l -> row.addView(makeKey(l, 1f) { commitText(l) }) }
        if (shift) row.addView(makeKey("⌫", 1.2f, true) { deleteLast() })
        parent.addView(row)
    }

    private fun addBottom(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = lpMW().apply { setMargins(2, 4, 2, 2) }
        }
        val lab = when (currentLayer) { 0 -> "۱۲۳"; 1 -> "#+="; else -> "فا" }
        row.addView(makeKey(lab, 1.3f, true) { currentLayer = (currentLayer + 1) % 3; rebuildKeys() })
        row.addView(makeKey("،", 0.8f) { commitText("،") })
        row.addView(makeKey(".", 0.8f) { commitText(".") })
        val space = makeKey("فاصله", 3.8f) { }
        val spaceState = booleanArrayOf(false)
        val spaceLong = Runnable { spaceState[0] = true; longPress.run() }
        space.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    spaceState[0] = false; v.isPressed = true
                    mainHandler.postDelayed(spaceLong, LONG_PRESS_MS); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(spaceLong); v.isPressed = false
                    if (!spaceState[0] && e.action == MotionEvent.ACTION_UP) {
                        commitText(" "); playClick()
                    }
                    true
                }
                else -> false
            }
        }
        space.setOnClickListener(null); row.addView(space)
        row.addView(makeKey("↵", 1.3f, true) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        })
        parent.addView(row)
    }

    private fun makeKey(label: String, w: Float, special: Boolean = false, onTap: () -> Unit) = Button(this).apply {
        text = label; textSize = if (label.length > 2) 13f else 17f; setTextColor(TEXT)
        setBackgroundColor(if (special) KEY_BG_SP else KEY_BG); isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, 100, w).apply { setMargins(3, 3, 3, 3) }
        setOnClickListener { onTap(); playClick() }
        contentDescription = when (label) {
            "فاصله" -> "فاصله — فشار طولانی برای تایپ صوتی"
            "⌫" -> "پاک کردن"; "↵" -> "ورود"; "⇧" -> "شیفت"; else -> label
        }
    }

    private fun commitText(text: String) {
        val ic: InputConnection = currentInputConnection ?: return
        val out = if (isShift && text.length == 1) text.uppercase() else text
        ic.commitText(out, 1)
        if (isShift) { isShift = false; rebuildKeys() }
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

    private fun startVoice() {
        if (isListening) return
        isListening = true; pcmChunks.clear()
        statusView?.text = "🎤 در حال گوش دادن… (موتور Shenava Koochik)"
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 80) } catch (_: Exception) {}
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { statusView?.text = "خطا در میکروفون"; isListening = false; return }
        audioRecord?.startRecording()
        listenJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(SAMPLE_RATE / 5)
            while (isActive && isListening) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (n > 0) pcmChunks.add(buf.copyOf(n))
            }
        }
    }

    private fun stopVoice() {
        synchronized(stopLock) {
            if (!isListening) return
            isListening = false; listenJob?.cancel()
            try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 60) } catch (_: Exception) {}
        statusView?.text = "در حال تشخیص با Shenava…"
        scope.launch(Dispatchers.IO) {
            val raw = pcmChunks.flatMap { it.toList() }.toShortArray(); pcmChunks.clear()
            val (text0, engine) = DualAsr.transcribe(this@VoiceInputMethodService, raw, SAMPLE_RATE)
            var text = text0
            val secs = raw.size.toFloat() / SAMPLE_RATE
            withContext(Dispatchers.Main) {
                if (text.isNotBlank()) {
                    currentInputConnection?.commitText(text + " ", 1)
                    statusView?.text = "✓ [$engine ${"%.1f".format(secs)}s] $text"
                } else statusView?.text = "چیزی تشخیص داده نشد (${"%.1f".format(secs)}s صدا=$engine)"
                mainHandler.postDelayed({
                    statusView?.text = "آفلاین تایپ — موتور Shenava Koochik · فشار طولانی فاصله = صوت"
                }, 2500)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        saveCurrentClipboard()
        scope.launch(Dispatchers.IO) {
            try {
                if (!ShenavaEngine.isReady(this@VoiceInputMethodService)) {
                    ShenavaEngine.ensureModel(this@VoiceInputMethodService)
                }
                ShenavaEngine.load(this@VoiceInputMethodService)
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
