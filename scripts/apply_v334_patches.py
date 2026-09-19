# -*- coding: utf-8 -*-
from pathlib import Path

def main():
    p = Path('app/src/main/java/com/persianstt/offline/MainActivity.kt')
    t = p.read_text(encoding='utf-8')
    idx = t.find('    private fun showSoundSettings() {')
    idx2 = t.find('    private fun showInvite()', idx)
    if idx > 0 and idx2 > idx and 'SettingsHelper' not in t[idx:idx2]:
        t = t[:idx] + '    private fun showSoundSettings() {\n        SettingsHelper.showSoundVibeSettings(this, prefs) { setupKeyboardFlow() }\n    }\n\n' + t[idx2:]
        print('patched settings')
    old = '''        MaterialAlertDialogBuilder(this)
            .setTitle("دانلود مدل")
            .setMessage("مدل سبک Whisper Tiny (~۱۲۰ مگ) دانلود شود؟ بعد می‌توانید Qwen را هم برای تایپ بگیرید.")
            .setPositiveButton("بله") { _, _ -> startModelDownload() }
            .setNegativeButton("خیر") { _, _ ->
                binding.status.text = "دانلود لغو شد"
                binding.micButton.isEnabled = false
            }
            .setCancelable(false)
            .show()'''
    new = '''        MaterialAlertDialogBuilder(this)
            .setTitle("دانلود موتور تشخیص گفتار")
            .setMessage("برای کار آفلاین باید موتور تشخیص (حدود ۱۲۰ مگابایت) یک‌بار دانلود شود.\\n\\nبعد از دانلود، بدون اینترنت تایپ صوتی کار می‌کند.\\n\\nآیا دانلود شود؟")
            .setPositiveButton("دانلود") { _, _ -> startModelDownload() }
            .setNegativeButton("لغو") { _, _ ->
                binding.status.text = "دانلود لغو شد — از منو می‌توانید دوباره دانلود کنید"
                binding.micButton.isEnabled = false
            }
            .setCancelable(false)
            .show()'''
    if old in t:
        t = t.replace(old, new, 1)
        print('patched dialog')
    old3 = '''                pcmChunks.clear()
                isListening = true
                audioRecord?.startRecording()
                binding.micButton.text = getString(R.string.btn_stop)
                binding.status.text = getString(R.string.status_listening)
                listenJob = lifecycleScope.launch(Dispatchers.IO) {
                    val buf = ShortArray(SAMPLE_RATE / 5)
                    while (isActive && isListening) {
                        val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                        if (n > 0) pcmChunks.add(buf.copyOf(n))
                    }
                }'''
    new3 = '''                pcmChunks.clear()
                isListening = true
                audioRecord?.startRecording()
                try {
                    val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
                    tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 90)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { tg.release() } catch (_: Exception) {} }, 200)
                } catch (_: Exception) {}
                binding.micButton.text = getString(R.string.btn_stop)
                binding.status.text = "🎤 گوش می‌دهم… (با سکوت خودکار متوقف می‌شود)"
                listenJob = lifecycleScope.launch(Dispatchers.IO) {
                    val buf = ShortArray(SAMPLE_RATE / 10)
                    var speechSeen = false
                    var silentFrames = 0
                    val silenceLimit = 15
                    val energyThr = 900
                    var maxListenFrames = 300
                    while (isActive && isListening && maxListenFrames-- > 0) {
                        val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                        if (n <= 0) continue
                        pcmChunks.add(buf.copyOf(n))
                        var peak = 0
                        for (i in 0 until n) {
                            val a = kotlin.math.abs(buf[i].toInt())
                            if (a > peak) peak = a
                        }
                        if (peak >= energyThr) {
                            speechSeen = true
                            silentFrames = 0
                        } else if (speechSeen) {
                            silentFrames++
                            if (silentFrames >= silenceLimit) {
                                withContext(Dispatchers.Main) {
                                    if (isListening) stopListening()
                                }
                                break
                            }
                        }
                    }
                }'''
    if old3 in t:
        t = t.replace(old3, new3, 1)
        print('patched listening')
    p.write_text(t, encoding='utf-8')

    v = Path('app/src/main/java/com/persianstt/offline/VoiceInputMethodService.kt')
    vt = v.read_text(encoding='utf-8')
    needle = '        setBackgroundColor(if (special) KEY_BG_SP else KEY_BG)\n        isAllCaps = false'
    repl = '''        val bgRes = when {
            label == "فاصله" || label == "space" -> R.drawable.key_bg_space
            special -> R.drawable.key_bg_special
            else -> R.drawable.key_bg
        }
        setBackgroundResource(bgRes)
        isAllCaps = false
        elevation = 2f
        setPadding(4, 0, 4, 0)'''
    if needle in vt:
        v.write_text(vt.replace(needle, repl, 1), encoding='utf-8')
        print('patched keyboard keys')
    print('done')

if __name__ == '__main__':
    main()
