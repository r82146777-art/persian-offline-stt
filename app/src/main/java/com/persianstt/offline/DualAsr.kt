package com.persianstt.offline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Voice path connected to offline Qwen for final typing:
 * audio → Whisper (hear) → Qwen (write final text into the field).
 * User-facing engine name: Qwen.
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

        if (!WhisperEngine.isReady(context)) {
            return "" to "مدل شنیدار هنوز دانلود نشده"
        }

        val prepared = pad(AudioPreprocessor.prepare(pcm, 16000), 16000)
        var heard = ""
        try {
            if (!WhisperEngine.load(context, "fa")) {
                return "" to WhisperEngine.lastError.ifBlank { "بارگذاری شنیدار ناموفق" }
            }
            heard = WhisperEngine.transcribe(prepared, 16000)
            if (heard.isBlank()) {
                WhisperEngine.load(context, "en")
                heard = WhisperEngine.transcribe(prepared, 16000)
                WhisperEngine.load(context, "fa")
            }
        } catch (e: Exception) {
            Log.e(TAG, "hear", e)
            return "" to (e.message ?: "خطای شنیدار")
        }

        if (heard.isBlank()) return "" to "چیزی شنیده نشد"

        // Qwen writes the final typed text
        var typed = heard
        try {
            if (OfflineLlm.isReady(context)) {
                val q = runBlocking { OfflineLlm.typeFromSpeech(context, heard) }
                if (q.isNotBlank()) typed = q
            }
        } catch (e: Exception) {
            Log.w(TAG, "qwen type", e)
        }
        // light phrase fix if Qwen skipped
        typed = OfflineVoiceAi.improve(context, typed).ifBlank { typed }

        return typed to "Qwen"
    }
}
