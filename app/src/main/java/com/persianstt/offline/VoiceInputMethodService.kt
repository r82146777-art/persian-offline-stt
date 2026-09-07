package com.persianstt.offline

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
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
import android.view.View
import android.view.inputmethod.EditorInfo
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
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Full keyboard IME (Persian + English + symbols) with offline voice.
 * - Normal tap = type
 * - Long-press SPACE or tap 🎤 = start/stop voice
 * - Sound + vibration feedback (settings)
 * - Never auto-starts listening
 */
class VoiceInputMethodService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    @Volatile private var isListening = false
    private val stopLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var keyboardView: KeyboardView? = null
    private var statusView: TextView? = null
    private var keyboardFa: Keyboard? = null
    private var keyboardEn: Keyboard? = null
    private var keyboardSymbols: Keyboard? = null
    private var currentIsFa = true
    private var isSymbols = false
    private var isShift = false

    private var prefs: SharedPreferences? = null
    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    // Long-press space detection
    private var spacePressed = false
    private var spaceLongTriggered = false
    private val longPressRunnable = Runnable {
        if (spacePressed && !isListening) {
            spaceLongTriggered = true
            startListening()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val KEYCODE_MIC = -101
        private const val KEYCODE_LANG = -100
        private const val KEYCODE_SHIFT = -1
        private const val KEYCODE_DELETE = -5
        private const val KEYCODE_SYMBOLS = -2
        private const val KEYCODE_SYMBOLS2 = -3
        private const val LONG_PRESS_MS = 450L
        private const val PREFS = "hamdel_stt"
        private const val KEY_SOUND = "key_sound"
        private const val KEY_VIBE = "key_vibe"
    }

    override fun onCreate() {
        super.onCreate()
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_SYSTEM, 60)
        } catch (_: Exception) {}
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = view.findViewById(R.id.keyboardView)
        statusView = view.findViewById(R.id.imeStatus)

        keyboardFa = Keyboard(this, R.xml.qwerty_fa)
        keyboardEn = Keyboard(this, R.xml.qwerty_en)
        keyboardSymbols = Keyboard(this, R.xml.symbols)

        keyboardView?.keyboard = if (currentIsFa) keyboardFa else keyboardEn
        keyboardView?.setOnKeyboardActionListener(this)
        keyboardView?.isPreviewEnabled = false

        // Never auto-start
        isListening = false
        statusView?.visibility = View.GONE

        ensureModel()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Ensure we do not start listening automatically
        if (isListening) stopListening()
        ensureModel()
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

    // ==================== Feedback ====================

    private fun playClick() {
        if (prefs?.getBoolean(KEY_SOUND, true) != true) return
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 25)
        } catch (_: Exception) {}
    }

    private fun playVoiceStart() {
        if (prefs?.getBoolean(KEY_SOUND, true) != true) return
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (_: Exception) {}
    }

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

    // ==================== Keyboard handling ====================

    override fun onPress(primaryCode: Int) {
        if (primaryCode == 32) {
            spacePressed = true
            spaceLongTriggered = false
            mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
        }
        if (primaryCode != 32) playClick()
        if (primaryCode != KEYCODE_MIC && primaryCode != 32) vibe(18)
    }

    override fun onRelease(primaryCode: Int) {
        if (primaryCode == 32) {
            mainHandler.removeCallbacks(longPressRunnable)
            spacePressed = false
        }
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return

        // If long-press on space already started voice, do not insert space
        if (primaryCode == 32 && spaceLongTriggered) {
            spaceLongTriggered = false
            return
        }

        when (primaryCode) {
            KEYCODE_DELETE -> {
                ic.deleteSurroundingText(1, 0)
            }
            KEYCODE_SHIFT -> {
                isShift = !isShift
            }
            KEYCODE_LANG -> {
                if (isSymbols) {
                    isSymbols = false
                    keyboardView?.keyboard = if (currentIsFa) keyboardFa else keyboardEn
                } else {
                    currentIsFa = !currentIsFa
                    keyboardView?.keyboard = if (currentIsFa) keyboardFa else keyboardEn
                }
            }
            KEYCODE_MIC -> {
                if (isListening) stopListening() else startListening()
            }
            KEYCODE_SYMBOLS, KEYCODE_SYMBOLS2 -> {
                isSymbols = !isSymbols
                keyboardView?.keyboard = if (isSymbols) keyboardSymbols else {
                    if (currentIsFa) keyboardFa else keyboardEn
                }
            }
            10 -> {
                ic.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_ENTER
                    )
                )
                ic.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_ENTER
                    )
                )
            }
            32 -> {
                ic.commitText(" ", 1)
            }
            else -> {
                if (primaryCode > 0) {
                    var code = primaryCode
                    if (!currentIsFa && !isSymbols && isShift && code in 97..122) {
                        code -= 32
                    }
                    ic.commitText(code.toChar().toString(), 1)
                    if (isShift) isShift = false
                }
            }
        }
    }

    override fun onText(text: CharSequence?) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}

    // ==================== Voice recognition ====================

    private fun ensureModel() {
        if (model != null) return
        scope.launch(Dispatchers.IO) {
            try {
                val base = File(applicationContext.filesDir, "vosk-models")
                val fa = File(base, "vosk-model-small-fa-0.42")
                val en = File(base, "vosk-model-small-en-us-0.15")
                val dir = when {
                    File(fa, "am/final.mdl").exists() -> fa
                    File(en, "am/final.mdl").exists() -> en
                    else -> null
                }
                if (dir == null) {
                    withContext(Dispatchers.Main) {
                        statusView?.visibility = View.VISIBLE
                        statusView?.text = getString(R.string.ime_need_model)
                    }
                    return@launch
                }
                val m = Model(dir.absolutePath)
                withContext(Dispatchers.Main) {
                    model = m
                    if (!isListening) statusView?.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusView?.visibility = View.VISIBLE
                    statusView?.text = "خطا مدل: ${e.message}"
                }
            }
        }
    }

    private fun startListening() {
        if (model == null) {
            Toast.makeText(this, R.string.ime_need_model, Toast.LENGTH_SHORT).show()
            ensureModel()
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
            val rec = Recognizer(model, SAMPLE_RATE.toFloat())
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
                recognizer = rec
                audioRecord = ar
                isListening = true
            }

            playVoiceStart()
            vibe(40)

            statusView?.visibility = View.VISIBLE
            statusView?.text = "🎤 در حال شنیدن… برای توقف دکمه میکروفون را بزنید"

            listenJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(4096)
                while (isActive && isListening) {
                    val n = try {
                        synchronized(stopLock) { audioRecord?.read(buffer, 0, buffer.size) ?: -1 }
                    } catch (_: Exception) { -1 }
                    if (n > 0) {
                        val r = synchronized(stopLock) { recognizer } ?: break
                        try {
                            if (r.acceptWaveForm(buffer, n)) {
                                val text = NumberNormalizer.normalize(extractText(r.result))
                                if (text.isNotBlank()) {
                                    withContext(Dispatchers.Main) {
                                        try {
                                            currentInputConnection?.commitText("$text ", 1)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        } catch (_: Exception) {}
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

                try {
                    val finalJson = try { recognizer?.finalResult } catch (_: Exception) { null }
                    if (finalJson != null) {
                        val text = NumberNormalizer.normalize(extractText(finalJson))
                        if (text.isNotBlank()) {
                            try { currentInputConnection?.commitText("$text ", 1) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}

                try { recognizer?.close() } catch (_: Exception) {}
                recognizer = null
            }
            try {
                statusView?.visibility = View.GONE
                vibe(20)
            } catch (_: Exception) {}
        }
    }

    private fun extractText(json: String): String =
        try { JSONObject(json).optString("text", "").trim() } catch (_: Exception) { "" }

    override fun onDestroy() {
        try { mainHandler.removeCallbacks(longPressRunnable) } catch (_: Exception) {}
        stopListening()
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
        scope.cancel()
        super.onDestroy()
    }
}
