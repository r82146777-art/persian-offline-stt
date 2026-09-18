package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * Offline voice pipeline:
 * Audio → Vosk → OfflineVoiceAi (SymSpell + phrase LM)
 */
object DualAsr {
    private const val TAG = "DualAsr"

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 5
        val post = (sampleRate * 0.9).toInt()
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.isEmpty()) return "" to "خالی"
        if (pcm.size < sampleRate / 8) return "" to "کوتاه"

        try {
            SymSpell.ensureLoadedBlocking(context, 2500)
            OfflineAi.ensure(context)
        } catch (_: Exception) {}

        val prepared = pad(AudioPreprocessor.prepare(pcm, 16000), 16000)
        var text = ""
        var err = ""

        try {
            if (!VoskEngine.isReady(context)) return "" to "مدل نیست"
            if (!VoskEngine.load(context)) return "" to VoskEngine.lastError.ifBlank { "بارگذاری ناموفق" }
            text = VoskEngine.transcribe(prepared, 16000)
            if (text.isBlank()) err = VoskEngine.lastError.ifBlank { "بدون‌متن" }
        } catch (e: Exception) {
            Log.e(TAG, "vosk", e)
            err = e.message ?: "خطای Vosk"
        }

        if (text.isBlank()) return "" to err.ifBlank { "بدون‌متن" }

        // Offline AI layer — always on for voice typing
        val improved = try {
            OfflineVoiceAi.improve(context, text)
        } catch (e: Exception) {
            Log.e(TAG, "offline ai", e)
            text
        }

        val out = improved.ifBlank { text }
        return out to "Vosk+AI"
    }
}
