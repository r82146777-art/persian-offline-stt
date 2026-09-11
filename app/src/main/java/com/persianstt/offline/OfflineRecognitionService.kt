package com.persianstt.offline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * System-level offline RecognitionService — selectable as voice input / dictation
 * (TalkBack, system dictation, other apps) like Google Voice Typing.
 */
class OfflineRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var listening = false
    private val chunks = CopyOnWriteArrayList<ShortArray>()

    companion object {
        private const val SR = 16000
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "offline_dictation"
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        if (listening) {
            safeError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        // 1) Mic permission — system may show "permissions not granted"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            safeError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            // Open app so user can grant
            try {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("request_mic", true)
                }
                startActivity(i)
            } catch (_: Exception) {}
            return
        }

        listening = true
        chunks.clear()

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                // 2) Ensure model is ready (download only if already partially present;
                //    do not start huge download from system dictation UI)
                if (!ShenavaEngine.isReady(this@OfflineRecognitionService)) {
                    try {
                        ShenavaEngine.ensureModel(this@OfflineRecognitionService) {}
                        ShenavaEngine.load(this@OfflineRecognitionService)
                    } catch (_: Exception) {}
                } else {
                    try { ShenavaEngine.load(this@OfflineRecognitionService) } catch (_: Exception) {}
                }
                if (!ShenavaEngine.isReady(this@OfflineRecognitionService) &&
                    !VoskEngine.isAnyReady(this@OfflineRecognitionService)
                ) {
                    listening = false
                    withContext(Dispatchers.Main) {
                        safeError(listener, SpeechRecognizer.ERROR_CLIENT)
                    }
                    return@launch
                }

                // 3) Foreground notification helps OEM permission / mic access
                startFg()

                withContext(Dispatchers.Main) {
                    try { listener.readyForSpeech(Bundle()) } catch (_: RemoteException) {}
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = (minBuf * 2).coerceAtLeast(SR)

                // Prefer VOICE_RECOGNITION, fall back to MIC
                audioRecord = buildRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufSize)
                    ?: buildRecorder(MediaRecorder.AudioSource.MIC, bufSize)

                if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    listening = false
                    stopFg()
                    withContext(Dispatchers.Main) {
                        safeError(listener, SpeechRecognizer.ERROR_AUDIO)
                    }
                    return@launch
                }

                try {
                    audioRecord?.startRecording()
                } catch (se: SecurityException) {
                    listening = false
                    stopFg()
                    withContext(Dispatchers.Main) {
                        safeError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    try { listener.beginningOfSpeech() } catch (_: Exception) {}
                }

                val buf = ShortArray(SR / 5)
                val start = System.currentTimeMillis()
                var lastLoud = start
                var spoke = false
                while (isActive && listening) {
                    val n = try {
                        audioRecord?.read(buf, 0, buf.size) ?: -1
                    } catch (_: SecurityException) {
                        -1
                    }
                    if (n > 0) {
                        chunks.add(buf.copyOf(n))
                        var energy = 0L
                        for (i in 0 until n) energy += kotlin.math.abs(buf[i].toInt())
                        val avg = energy / n
                        val now = System.currentTimeMillis()
                        if (avg > 400) {
                            lastLoud = now
                            spoke = true
                        }
                        // end after 1.8s silence once user spoke, or 30s max
                        val silence = now - lastLoud
                        if ((spoke && silence > 1800 && chunks.sumOf { it.size } > SR) ||
                            now - start > 30_000
                        ) {
                            break
                        }
                    } else if (n < 0) {
                        break
                    }
                }

                stopRecordingInternal()
                stopFg()
                withContext(Dispatchers.Main) {
                    try { listener.endOfSpeech() } catch (_: Exception) {}
                }

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

                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        val bundle = Bundle()
                        bundle.putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            arrayListOf(text)
                        )
                        try { listener.results(bundle) } catch (_: Exception) {}
                    } else {
                        safeError(listener, SpeechRecognizer.ERROR_NO_MATCH)
                    }
                }
            } catch (e: SecurityException) {
                listening = false
                stopRecordingInternal()
                stopFg()
                withContext(Dispatchers.Main) {
                    safeError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                }
            } catch (_: Exception) {
                listening = false
                stopRecordingInternal()
                stopFg()
                withContext(Dispatchers.Main) {
                    safeError(listener, SpeechRecognizer.ERROR_CLIENT)
                }
            }
        }
    }

    private fun buildRecorder(source: Int, bufSize: Int): AudioRecord? {
        return try {
            val ar = AudioRecord(
                source, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) ar else {
                ar.release()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeError(listener: Callback, code: Int) {
        try { listener.error(code) } catch (_: Exception) {}
    }

    private fun startFg() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                val ch = NotificationChannel(
                    CHANNEL_ID, "دیکته آفلاین",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm?.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("تایپ صوتی آفلاین")
                .setContentText("در حال شنیدن…")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {}
    }

    private fun stopFg() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
    }

    override fun onStopListening(listener: Callback?) {
        listening = false
    }

    override fun onCancel(listener: Callback?) {
        listening = false
        stopRecordingInternal()
        stopFg()
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
        stopFg()
        recordJob?.cancel()
        super.onDestroy()
    }
}
