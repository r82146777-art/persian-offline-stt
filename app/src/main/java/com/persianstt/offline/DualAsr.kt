package com.persianstt.offline

import android.content.Context
import android.util.Log
import org.vosk.Recognizer

/**
 * مسیر تشخیص: فقط Vosk + Grammar کاربر.
 * هیچ هوش مصنوعی بازنویسی‌کننده متن وجود ندارد.
 */
object DualAsr {
    private const val TAG = "DualAsr"

    fun transcribe(context: Context, pcm16: ShortArray, sampleRate: Int): Pair<String, String> {
        if (pcm16.isEmpty() || pcm16.size < sampleRate / 10) return "" to "empty"
        return try {
            if (!GrammarVoskEngine.isReady(context)) {
                GrammarVoskEngine.ensureModel(context) {}
            }
            if (!GrammarVoskEngine.isReady(context)) return "" to "no-model"
            GrammarVoskEngine.load(context)
            val words = DictStore.getWords(context)
            val rec: Recognizer = GrammarVoskEngine.createRecognizer(words)
            try {
                var off = 0
                val chunk = sampleRate / 2
                while (off < pcm16.size) {
                    val n = minOf(chunk, pcm16.size - off)
                    rec.acceptWaveForm(pcm16.copyOfRange(off, off + n), n)
                    off += n
                }
                val text = GrammarVoskEngine.extractText(rec.finalResult)
                text to "Vosk-Grammar"
            } finally {
                try { rec.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "transcribe", e)
            "" to "err"
        }
    }
}
