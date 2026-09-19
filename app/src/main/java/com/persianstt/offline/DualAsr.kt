package com.persianstt.offline

import android.content.Context
import android.util.Log

/**
 * Voice typing path: **Vosk only** (stable Persian offline engine).
 * No Whisper / Qwen in the recognition path.
 */
object DualAsr {
    private const val TAG = "DualAsr"

    fun transcribe(context: Context, pcm16: ShortArray, sampleRate: Int): Pair<String, String> {
        if (pcm16.isEmpty() || pcm16.size < sampleRate / 10) {
            return "" to "empty"
        }
        return try {
            if (!VoskEngine.isReady(context)) {
                VoskEngine.ensureModel(context) {}
            }
            if (!VoskEngine.isReady(context)) {
                return "" to "no-model"
            }
            VoskEngine.load(context)
            var text = VoskEngine.transcribe(pcm16, sampleRate).trim()
            if (text.isBlank()) return "" to "Vosk"
            // light offline post-process only (no cloud, no heavy LLM rewrite)
            try {
                text = NumberNormalizer.normalize(text)
            } catch (_: Exception) {}
            try {
                text = PersianPostProcess.fix(text)
            } catch (_: Exception) {}
            try {
                val improved = OfflineVoiceAi.improve(context, text)
                if (improved.isNotBlank()) text = improved
            } catch (_: Exception) {}
            text to "Vosk"
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM", e)
            "" to "oom"
        } catch (e: Exception) {
            Log.e(TAG, "transcribe", e)
            "" to "err"
        }
    }
}
