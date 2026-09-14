package com.persianstt.offline

/**
 * Key is not stored as a plain consecutive string.
 * Client-side obfuscation only — not absolute secrecy.
 */
internal object ApiKeyVault {
    private val parts = intArrayOf(
        26, 98, 98, 102, 108, 15, 108, 41, 101, 63, 11, 57, 44, 96, 54, 93, 56, 126, 101, 110,
        80, 62, 126, 54, 57, 93, 48, 120, 102, 63, 12, 109, 122, 51, 62
    )
    private val mask = intArrayOf(0x69, 0x09, 0x4F, 0x03, 0x0F)

    fun reveal(): String {
        val sb = StringBuilder(parts.size)
        for (i in parts.indices) {
            sb.append((parts[i] xor mask[i % mask.size]).toChar())
        }
        return sb.toString()
    }
}
