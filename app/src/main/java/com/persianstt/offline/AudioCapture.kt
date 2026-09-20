package com.persianstt.offline

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.min

/**
 * Capture mic as mono 16-bit and guarantee 16 kHz for STT models.
 * If device won't open 16 kHz cleanly, open 44.1/48 kHz and resample.
 * Fixes Whisper/Vosk hallucinations from wrong sample rate.
 */
object AudioCapture {
    private const val TAG = "AudioCapture"
    const val TARGET_RATE = 16000

    data class Session(
        val record: AudioRecord,
        val captureRate: Int,
        val bufferShorts: Int
    )

    /** Preferred rates: exact 16k first, then common device rates. */
    private val TRY_RATES = intArrayOf(16000, 44100, 48000, 22050, 32000)

    fun open(): Session? {
        for (rate in TRY_RATES) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) continue
            val bufSize = (minBuf * 2).coerceAtLeast(rate / 5 * 2) // ~200ms+
            try {
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "opened mono PCM16 @ $rate Hz (target $TARGET_RATE)")
                    return Session(ar, rate, rate / 10) // ~100ms frames
                }
                ar.release()
            } catch (e: Exception) {
                Log.w(TAG, "open $rate fail", e)
            }
        }
        // last resort: default MIC
        try {
            val rate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                return Session(ar, rate, rate / 10)
            }
            ar.release()
        } catch (_: Exception) {}
        return null
    }

    /** Linear resample mono PCM16 → 16 kHz. */
    fun to16k(pcm: ShortArray, fromRate: Int): ShortArray {
        if (pcm.isEmpty()) return pcm
        if (fromRate == TARGET_RATE) return pcm
        if (fromRate <= 0) return pcm
        val outLen = ((pcm.size.toLong() * TARGET_RATE) / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val ratio = fromRate.toDouble() / TARGET_RATE
        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, pcm.size - 1)
            val i1 = min(i0 + 1, pcm.size - 1)
            val frac = src - i0
            val v = pcm[i0] * (1.0 - frac) + pcm[i1] * frac
            out[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun close(session: Session?) {
        try {
            session?.record?.stop()
        } catch (_: Exception) {}
        try {
            session?.record?.release()
        } catch (_: Exception) {}
    }
}
