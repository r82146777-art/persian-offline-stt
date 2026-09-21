package com.persianstt.offline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Distinct keyboard / UI sounds (synthesized — no external assets).
 * 0 Samsung click, 1 Google soft, 2 Apple click, 3 Camera shutter,
 * 4 Guitar, 5 Piano, 6 Pop, 7 Ding (end-of-speech)
 */
object KeySoundPlayer {

    const val EFFECT_SAMSUNG = 0
    const val EFFECT_GOOGLE = 1
    const val EFFECT_APPLE = 2
    const val EFFECT_CAMERA = 3
    const val EFFECT_GUITAR = 4
    const val EFFECT_PIANO = 5
    const val EFFECT_POP = 6
    const val EFFECT_DING = 7

    val EFFECT_NAMES = arrayOf(
        "کلیک سامسونگ",
        "کلیک گوگل",
        "کلیک اپل",
        "شاتر دوربین",
        "گیتار",
        "پیانو",
        "پاپ",
        "دینگ پایان گفتار"
    )

    private const val SR = 22050

    fun play(context: Context, effect: Int, volumePercent: Int) {
        val vol = (volumePercent.coerceIn(5, 100) / 100f)
        Thread {
            try {
                val samples = when (effect.coerceIn(0, EFFECT_NAMES.size - 1)) {
                    EFFECT_GOOGLE -> synthGoogle()
                    EFFECT_APPLE -> synthApple()
                    EFFECT_CAMERA -> synthCamera()
                    EFFECT_GUITAR -> synthGuitar()
                    EFFECT_PIANO -> synthPiano()
                    EFFECT_POP -> synthPop()
                    EFFECT_DING -> synthDing()
                    else -> synthSamsung()
                }
                playPcm(samples, vol)
            } catch (_: Exception) {
                try {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent.coerceIn(10, 100))
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
                    tg.release()
                } catch (_: Exception) {}
            }
        }.start()
    }

    fun playDing(volumePercent: Int = 70) {
        Thread {
            try {
                playPcm(synthDing(), volumePercent.coerceIn(5, 100) / 100f)
            } catch (_: Exception) {
                try {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
                    tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                    tg.release()
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun playPcm(samples: ShortArray, vol: Float) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        val scaled = ShortArray(samples.size)
        for (i in samples.indices) {
            scaled[i] = (samples[i] * vol).toInt().coerceIn(-32768, 32767).toShort()
        }
        track.write(scaled, 0, scaled.size)
        track.play()
        val ms = (samples.size * 1000L / SR) + 40
        try { Thread.sleep(ms) } catch (_: Exception) {}
        try { track.stop(); track.release() } catch (_: Exception) {}
    }

    /** Sharp mechanical Samsung-like click */
    private fun synthSamsung(): ShortArray {
        val n = (SR * 0.035).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 85.0)
            val sig = sin(2 * PI * 2100 * t) * 0.7 + sin(2 * PI * 4200 * t) * 0.3
            out[i] = (sig * env * 24000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Softer Google Gboard-like tick */
    private fun synthGoogle(): ShortArray {
        val n = (SR * 0.028).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 110.0)
            val sig = sin(2 * PI * 1600 * t) * 0.55 + sin(2 * PI * 3200 * t) * 0.25
            out[i] = (sig * env * 18000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Crisp Apple keyboard click */
    private fun synthApple(): ShortArray {
        val n = (SR * 0.04).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 70.0)
            val sig = sin(2 * PI * 2800 * t) * 0.5 + sin(2 * PI * 5600 * t) * 0.2 +
                sin(2 * PI * 900 * t) * 0.15
            out[i] = (sig * env * 20000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun synthCamera(): ShortArray {
        val n = (SR * 0.1).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env1 = exp(-t * 90.0)
            val env2 = if (t > 0.045) exp(-(t - 0.045) * 75.0) else 0.0
            val sig = sin(2 * PI * 1500 * t) * 0.55 + sin(2 * PI * 2800 * t) * 0.45
            val env = max(env1 * 0.75, env2)
            out[i] = (sig * env * 26000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun synthGuitar(): ShortArray {
        val n = (SR * 0.14).toInt()
        val out = ShortArray(n)
        val f0 = 196.0 // G3
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 11.0)
            val sig = sin(2 * PI * f0 * t) * 0.5 +
                sin(2 * PI * f0 * 2 * t) * 0.28 +
                sin(2 * PI * f0 * 3 * t) * 0.12 +
                sin(2 * PI * f0 * 4 * t) * 0.05
            out[i] = (sig * env * 23000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun synthPiano(): ShortArray {
        val n = (SR * 0.16).toInt()
        val out = ShortArray(n)
        val f0 = 523.25 // C5
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 9.0) * (1.0 - t * 2.0).coerceAtLeast(0.0)
            val sig = sin(2 * PI * f0 * t) * 0.55 +
                sin(2 * PI * f0 * 2 * t) * 0.22 +
                sin(2 * PI * f0 * 3 * t) * 0.1
            out[i] = (sig * env * 22000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun synthPop(): ShortArray {
        val n = (SR * 0.05).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 45.0)
            val freq = 420.0 * exp(-t * 22.0)
            val sig = sin(2 * PI * freq * t)
            out[i] = (sig * env * 21000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Soft end-of-speech ding (Google-like) */
    private fun synthDing(): ShortArray {
        val n = (SR * 0.18).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 8.0)
            val sig = sin(2 * PI * 880 * t) * 0.55 + sin(2 * PI * 1320 * t) * 0.25
            out[i] = (sig * env * 18000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
