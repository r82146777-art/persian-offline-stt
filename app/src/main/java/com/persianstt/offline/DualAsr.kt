package com.persianstt.offline

import android.content.Context

/** Vosk + lexicon + user dictionary auto-correction. */
object DualAsr {

    fun pad(pcm: ShortArray, sampleRate: Int = 16000): ShortArray {
        val pre = sampleRate / 5
        val post = sampleRate / 3
        val out = ShortArray(pre + pcm.size + post)
        System.arraycopy(pcm, 0, out, pre, pcm.size)
        return out
    }

    fun transcribe(context: Context, pcm: ShortArray, sampleRate: Int = 16000): Pair<String, String> {
        val prepared = pad(AudioPreprocessor.prepare(pcm, sampleRate), sampleRate)
        var text = ""
        try {
            if (VoskEngine.isReady(context)) {
                VoskEngine.load(context)
                text = VoskEngine.transcribe(prepared, sampleRate)
            }
        } catch (_: Exception) {}
        if (text.isNotBlank()) {
            text = LexiconCorrector.autoCorrect(context, text)
        }
        return text to if (text.isNotBlank()) "Vosk+Lex" else "none"
    }
}
