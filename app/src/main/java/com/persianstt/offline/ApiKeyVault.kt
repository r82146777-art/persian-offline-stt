package com.persianstt.offline

/** Obfuscated Gemini API key (client-side only). */
internal object ApiKeyVault {
    private val parts = intArrayOf(
        40, 88, 97, 66, 109, 81, 91, 1, 53, 68, 58, 90, 7, 78, 126, 42, 99, 21, 66, 66,
        48, 89, 31, 122, 96, 27, 62, 61, 119, 62, 94, 122, 63, 97, 106, 61, 74, 29, 53, 62,
        42, 61, 120, 113, 73, 54, 78, 60, 77, 90, 32, 93, 30
    )
    private val mask = intArrayOf(0x69, 0x09, 0x4F, 0x03, 0x0F)

    fun reveal(): String {
        val sb = StringBuilder(parts.size)
        for (i in parts.indices) sb.append((parts[i] xor mask[i % mask.size]).toChar())
        return sb.toString()
    }
}
