package com.persianstt.offline

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * System-level offline RecognitionService so this app can be selected as
 * voice input / dictation provider (like Google Voice Typing).
 */
class OfflineRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var listening = false
    private val chunks = CopyOnWriteArrayList<ShortArray>()

    companion object {
        private const val SR = 16000
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        if (listening) {
            try { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } catch (_: Exception) {}
            return
        }
        // Ensure model
        scope.launch(Dispatchers.IO) {
            try {
                if (!ShenavaEngine.isReady(this@OfflineRecognitionService)) {
                    ShenavaEngine.ensureModel(this@OfflineRecognitionService)
                }
                ShenavaEngine.load(this@OfflineRecognitionService)
            } catch (_: Exception) {}
        }
        try {
            listener.readyForSpeech(Bundle())
        } catch (_: RemoteException) {}

        listening = true
        chunks.clear()
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                (minBuf * 2).coerceAtLeast(SR)
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listening = false
                try { listener.error(SpeechRecognizer.ERROR_AUDIO) } catch (_: Exception) {}
                return
            }
            audioRecord?.startRecording()
            try { listener.beginningOfSpeech() } catch (_: Exception) {}
        } catch (_: Exception) {
            listening = false
            try { listener.error(SpeechRecognizer.ERROR_AUDIO) } catch (_: Exception) {}
            return
        }

        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(SR / 5)
            val start = System.currentTimeMillis()
            var speechEnded = false
            var silenceMs = 0L
            var lastLoud = start
            while (isActive && listening) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (n > 0) {
                    chunks.add(buf.copyOf(n))
                    // simple energy VAD for end-of-speech
                    var energy = 0L
                    for (i in 0 until n) energy += kotlin.math.abs(buf[i].toInt())
                    val avg = energy / n
                    val now = System.currentTimeMillis()
                    if (avg > 500) {
                        lastLoud = now
                        silenceMs = 0
                        if (!speechEnded) {
                            // still speaking
                        }
                    } else {
                        silenceMs = now - lastLoud
                    }
                    // auto-stop after 1.5s silence or 25s max
                    if ((silenceMs > 1500 && chunks.sumOf { it.size } > SR) || now - start > 25_000) {
                        speechEnded = true
                        break
                    }
                }
            }
            // finalize
            stopRecordingInternal()
            try { listener.endOfSpeech() } catch (_: Exception) {}
            val all = chunks.flatMap { it.toList() }.toShortArray()
            chunks.clear()
            var text = ""
            try {
                val prepared = AudioPreprocessor.prepare(all, SR)
                if (ShenavaEngine.isReady(this@OfflineRecognitionService)) {
                    ShenavaEngine.load(this@OfflineRecognitionService)
                    text = ShenavaEngine.transcribe(prepared, SR)
                }
                if (text.length < 2 && VoskEngine.isAnyReady(this@OfflineRecognitionService)) {
                    VoskEngine.load(this@OfflineRecognitionService, "fa")
                    text = VoskEngine.transcribe(prepared, SR)
                }
                if (text.isNotBlank()) {
                    text = PersianPostProcess.fix(NumberNormalizer.normalize(text))
                }
            } catch (_: Exception) {}
            if (text.isNotBlank()) {
                val bundle = Bundle()
                bundle.putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf(text)
                )
                try { listener.results(bundle) } catch (_: Exception) {}
            } else {
                try { listener.error(SpeechRecognizer.ERROR_NO_MATCH) } catch (_: Exception) {}
            }
        }
    }

    override fun onStopListening(listener: Callback?) {
        listening = false
        // recordJob will finalize
    }

    override fun onCancel(listener: Callback?) {
        listening = false
        stopRecordingInternal()
        recordJob?.cancel()
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
    }

    private fun stopRecordingInternal() {
        listening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    override fun onDestroy() {
        listening = false
        stopRecordingInternal()
        recordJob?.cancel()
        super.onDestroy()
    }
}
