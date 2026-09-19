package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * Only offline neural AI speech model: Whisper (OpenAI architecture via sherpa-onnx).
 * No Vosk / Shenava path.
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
            return "" to "مدل AI دانلود نشده"
        }

        val prepared = pad(AudioPreprocessor.prepare(pcm, 16000), 16000)
        return try {
            if (!WhisperEngine.load(context, "fa")) {
                return "" to WhisperEngine.lastError.ifBlank { "بارگذاری AI ناموفق" }
            }
            var text = WhisperEngine.transcribe(prepared, 16000)
            if (text.isBlank()) {
                // retry with en then still fa load
                WhisperEngine.load(context, "en")
                text = WhisperEngine.transcribe(prepared, 16000)
                WhisperEngine.load(context, "fa")
            }
            if (text.isBlank()) return "" to WhisperEngine.lastError.ifBlank { "بدون‌متن" }
            // light phrase cleanup only
            text = OfflineVoiceAi.improve(context, text)
            text to "Whisper-AI"
        } catch (e: Exception) {
            Log.e(TAG, "whisper", e)
            "" to (e.message ?: "خطای AI")
        }
    }
}
