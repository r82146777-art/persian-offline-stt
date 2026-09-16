package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * Pipeline: AudioPreprocessor → Vosk (16kHz + optional grammar) → post-process.
 * Primary engine = original Vosk small-fa.
 */
object DualAsr {
    private const val TAG = "DualAsr"

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 5
        val post = sampleRate / 2
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.isEmpty()) return "" to "خالی"
        if (pcm.size < sampleRate / 8) return "" to "کوتاه"

        val prepared = pad(AudioPreprocessor.prepare(pcm, 16000), 16000)
        var text = ""
        var err = ""
        try {
            if (!VoskEngine.isReady(context)) return "" to "مدل نیست"
            if (!VoskEngine.load(context)) {
                return "" to VoskEngine.lastError.ifBlank { "بارگذاری ناموفق" }
            }
            text = VoskEngine.transcribe(prepared, 16000)
            if (text.isNotBlank()) {
                text = OfflineAi.correctText(context, text)
            } else {
                err = VoskEngine.lastError.ifBlank { "بدون‌متن" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "transcribe", e)
            err = e.message ?: "خطا"
        }
        return if (text.isNotBlank()) text to "Vosk" else "" to err.ifBlank { "none" }
    }
}
