package com.persianstt.offline

import android.content.Context

/**
 * Stub — AI rewrite completely removed.
 * Keep class so any leftover references compile without pulling llama.
 */
object OfflineLlm {
    var lastError: String = "AI disabled"
    fun isReady(context: Context): Boolean = false
    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}) {}
    fun correctText(context: Context, text: String): String = text
    fun addEmojis(context: Context, text: String): String = text
    fun release() {}
}
