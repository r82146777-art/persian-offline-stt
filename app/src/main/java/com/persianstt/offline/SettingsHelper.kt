package com.persianstt.offline

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object SettingsHelper {
    fun showSoundVibeSettings(
        context: Context,
        prefs: SharedPreferences,
        onOpenKeyboardSetup: () -> Unit
    ) {
        val soundOn = prefs.getBoolean("key_sound", true)
        val vibeOn = prefs.getBoolean("key_vibe", true)
        val volume = prefs.getInt("sound_volume", 60)
        val vibeStrength = prefs.getInt("vibe_strength", 60)
        val effect = prefs.getInt("sound_effect", 0)
        val effectNames = arrayOf("کلیک سامسونگ", "تیک نرم", "پاپ", "شاتر دوربین", "گیتار")
        val items = arrayOf(
            if (soundOn) "🔇 خاموش کردن صدای کلید" else "🔊 روشن کردن صدای کلید",
            "🎵 انتخاب افکت صدا (الان: ${effectNames.getOrElse(effect) { effectNames[0] }})",
            "📢 میزان صدای کلید (الان: $volume٪)",
            if (vibeOn) "📴 خاموش کردن ویبره" else "📳 روشن کردن ویبره",
            "💪 شدت ویبره (الان: $vibeStrength٪)",
            "🎹 راهنمای فعال‌سازی کیبورد"
        )
        MaterialAlertDialogBuilder(context)
            .setTitle("تنظیمات کیبورد، صدا و ویبره")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putBoolean("key_sound", !soundOn).apply()
                        Toast.makeText(
                            context,
                            if (!soundOn) "صدای کلید روشن شد" else "صدای کلید خاموش شد",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("افکت صدای کلید")
                            .setItems(effectNames) { _, e ->
                                prefs.edit().putInt("sound_effect", e).apply()
                                KeySoundPlayer.play(context, e, volume)
                                Toast.makeText(context, "افکت: ${effectNames[e]}", Toast.LENGTH_SHORT).show()
                            }.show()
                    }
                    2 -> {
                        val levels = arrayOf("۲۰٪", "۴۰٪", "۶۰٪", "۸۰٪", "۱۰۰٪")
                        MaterialAlertDialogBuilder(context)
                            .setTitle("میزان صدای کلید")
                            .setItems(levels) { _, w ->
                                val v = (w + 1) * 20
                                prefs.edit().putInt("sound_volume", v).apply()
                                KeySoundPlayer.play(context, effect, v)
                                Toast.makeText(context, "بلندی: ${levels[w]}", Toast.LENGTH_SHORT).show()
                            }.show()
                    }
                    3 -> {
                        prefs.edit().putBoolean("key_vibe", !vibeOn).apply()
                        Toast.makeText(
                            context,
                            if (!vibeOn) "ویبره روشن شد" else "ویبره خاموش شد",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    4 -> {
                        val levels = arrayOf("۲۰٪ (ضعیف)", "۴۰٪", "۶۰٪ (متوسط)", "۸۰٪", "۱۰۰٪ (قوی)")
                        MaterialAlertDialogBuilder(context)
                            .setTitle("شدت ویبره تایپ")
                            .setItems(levels) { _, w ->
                                val v = (w + 1) * 20
                                prefs.edit().putInt("vibe_strength", v).apply()
                                Toast.makeText(context, "شدت ویبره: ${levels[w]}", Toast.LENGTH_SHORT).show()
                            }.show()
                    }
                    5 -> onOpenKeyboardSetup()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }
}
