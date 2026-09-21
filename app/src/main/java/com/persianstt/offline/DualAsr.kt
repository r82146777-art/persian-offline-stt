package com.persianstt.offline

import android.content.Context

/**
 * Hybrid STT:
 * 1) 16 kHz mono audio pipeline
 * 2) Free Vosk (full vocabulary)
 * 3) Dictionary only as soft correction (does not overwrite good engine words)
 */
object DualAsr {
    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.isEmpty()) return "" to "خالی"
        if (!VoskEngine.isReady(context)) return "" to "مدل نیست"

        val prepared = AudioPreprocessor.prepare(pcm, sampleRate)
        if (prepared.size < 16000 / 8) return "" to "کوتاه"

        var text = VoskEngine.transcribe(context, prepared, 16000)
        if (text.isBlank()) return "" to VoskEngine.lastError.ifBlank { "بدون‌متن" }

        text = HybridCorrector.improve(context, text)
        return text to "Vosk+دیکت"
    }
}
