package com.persianstt.offline

import android.content.Context

/** Stub — llama package removed for minSdk 21 / stability. Use OfflineAi / HybridCorrector. */
object OfflineLlm {
    var lastError: String = ""
    fun isReady(context: Context): Boolean = false
    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {
        onProgress(100)
        lastError = "LLM اختیاری غیرفعال"
    }
    suspend fun ensureLoaded(context: Context): Boolean = false
    suspend fun correctText(context: Context, input: String): String = ""
    suspend fun typeFromSpeech(context: Context, input: String): String = input
    suspend fun addEmojis(context: Context, input: String): String = ""
    fun release() {}
}
