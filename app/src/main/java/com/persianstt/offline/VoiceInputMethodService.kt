package com.persianstt.offline

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
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
 * Simple voice-only Input Method so the user can select this as a keyboard
 * and dictate into any app (WhatsApp, messages, etc.).
 * Uses the same offline Vosk engine. Model must already be downloaded via MainActivity.
 */
class VoiceInputMethodService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    @Volatile private var isListening = false

    private var statusView: TextView? = null
    private var micButton: Button? = null

    companion object {
        private const val SAMPLE_RATE = 16000
    }

    override fun onCreate() {
        super.onCreate()
        LibVosk.setLogLevel(LogLevel.WARNINGS)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_voice, null)
        statusView = view.findViewById(R.id.imeStatus)
        micButton = view.findViewById(R.id.imeMicButton)
        micButton?.setOnClickListener {
            if (isListening) stopListening() else startListening()
        }
        ensureModel()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        ensureModel()
    }

    private fun ensureModel() {
        if (model != null) {
            statusView?.text = getString(R.string.status_ready)
            micButton?.isEnabled = true
            return
        }
        statusView?.text = getString(R.string.status_loading)
        micButton?.isEnabled = false
        scope.launch(Dispatchers.IO) {
            try {
                // Prefer Persian model; fall back to English if present
                val base = File(filesDir, "vosk-models")
                val fa = File(base, "vosk-model-small-fa-0.42")
                val en = File(base, "vosk-model-small-en-us-0.15")
                val dir = when {
                    File(fa, "am/final.mdl").exists() -> fa
                    File(en, "am/final.mdl").exists() -> en
                    else -> null
                }
                if (dir == null) {
                    withContext(Dispatchers.Main) {
                        statusView?.text = getString(R.string.ime_need_model)
                        micButton?.isEnabled = false
                        Toast.makeText(this@VoiceInputMethodService, R.string.ime_need_model, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                model = Model(dir.absolutePath)
                withContext(Dispatchers.Main) {
                    statusView?.text = getString(R.string.status_ready)
                    micButton?.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusView?.text = "${getString(R.string.status_error)}: ${e.message}"
                    micButton?.isEnabled = false
                }
            }
        }
    }

    private fun startListening() {
        if (model == null) {
            Toast.makeText(this, R.string.ime_need_model, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
            // User must grant via MainActivity or system settings
            return
        }
        if (isListening) return

        try {
            val rec = Recognizer(model, SAMPLE_RATE.toFloat())
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
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
            recognizer = rec
            audioRecord = ar
            isListening = true
            micButton?.text = getString(R.string.btn_stop)
            statusView?.text = getString(R.string.status_listening)

            listenJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(4096)
                val sb = StringBuilder()
                while (isActive && isListening) {
                    val n = try { audioRecord?.read(buffer, 0, buffer.size) ?: -1 } catch (_: Exception) { -1 }
                    if (n > 0) {
                        val r = recognizer ?: break
                        try {
                            if (r.acceptWaveForm(buffer, n)) {
                                val text = NumberNormalizer.normalize(extractText(r.result))
                                if (text.isNotBlank()) {
                                    if (sb.isNotEmpty()) sb.append(" ")
                                    sb.append(text)
                                    withContext(Dispatchers.Main) {
                                        currentInputConnection?.commitText(text + " ", 1)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            statusView?.text = "${getString(R.string.status_error)}: ${e.message}"
            stopListening()
        }
    }

    private fun stopListening() {
        isListening = false
        try { listenJob?.cancel() } catch (_: Exception) {}
        listenJob = null
        try {
            audioRecord?.let {
                try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        audioRecord = null
        try {
            val final = recognizer?.finalResult
            if (final != null) {
                val text = NumberNormalizer.normalize(extractText(final))
                if (text.isNotBlank()) {
                    currentInputConnection?.commitText(text + " ", 1)
                }
            }
        } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        micButton?.text = getString(R.string.btn_mic)
        statusView?.text = getString(R.string.status_ready)
    }

    private fun extractText(json: String): String =
        try { JSONObject(json).optString("text", "").trim() } catch (_: Exception) { "" }

    override fun onDestroy() {
        stopListening()
        try { model?.close() } catch (_: Exception) {}
        model = null
        scope.cancel()
        super.onDestroy()
    }
}
