package com.persianstt.offline

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.inputmethodservice.InputMethodService
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
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * کیبورد کامل + تایپ صوتی با موتور Whisper آفلاین (دقت بالاتر)
 * تک‌ضربه برای همه کلیدها (مناسب صفحه‌خوان)
 * فشار طولانی فاصله = صوت
 */
class VoiceInputMethodService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    @Volatile private var isListening = false
    private val stopLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pcmChunks = CopyOnWriteArrayList<ShortArray>()

    private var statusView: TextView? = null
    private var row1: LinearLayout? = null
    private var row2: LinearLayout? = null
    private var row3: LinearLayout? = null
    private var row4: LinearLayout? = null

    private var currentIsFa = true
    private var isSymbols = false
    private var isShift = false
    private var prefs: SharedPreferences? = null
    private var vibrator: Vibrator? = null
    private var toneGen: ToneGenerator? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PREFS = "hamdel_stt"
        private const val KEY_VIBE = "key_vibe"
        private const val KEY_SOUND = "key_sound"
        private const val KEY_LANG = "lang"

        private val FA_ROWS = listOf(
            listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "چ"),
            listOf("ش", "س", "ی", "ب", "ل", "ا", "ت", "ن", "م", "ک", "گ"),
            listOf("⇧", "ظ", "ط", "ز", "ر", "ذ", "د", "پ", "و", "⌫")
        )
        private val EN_ROWS = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫")
        )
        private val SYM_ROWS = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "$", "%", "&", "*", "-", "=", "(", ")"),
            listOf("!", "\"", "'", ":", ";", "/", "?", ",", ".", "⌫")
        )
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try { toneGen = ToneGenerator(AudioManager.STREAM_SYSTEM, 70) } catch (_: Exception) {}
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusView = view.findViewById(R.id.imeStatus)
        row1 = view.findViewById(R.id.row1)
        row2 = view.findViewById(R.id.row2)
        row3 = view.findViewById(R.id.row3)
        row4 = view.findViewById(R.id.row4)
        isListening = false
        statusView?.visibility = View.GONE
        buildKeyboard()
        ensureWhisper()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (isListening) stopListening()
        ensureWhisper()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (isListening) stopListening()
        super.onFinishInputView(finishingInput)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun ensureWhisper() {
        if (WhisperEngine.isReady(this) && WhisperEngine.load(this, prefs?.getString(KEY_LANG, "") ?: "")) {
            statusView?.visibility = View.GONE
            return
        }
        // Model must be downloaded from MainActivity first
        if (!WhisperEngine.isReady(this)) {
            statusView?.visibility = View.VISIBLE
            statusView?.text = "ابتدا از برنامه اصلی مدل Whisper را دانلود کنید"
        }
    }

    private fun buildKeyboard() {
        row1?.removeAllViews()
        row2?.removeAllViews()
        row3?.removeAllViews()
        row4?.removeAllViews()
        val rows = when {
            isSymbols -> SYM_ROWS
            currentIsFa -> FA_ROWS
            else -> EN_ROWS
        }
        listOf(row1, row2, row3).forEachIndexed { i, row ->
            rows.getOrNull(i)?.forEach { row?.addView(makeKey(it)) }
        }
        row4?.addView(makeKey(if (isSymbols) "ABC" else "123", 1.2f))
        row4?.addView(makeKey("MIC", 1.2f))
        if (!isSymbols) row4?.addView(makeKey(if (currentIsFa) "،" else ",", 0.8f))
        row4?.addView(makeKey("SPACE", 3.2f))
        row4?.addView(makeKey(".", 0.8f))
        row4?.addView(makeKey(if (currentIsFa) "EN" else "FA", 1.0f))
        row4?.addView(makeKey("↵", 1.2f))
    }

    private fun makeKey(label: String, weight: Float = 1f): Button {
        val btn = Button(this)
        val lp = LinearLayout.LayoutParams(0, dp(48), weight)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        btn.layoutParams = lp
        btn.text = if (label == "SPACE") (if (currentIsFa) "فاصله" else "space") else label
        btn.contentDescription = when (label) {
            "SPACE" -> "فاصله. برای تایپ صوتی نگه دارید"
            "MIC" -> "میکروفون تایپ صوتی"
            "⌫" -> "پاک کردن"
            "↵" -> "خط جدید"
            "123" -> "اعداد و علائم"
            "ABC" -> "حروف"
            "EN", "FA" -> "تعویض زبان"
            else -> label
        }
        btn.setTextColor(Color.WHITE)
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 2) 11f else 16f)
        btn.setBackgroundColor(Color.parseColor("#333333"))
        btn.setPadding(0, 0, 0, 0)
        btn.minWidth = 0
        btn.minimumWidth = 0
        btn.isAllCaps = false
        btn.isClickable = true
        btn.isFocusable = true
        btn.isLongClickable = label == "SPACE" || label == "MIC"
        // کمک به صفحه‌خوان: اکشن کلیک واضح
        btn.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
                info.isClickable = true
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (host.isLongClickable) {
                    info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }
            }
        }

        if (label == "SPACE") {
            btn.setOnClickListener {
                currentInputConnection?.commitText(" ", 1)
                vibe(15)
                playKeySound()
            }
            btn.setOnLongClickListener {
                if (isListening) stopListening() else startListening()
                true
            }
        } else {
            btn.setOnClickListener { onKeyTap(label) }
        }
        return btn
    }

    private fun onKeyTap(label: String) {
        val ic = currentInputConnection ?: return
        vibe(15)
        playKeySound()
        when (label) {
            "⌫" -> ic.deleteSurroundingText(1, 0)
            "⇧" -> isShift = !isShift
            "MIC" -> if (isListening) stopListening() else startListening()
            "123" -> { isSymbols = true; buildKeyboard() }
            "ABC" -> { isSymbols = false; buildKeyboard() }
            "EN", "FA" -> { isSymbols = false; currentIsFa = !currentIsFa; buildKeyboard() }
            "↵" -> {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            }
            else -> {
                var t = label
                if (!currentIsFa && !isSymbols && isShift && t.length == 1 && t[0] in 'a'..'z') {
                    t = t.uppercase()
                    isShift = false
                }
                ic.commitText(t, 1)
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun vibe(ms: Long = 25) {
        if (prefs?.getBoolean(KEY_VIBE, true) != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    private fun playKeySound() {
        if (prefs?.getBoolean(KEY_SOUND, true) != true) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 20) } catch (_: Exception) {}
    }

    private fun playVoiceStartSound() {
        if (prefs?.getBoolean(KEY_SOUND, true) != true) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 150) } catch (_: Exception) {}
    }

    private fun playVoiceStopSound() {
        if (prefs?.getBoolean(KEY_SOUND, true) != true) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 100) } catch (_: Exception) {}
    }

    private fun startListening() {
        if (!WhisperEngine.isReady(this)) {
            Toast.makeText(this, "ابتدا از برنامه اصلی مدل Whisper را دانلود کنید", Toast.LENGTH_LONG).show()
            return
        }
        if (!WhisperEngine.load(this, prefs?.getString(KEY_LANG, "") ?: "")) {
            Toast.makeText(this, "خطا در بارگذاری مدل Whisper", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
            return
        }
        if (isListening) return

        try {
            pcmChunks.clear()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("buffer error")
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(SAMPLE_RATE / 2)
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("AudioRecord init failed")
            }
            ar.startRecording()
            synchronized(stopLock) {
                audioRecord = ar
                isListening = true
            }
            playVoiceStartSound()
            vibe(40)
            statusView?.visibility = View.VISIBLE
            statusView?.text = "در حال ضبط… برای توقف MIC را بزنید"

            listenJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(4096)
                while (isActive && isListening) {
                    val n = try {
                        synchronized(stopLock) { audioRecord?.read(buffer, 0, buffer.size) ?: -1 }
                    } catch (_: Exception) { -1 }
                    if (n > 0) {
                        pcmChunks.add(buffer.copyOf(n))
                    }
                }
            }
        } catch (e: Exception) {
            statusView?.visibility = View.VISIBLE
            statusView?.text = "خطا: ${e.message}"
            stopListening()
        }
    }

    private fun stopListening() {
        isListening = false
        try { listenJob?.cancel() } catch (_: Exception) {}
        listenJob = null

        mainHandler.post {
            synchronized(stopLock) {
                try {
                    audioRecord?.let { ar ->
                        try {
                            if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar.stop()
                        } catch (_: Exception) {}
                        try { ar.release() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                audioRecord = null
            }
            playVoiceStopSound()
            vibe(20)
            statusView?.visibility = View.VISIBLE
            statusView?.text = "در حال تشخیص…"

            // Decode with Whisper on background
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
                val text = if (pcm.isNotEmpty()) WhisperEngine.transcribe(pcm, SAMPLE_RATE) else ""
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        try { currentInputConnection?.commitText("$text ", 1) } catch (_: Exception) {}
                    }
                    statusView?.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
        scope.cancel()
        super.onDestroy()
    }
}
