package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * Primary: Shenava (real offline Persian ASR model, sherpa-onnx) — not hand-built rules.
 * Fallback: Vosk small-fa if Shenava missing/OOM.
 * Light phrase cleanup after either engine.
 */
object DualAsr {
    private const val TAG = "DualAsr"

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 5
        val post = (sampleRate * 0.85).toInt()
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        if (pcm.isEmpty()) return "" to "خالی"
        if (pcm.size < sampleRate / 8) return "" to "کوتاه"

        val prepared = pad(AudioPreprocessor.prepare(pcm, 16000), 16000)
        var text = ""
        var engine = "none"
        var err = ""

        // 1) Real offline AI model: Shenava Persian
        try {
            if (ShenavaEngine.isReady(context)) {
                if (ShenavaEngine.load(context)) {
                    text = ShenavaEngine.transcribe(prepared, 16000)
                    if (text.isNotBlank()) engine = "Shenava"
                    else err = ShenavaEngine.lastError.ifBlank { "خروجی خالی" }
                } else {
                    err = ShenavaEngine.lastError.ifBlank { "بارگذاری Shenava ناموفق" }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "shenava", e)
            err = e.message ?: "خطای Shenava"
        }

        // 2) Fallback Vosk
        if (text.isBlank()) {
            try {
                if (VoskEngine.isReady(context) && VoskEngine.load(context)) {
                    text = VoskEngine.transcribe(prepared, 16000)
                    if (text.isNotBlank()) engine = "Vosk"
                    else if (err.isBlank()) err = VoskEngine.lastError.ifBlank { "بدون‌متن" }
                } else if (err.isBlank()) {
                    err = VoskEngine.lastError.ifBlank { "مدل نیست" }
                }
            } catch (e: Exception) {
                Log.e(TAG, "vosk", e)
                if (err.isBlank()) err = e.message ?: "خطای Vosk"
            }
        }

        if (text.isBlank()) return "" to err.ifBlank { "بدون‌متن" }

        // light known-phrase fix only (not a fake "AI")
        text = OfflineVoiceAi.improve(context, text)
        return text to engine
    }
}
