package com.persianstt.offline

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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Full Persian offline keyboard (IME) + voice typing.
 * - RTL layout
 * - Single-tap keys (works with TalkBack)
 * - Long-press Space = start/stop voice typing
 * - Numbers / symbols layers
 * - Sound + vibration (toggleable)
 * - Uses WhisperEngine (forced fa/en only) primary, Vosk fallback
 */
class VoiceInputMethodService : InputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var keyboardRoot: LinearLayout? = null
    private var statusView: TextView? = null
    private var currentLayer = LAYER_LETTERS
    private var isShift = false
    private var isListening = false
    private var listenJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val pcmChunks = CopyOnWriteArrayList<ShortArray>()
    private val stopLock = Any()
    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val LAYER_LETTERS = 0
        private const val LAYER_NUMBERS = 1
        private const val LAYER_SYMBOLS = 2
        private const val LONG_PRESS_MS = 450L
    }

    // Persian letters (RTL visual order left-to-right in code = right-to-left on screen)
    private val ROW1 = listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "چ")
    private val ROW2 = listOf("ش", "س", "ی", "ب", "ل", "ا", "ت", "ن", "م", "ک", "گ")
    private val ROW3 = listOf("ظ", "ط", "ز", "ر", "ذ", "د", "پ", "و")
    private val ROW_NUM = listOf("۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹", "۰")
    private val ROW_SYM1 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val ROW_SYM2 = listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", ":")
    private val ROW_SYM3 = listOf("\"", "'", ",", ".", "/", "\\", "؟", "،", "؛", "«")

    override fun onCreate() {
        super.onCreate()
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        } catch (_: Exception) {}
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(4, 8, 4, 8)
        }
        keyboardRoot = root

        statusView = TextView(this).apply {
            text = "آفلاین تایپ — فشار طولانی فاصله = صوت"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 12f
            setPadding(16, 4, 16, 8)
            gravity = android.view.Gravity.CENTER
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        buildKeyboard(root)
        return root
    }

    private fun buildKeyboard(root: LinearLayout) {
        // Remove old key rows (keep status)
        while (root.childCount > 1) root.removeViewAt(1)

        when (currentLayer) {
            LAYER_LETTERS -> {
                addKeyRow(root, ROW1)
                addKeyRow(root, ROW2)
                addKeyRow(root, ROW3, includeShift = true)
            }
            LAYER_NUMBERS -> {
                addKeyRow(root, ROW_NUM)
                addKeyRow(root, listOf(".", ",", "؟", "!", ":", ";", "ـ", "٪", "×", "÷"))
                addKeyRow(root, listOf("(", ")", "[", "]", "{", "}", "<", ">"))
            }
            LAYER_SYMBOLS -> {
                addKeyRow(root, ROW_SYM1)
                addKeyRow(root, ROW_SYM2)
                addKeyRow(root, ROW_SYM3)
            }
        }
        addBottomRow(root)
    }

    private fun addKeyRow(
        parent: LinearLayout,
        keys: List<String>,
        includeShift: Boolean = false
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(2, 2, 2, 2) }
        }
        if (includeShift) {
            row.addView(makeKey("⇧", 1.2f) {
                isShift = !isShift
                buildKeyboard(parent)
            })
        }
        keys.forEach { label ->
            row.addView(makeKey(label, 1f) { commitText(label) })
        }
        if (includeShift) {
            row.addView(makeKey("⌫", 1.2f) { deleteLast() })
        }
        parent.addView(row)
    }

    private fun addBottomRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(2, 4, 2, 2) }
        }

        // Layer switch
        val layerLabel = when (currentLayer) {
            LAYER_LETTERS -> "۱۲۳"
            LAYER_NUMBERS -> "#+="
            else -> "فا"
        }
        row.addView(makeKey(layerLabel, 1.3f) {
            currentLayer = (currentLayer + 1) % 3
            buildKeyboard(parent)
        })

        row.addView(makeKey(",", 0.8f) { commitText("،") })
        row.addView(makeKey(".", 0.8f) { commitText(".") })

        // Space with long-press for voice
        val space = makeKey("فاصله", 3.5f) { commitText(" ") }
        space.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    if (event.eventTime - event.downTime < LONG_PRESS_MS) {
                        // short tap = space
                        commitText(" ")
                        playClick()
                    }
                    true
                }
                else -> false
            }
        }
        // Override click to avoid double
        space.setOnClickListener(null)
        row.addView(space)

        row.addView(makeKey("↵", 1.3f) {
            val ic = currentInputConnection
            ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        })

        parent.addView(row)
    }

    private val longPressRunnable = Runnable {
        if (!isListening) startVoice() else stopVoice()
    }

    private fun makeKey(label: String, weight: Float, onTap: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = if (label.length > 2) 14f else 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2D2D2D.toInt())
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, 96, weight).apply {
                setMargins(3, 3, 3, 3)
            }
            // Single-tap that works for TalkBack and sighted users
            setOnClickListener {
                onTap()
                playClick()
            }
            // Accessibility
            contentDescription = when (label) {
                "فاصله" -> "فاصله — فشار طولانی برای تایپ صوتی"
                "⌫" -> "پاک کردن"
                "↵" -> "ورود"
                "⇧" -> "شیفت"
                else -> label
            }
        }
    }

    private fun commitText(text: String) {
        val ic: InputConnection = currentInputConnection ?: return
        val out = if (isShift && text.length == 1) text.uppercase() else text
        ic.commitText(out, 1)
        if (isShift) {
            isShift = false
            keyboardRoot?.let { buildKeyboard(it) }
        }
    }

    private fun deleteLast() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun playClick() {
        val prefs = getSharedPreferences("hamdel_stt", MODE_PRIVATE)
        if (prefs.getBoolean("key_sound", true)) {
            try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 30) } catch (_: Exception) {}
        }
        if (prefs.getBoolean("key_vibe", true)) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(15)
                }
            } catch (_: Exception) {}
        }
    }

    private fun startVoice() {
        if (isListening) return
        isListening = true
        pcmChunks.clear()
        statusView?.text = "در حال گوش دادن… (دوباره فشار طولانی برای توقف)"
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 80)
        } catch (_: Exception) {}

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            statusView?.text = "خطا در میکروفون"
            isListening = false
            return
        }
        audioRecord?.startRecording()

        listenJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(SAMPLE_RATE / 5)
            while (isActive && isListening) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (n > 0) {
                    pcmChunks.add(buf.copyOf(n))
                }
            }
        }
    }

    private fun stopVoice() {
        synchronized(stopLock) {
            if (!isListening) return
            isListening = false
            listenJob?.cancel()
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}
            audioRecord = null
        }
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 60)
        } catch (_: Exception) {}
        statusView?.text = "در حال تشخیص…"

        scope.launch(Dispatchers.IO) {
            val all = pcmChunks.flatMap { it.toList() }.toShortArray()
            pcmChunks.clear()
            var text = ""
            try {
                // Primary: Whisper base forced to fa
                if (WhisperEngine.isReady(this@VoiceInputMethodService)) {
                    WhisperEngine.load(this@VoiceInputMethodService, "fa")
                    text = WhisperEngine.transcribe(all, SAMPLE_RATE)
                }
                // Fallback Vosk
                if (text.length < 2 && VoskEngine.isAnyReady(this@VoiceInputMethodService)) {
                    VoskEngine.load(this@VoiceInputMethodService, "fa")
                    text = VoskEngine.transcribe(all, SAMPLE_RATE)
                }
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                if (text.isNotBlank()) {
                    currentInputConnection?.commitText(text + " ", 1)
                    statusView?.text = "✓ $text"
                } else {
                    statusView?.text = "چیزی تشخیص داده نشد — دوباره امتحان کنید"
                }
                mainHandler.postDelayed({
                    statusView?.text = "آفلاین تایپ — فشار طولانی فاصله = صوت"
                }, 2500)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Ensure models are ready in background
        scope.launch(Dispatchers.IO) {
            try {
                if (!WhisperEngine.isReady(this@VoiceInputMethodService)) {
                    WhisperEngine.ensureModel(this@VoiceInputMethodService)
                }
                WhisperEngine.load(this@VoiceInputMethodService, "fa")
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        stopVoice()
        try { toneGen?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
