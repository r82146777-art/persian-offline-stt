package com.persianstt.offline

import android.content.Context
import android.util.Log

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

        val prepared = pad(AudioPreprocessor.prepare(pcm, sampleRate), sampleRate)
        var text = ""
        var err = ""
        try {
            if (!HamdelEngine.isReady(context)) {
                return "" to "مدل نیست"
            }
            val loaded = HamdelEngine.load(context)
            if (!loaded) {
                err = HamdelEngine.lastError.ifBlank { "بارگذاری ناموفق" }
                return "" to err
            }
            text = HamdelEngine.transcribe(prepared, sampleRate)
            if (text.isBlank()) {
                err = HamdelEngine.lastError.ifBlank { "بدون‌متن" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "transcribe", e)
            err = e.message ?: "خطا"
        }
        return if (text.isNotBlank()) text to "همدل" else "" to err.ifBlank { "none" }
    }
}
