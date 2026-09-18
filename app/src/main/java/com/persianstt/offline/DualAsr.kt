package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * Conclusion from guidance docs:
 * - Keep light mobile ASR (Vosk); boost with LM/SymSpell post
 * - Optional Sherpa/Shenava if already on device
 * - No multi-GB models
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

        SymSpell.ensureLoaded(context)
        OfflineAi.ensure(context)

        val prepared = pad(AudioPreprocessor.prepare(pcm, 16000), 16000)
        var text = ""
        var engine = "none"
        var err = ""

        // 1) Vosk primary (stable)
        try {
            if (VoskEngine.isReady(context) && VoskEngine.load(context)) {
                text = VoskEngine.transcribe(prepared, 16000)
                if (text.isNotBlank()) engine = "Vosk"
                else err = VoskEngine.lastError
            } else {
                err = VoskEngine.lastError.ifBlank { "مدل Vosk نیست" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "vosk", e)
            err = e.message ?: "خطای Vosk"
        }

        // 2) Shenava only if Vosk empty and model already present (no forced download)
        if (text.isBlank()) {
            try {
                if (ShenavaEngine.isReady(context)) {
                    if (ShenavaEngine.load(context)) {
                        val t2 = ShenavaEngine.transcribe(prepared, 16000)
                        if (t2.isNotBlank()) {
                            text = t2
                            engine = "Shenava"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "shenava", e)
            }
        }

        if (text.isBlank()) return "" to err.ifBlank { "بدون‌متن" }

        // 3) Language-model style post: hard phrases + SymSpell + light normalize
        text = OfflineAi.correctText(context, text)
        if (text.isBlank()) {
            text = NumberNormalizer.normalize(PersianPostProcess.fix(text))
        }
        return text to engine
    }
}
