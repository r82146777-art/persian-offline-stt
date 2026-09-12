package com.persianstt.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System RecognitionService — ONLY Shenava Koochik (no Vosk/Whisper).
 * Runs in a separate process to avoid OEM permission bugs.
 */
class OfflineRecognitionService : RecognitionService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioRecord: AudioRecord? = null
    private val listening = AtomicBoolean(false)
    private var worker: Thread? = null

    companion object {
        private const val SR = 16000
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        if (!listening.compareAndSet(false, true)) {
            err(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        // App must have mic — if missing, open MainActivity
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listening.set(false)
            err(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            try {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("request_mic", true)
                })
            } catch (_: Exception) {}
            return
        }

        worker = Thread {
            try {
                // Load ONLY Shenava
                try {
                    if (!ShenavaEngine.isReady(this)) ShenavaEngine.ensureModel(this) {}
                } catch (_: Exception) {}
                val loaded = try {
                    ShenavaEngine.load(this)
                } catch (_: Exception) {
                    false
                }
                if (!loaded && !ShenavaEngine.isReady(this)) {
                    listening.set(false)
                    err(listener, SpeechRecognizer.ERROR_CLIENT)
                    return@Thread
                }

                mainHandler.post {
                    try { listener.readyForSpeech(Bundle()) } catch (_: Exception) {}
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = (minBuf * 2).coerceAtLeast(SR * 2)

                var ar: AudioRecord? = null
                for (src in intArrayOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                )) {
                    try {
                        val candidate = AudioRecord(
                            src, SR, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufSize
                        )
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            ar = candidate
                            break
                        }
                        candidate.release()
                    } catch (_: SecurityException) {
                        listening.set(false)
                        err(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                        return@Thread
                    } catch (_: Exception) {}
                }

                if (ar == null) {
                    listening.set(false)
                    err(listener, SpeechRecognizer.ERROR_AUDIO)
                    return@Thread
                }
                audioRecord = ar

                try {
                    ar.startRecording()
                } catch (_: SecurityException) {
                    releaseRec()
                    listening.set(false)
                    err(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    return@Thread
                }

                mainHandler.post {
                    try { listener.beginningOfSpeech() } catch (_: Exception) {}
                }

                val chunks = ArrayList<ShortArray>()
                val buf = ShortArray(SR / 5)
                val start = System.currentTimeMillis()
                var lastLoud = start
                var spoke = false
                while (listening.get()) {
                    val n = try {
                        ar.read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) break
                    chunks.add(buf.copyOf(n))
                    var energy = 0L
                    for (i in 0 until n) energy += kotlin.math.abs(buf[i].toInt())
                    val avg = energy / n
                    val now = System.currentTimeMillis()
                    if (avg > 350) {
                        lastLoud = now
                        spoke = true
                    }
                    if ((spoke && now - lastLoud > 2000 && chunks.sumOf { it.size } > SR) ||
                        now - start > 30_000
                    ) break
                }

                releaseRec()
                mainHandler.post {
                    try { listener.endOfSpeech() } catch (_: Exception) {}
                }

                val total = chunks.sumOf { it.size }
                val all = ShortArray(total)
                var off = 0
                for (c in chunks) {
                    System.arraycopy(c, 0, all, off, c.size)
                    off += c.size
                }

                var text = ""
                try {
                    val pair = DualAsr.transcribe(this, all, SR)
                    text = pair.first
                } catch (_: Exception) {}

                listening.set(false)
                if (text.isNotBlank()) {
                    val bundle = Bundle()
                    bundle.putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(text)
                    )
                    mainHandler.post {
                        try { listener.results(bundle) } catch (_: Exception) {}
                    }
                } else {
                    err(listener, SpeechRecognizer.ERROR_NO_MATCH)
                }
            } catch (se: SecurityException) {
                releaseRec()
                listening.set(false)
                err(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            } catch (_: Exception) {
                releaseRec()
                listening.set(false)
                err(listener, SpeechRecognizer.ERROR_CLIENT)
            }
        }.also { it.start() }
    }

    private fun releaseRec() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun err(listener: Callback, code: Int) {
        mainHandler.post {
            try { listener.error(code) } catch (_: Exception) {}
        }
    }

    override fun onStopListening(listener: Callback?) {
        listening.set(false)
    }

    override fun onCancel(listener: Callback?) {
        listening.set(false)
        releaseRec()
        worker = null
        err(listener ?: return, SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onDestroy() {
        listening.set(false)
        releaseRec()
        super.onDestroy()
    }
}
