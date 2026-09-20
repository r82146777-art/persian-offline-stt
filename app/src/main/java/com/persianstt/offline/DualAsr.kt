package com.persianstt.offline

import android.content.Context

/**
 * Vosk STT with forced 16 kHz mono pipeline.
 * Always resample + preprocess before model (fixes hallucination).
 */
object DualAsr {
    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.isEmpty()) return "" to "خالی"
        if (!VoskEngine.isReady(context)) return "" to "مدل نیست"

        val prepared = AudioPreprocessor.prepare(pcm, sampleRate)
        if (prepared.size < 16000 / 8) return "" to "کوتاه"

        val text = VoskEngine.transcribe(context, prepared, 16000)
        if (text.isBlank()) return "" to VoskEngine.lastError.ifBlank { "بدون‌متن" }
        return text to "Vosk+16k"
    }
}
