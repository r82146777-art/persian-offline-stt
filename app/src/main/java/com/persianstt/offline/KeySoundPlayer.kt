package com.persianstt.offline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.AudioManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Distinct key-click sounds synthesized on the fly (no asset files needed).
 * Effects: classic click, soft tick, pop, camera shutter, guitar pluck.
 */
object KeySoundPlayer {

    const val EFFECT_CLICK = 0
    const val EFFECT_TICK = 1
    const val EFFECT_POP = 2
    const val EFFECT_CAMERA = 3
    const val EFFECT_GUITAR = 4

    private const val SR = 22050

    fun play(context: Context, effect: Int, volumePercent: Int) {
        val vol = (volumePercent.coerceIn(5, 100) / 100f)
        Thread {
            try {
                val samples = when (effect) {
                    EFFECT_TICK -> synthTick()
                    EFFECT_POP -> synthPop()
                    EFFECT_CAMERA -> synthCamera()
                    EFFECT_GUITAR -> synthGuitar()
                    else -> synthClick()
                }
                playPcm(samples, vol)
            } catch (_: Exception) {
                // last-resort fallback
                try {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent.coerceIn(10, 100))
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 25)
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
        // apply volume
        val scaled = ShortArray(samples.size)
        for (i in samples.indices) {
            scaled[i] = (samples[i] * vol).toInt().coerceIn(-32768, 32767).toShort()
        }
        track.write(scaled, 0, scaled.size)
        track.play()
        val ms = (samples.size * 1000L / SR) + 30
        try { Thread.sleep(ms) } catch (_: Exception) {}
        try { track.stop(); track.release() } catch (_: Exception) {}
    }

    /** Sharp mechanical click (Samsung-like) */
    private fun synthClick(): ShortArray {
        val n = (SR * 0.035).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 80.0)
            val sig = sin(2 * PI * 1800 * t) * 0.5 + sin(2 * PI * 3200 * t) * 0.3
            out[i] = (sig * env * 28000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Soft short tick */
    private fun synthTick(): ShortArray {
        val n = (SR * 0.02).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 150.0)
            val sig = sin(2 * PI * 2400 * t)
            out[i] = (sig * env * 20000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Soft low pop / bubble */
    private fun synthPop(): ShortArray {
        val n = (SR * 0.05).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 45.0)
            val freq = 400.0 * exp(-t * 25.0) // pitch drop
            val sig = sin(2 * PI * freq * t)
            out[i] = (sig * env * 22000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Camera shutter double-click */
    private fun synthCamera(): ShortArray {
        val n = (SR * 0.09).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            // two bursts
            val env1 = exp(-t * 90.0)
            val env2 = if (t > 0.04) exp(-(t - 0.04) * 70.0) else 0.0
            val sig = sin(2 * PI * 1500 * t) * 0.6 + sin(2 * PI * 2800 * t) * 0.4
            val env = maxOf(env1 * 0.7, env2)
            out[i] = (sig * env * 26000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Simple guitar-like pluck (karplus-ish decay) */
    private fun synthGuitar(): ShortArray {
        val n = (SR * 0.12).toInt()
        val out = ShortArray(n)
        val f0 = 220.0 // A3
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 12.0)
            // harmonics
            val sig = sin(2 * PI * f0 * t) * 0.5 +
                sin(2 * PI * f0 * 2 * t) * 0.25 +
                sin(2 * PI * f0 * 3 * t) * 0.12 +
                sin(2 * PI * f0 * 4 * t) * 0.06
            out[i] = (sig * env * 24000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
