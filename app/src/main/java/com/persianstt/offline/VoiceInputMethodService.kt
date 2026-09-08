package com.persianstt.offline

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Full offline Persian/English IME.
 * - Single-tap keys via normal OnClickListener (reliable on all devices + TalkBack)
 * - Long-press SPACE only = voice typing
 * - Vosk primary, Whisper forced-lang fallback for better accuracy
 */
class VoiceInputMethodService : android.inputmethodservice.InputMethodService() {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PREFS = "hamdel_stt"
        private const val KEY_SOUND = "key_sound"
        private const val KEY_VIBE = "key_vibe"
        private const val KEY_LANG = "lang"

        private val ROW1_FA = listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "چ")
        private val ROW2_FA = listOf("ش", "س", "ی", "ب", "ل", "ا", "ت", "ن", "م", "ک", "گ")
        private val ROW3_FA = listOf("ظ", "ط", "ز", "ر", "ذ", "د", "پ", "و", "ئ")

        private val ROW1_EN = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        private val ROW2_EN = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        private val ROW3_EN = listOf("z", "x", "c", "v", "b", "n", "m")

        private val ROW1_SYM = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val ROW2_SYM = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
        private val ROW3_SYM = listOf("*", "\"", "'", ":", ";", "!", "?", "~", "=")
    }

    private lateinit var prefs: SharedPreferences
    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var row1: LinearLayout? = null
    private var row2: LinearLayout? = null
    private var row3: LinearLayout? = null
    private var row4: LinearLayout? = null

    private var isPersian = true
    private var isSymbols = false
    private var isShift = false
    private var currentLangCode = "fa"

    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    @Volatile private var isListening = false
    private val stopLock = Any()
    private val pcmChunks = CopyOnWriteArrayList<ShortArray>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currentLangCode = prefs.getString(KEY_LANG, "fa") ?: "fa"
        isPersian = currentLangCode != "en"
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        } catch (_: Exception) {}
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.keyboard_view, null) as LinearLayout
        rootView = v
        statusView = v.findViewById(R.id.imeStatus)
        row1 = v.findViewById(R.id.row1)
        row2 = v.findViewById(R.id.row2)
        row3 = v.findViewById(R.id.row3)
        row4 = v.findViewById(R.id.row4)
        v.layoutDirection = View.LAYOUT_DIRECTION_LTR
        buildKeyboard()
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (isListening) stopVoice()
        scope.launch(Dispatchers.IO) {
            if (VoskEngine.isReady(this@VoiceInputMethodService, currentLangCode)) {
                VoskEngine.load(this@VoiceInputMethodService, currentLangCode)
            }
            if (WhisperEngine.isReady(this@VoiceInputMethodService)) {
                WhisperEngine.load(this@VoiceInputMethodService, currentLangCode)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (isListening) stopVoice()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopVoice()
        try { tone?.release() } catch (_: Exception) {}
        tone = null
        super.onDestroy()
    }

    private fun buildKeyboard() {
        row1?.removeAllViews()
        row2?.removeAllViews()
        row3?.removeAllViews()
        row4?.removeAllViews()

        val r1 = if (isSymbols) ROW1_SYM else if (isPersian) ROW1_FA else ROW1_EN
        val r2 = if (isSymbols) ROW2_SYM else if (isPersian) ROW2_FA else ROW2_EN
        val r3 = if (isSymbols) ROW3_SYM else if (isPersian) ROW3_FA else ROW3_EN

        r1.forEach { addKey(row1!!, it) }
        r2.forEach { addKey(row2!!, it) }

        addKey(row3!!, if (isSymbols) "ABC" else "⇧", weight = 1.4f, special = true)
        r3.forEach { addKey(row3!!, it) }
        addKey(row3!!, "⌫", weight = 1.4f, special = true)

        addKey(row4!!, if (isSymbols) "ABC" else "123", weight = 1.3f, special = true)
        addKey(row4!!, if (isPersian) "EN" else "FA", weight = 1.2f, special = true)
        addKey(row4!!, "،", weight = 1.0f)
        addKey(row4!!, " ", weight = 3.8f, label = "فاصله", special = true)
        addKey(row4!!, ".", weight = 1.0f)
        addKey(row4!!, "↵", weight = 1.3f, special = true)
        addKey(row4!!, "🎤", weight = 1.3f, special = true)
    }

    private fun addKey(
        row: LinearLayout,
        code: String,
        weight: Float = 1f,
        label: String? = null,
        special: Boolean = false
    ) {
        val btn = Button(this).apply {
            text = label ?: if (!isPersian && !isSymbols && isShift) code.uppercase() else code
            textSize = if (code.length > 1 || special) 13f else 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.NORMAL)
            setBackgroundColor(if (special) 0xFF2C2C2C.toInt() else 0xFF3A3A3A.toInt())
            setPadding(2, 10, 2, 10)
            isAllCaps = false
            isClickable = true
            isFocusable = true
            contentDescription = when (code) {
                " " -> "فاصله. فشار طولانی برای تایپ صوتی"
                "⌫" -> "پاک کردن"
                "↵" -> "ورود"
                "🎤" -> "تایپ صوتی"
                "⇧" -> "شیفت"
                "123", "ABC" -> "اعداد و علائم"
                "EN", "FA" -> "تغییر زبان"
                else -> code
            }

            // TRUE single-tap
            setOnClickListener {
                onKey(code)
            }

            // Long-press ONLY on space = voice
            if (code == " ") {
                setOnLongClickListener {
                    startOrStopVoice()
                    true
                }
            }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            setMargins(2, 3, 2, 3)
        }
        row.addView(btn, lp)
    }

    private fun onKey(code: String) {
        playClick()
        val ic = currentInputConnection ?: return
        when (code) {
            "⌫" -> {
                val selected = ic.getSelectedText(0)
                if (selected != null && selected.isNotEmpty()) {
                    ic.commitText("", 1)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            "↵" -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            "⇧" -> {
                isShift = !isShift
                buildKeyboard()
            }
            "123" -> {
                isSymbols = true
                buildKeyboard()
            }
            "ABC" -> {
                isSymbols = false
                buildKeyboard()
            }
            "EN" -> {
                isPersian = false
                currentLangCode = "en"
                prefs.edit().putString(KEY_LANG, "en").apply()
                isShift = false
                isSymbols = false
                buildKeyboard()
                scope.launch(Dispatchers.IO) {
                    VoskEngine.load(this@VoiceInputMethodService, "en")
                    if (WhisperEngine.isReady(this@VoiceInputMethodService)) {
                        WhisperEngine.load(this@VoiceInputMethodService, "en")
                    }
                }
            }
            "FA" -> {
                isPersian = true
                currentLangCode = "fa"
                prefs.edit().putString(KEY_LANG, "fa").apply()
                isShift = false
                isSymbols = false
                buildKeyboard()
                scope.launch(Dispatchers.IO) {
                    VoskEngine.load(this@VoiceInputMethodService, "fa")
                    if (WhisperEngine.isReady(this@VoiceInputMethodService)) {
                        WhisperEngine.load(this@VoiceInputMethodService, "fa")
                    }
                }
            }
            "🎤" -> startOrStopVoice()
            " " -> ic.commitText(" ", 1)
            else -> {
                val t = if (!isPersian && !isSymbols && isShift) code.uppercase() else code
                ic.commitText(t, 1)
                if (isShift && !isSymbols) {
                    isShift = false
                    buildKeyboard()
                }
            }
        }
    }

    private fun startOrStopVoice() {
        if (isListening) stopVoice() else startVoice()
    }

    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showStatus("مجوز میکروفون لازم است — از برنامه اصلی بدهید")
            return
        }
        if (!VoskEngine.isReady(this, currentLangCode) && !WhisperEngine.isReady(this)) {
            showStatus("ابتدا از برنامه اصلی مدل را دانلود کنید")
            return
        }
        scope.launch(Dispatchers.IO) {
            VoskEngine.load(this@VoiceInputMethodService, currentLangCode)
            if (WhisperEngine.isReady(this@VoiceInputMethodService)) {
                WhisperEngine.load(this@VoiceInputMethodService, currentLangCode)
            }
            withContext(Dispatchers.Main) {
                synchronized(stopLock) {
                    if (isListening) return@withContext
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        audioRecord = AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf * 2
                        )
                        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                            showStatus("میکروفون آماده نیست")
                            return@withContext
                        }
                        pcmChunks.clear()
                        audioRecord?.startRecording()
                        isListening = true
                        playBeep(true)
                        vibrate(40)
                        showStatus("🎤 در حال شنیدن… دوباره برای توقف")
                        listenJob = scope.launch(Dispatchers.IO) {
                            val buf = ShortArray(SAMPLE_RATE / 4)
                            while (isActive && isListening) {
                                val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                                if (n > 0) {
                                    pcmChunks.add(buf.copyOf(n))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        isListening = false
                        showStatus("خطا: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopVoice() {
        synchronized(stopLock) {
            if (!isListening) return
            isListening = false
            listenJob?.cancel()
            listenJob = null
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}
            audioRecord = null
            playBeep(false)
            vibrate(25)
            showStatus("در حال تشخیص…")
        }
        val chunks = pcmChunks.toList()
        pcmChunks.clear()
        scope.launch(Dispatchers.IO) {
            val total = chunks.sumOf { it.size }
            val pcm = ShortArray(total)
            var o = 0
            for (c in chunks) {
                System.arraycopy(c, 0, pcm, o, c.size)
                o += c.size
            }
            val text = if (pcm.isNotEmpty()) recognizeBest(pcm) else ""
            withContext(Dispatchers.Main) {
                if (text.isNotBlank()) {
                    currentInputConnection?.commitText(text + " ", 1)
                }
                hideStatus()
            }
        }
    }

    private fun recognizeBest(pcm: ShortArray): String {
        var text = ""
        try {
            if (VoskEngine.isReady(this, currentLangCode)) {
                VoskEngine.load(this, currentLangCode)
                text = VoskEngine.transcribe(pcm, SAMPLE_RATE)
            }
        } catch (_: Exception) {}

        val weak = text.isBlank() || text.length < 2
        if (weak && WhisperEngine.isReady(this)) {
            try {
                WhisperEngine.load(this, currentLangCode)
                val alt = WhisperEngine.transcribe(pcm, SAMPLE_RATE)
                if (alt.isNotBlank() && alt.length >= text.length) {
                    text = alt
                }
            } catch (_: Exception) {}
        }

        text = text.replace(
            Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uac00-\\ud7af\\uff00-\\uffef\\u3000-\\u303f]"),
            ""
        ).trim()
        return NumberNormalizer.normalize(text)
    }

    private fun playClick() {
        if (!prefs.getBoolean(KEY_SOUND, true)) return
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
        } catch (_: Exception) {}
        if (prefs.getBoolean(KEY_VIBE, true)) vibrate(12)
    }

    private fun playBeep(start: Boolean) {
        try {
            tone?.startTone(
                if (start) ToneGenerator.TONE_CDMA_CONFIRM else ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
                80
            )
        } catch (_: Exception) {}
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    private fun showStatus(msg: String) {
        statusView?.apply {
            text = msg
            visibility = View.VISIBLE
        }
    }

    private fun hideStatus() {
        statusView?.visibility = View.GONE
    }
}
