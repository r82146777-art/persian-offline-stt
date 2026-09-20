package com.persianstt.offline

import android.content.Context

/** Vosk-only offline STT with grammar dictionary. */
object DualAsr {
    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.isEmpty()) return "" to "خالی"
        if (!VoskEngine.isReady(context)) return "" to "مدل نیست"
        val text = VoskEngine.transcribe(context, pcm, sampleRate)
        if (text.isBlank()) return "" to VoskEngine.lastError.ifBlank { "بدون‌متن" }
        return text to "Vosk+Grammar"
    }
}
