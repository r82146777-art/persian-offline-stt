package com.persianstt.offline

import android.content.Context

/** Disabled stub — not used. Whisper + Vosk are the active engines. */
object ShenavaEngine {
    fun isReady(context: Context): Boolean = false
    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) { onProgress(100) }
    fun load(context: Context): Boolean = false
    fun release() {}
    fun transcribe(pcm16: ShortArray, sampleRate: Int = 16000): String = ""
}
