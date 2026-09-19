# -*- coding: utf-8 -*-
"""Force MainActivity onto pure Vosk path (stable engine)."""
from pathlib import Path

def main():
    p = Path('app/src/main/java/com/persianstt/offline/MainActivity.kt')
    t = p.read_text(encoding='utf-8')

    old_prep_start = '    private fun prepareModel() {'
    old_copy = '    private fun copyText() {'

    i0 = t.find(old_prep_start)
    i1 = t.find(old_copy)
    if i0 < 0 or i1 < 0:
        print('bounds missing', i0, i1)
        return

    new = r'''
    private fun prepareModel() {
        try {
            if (VoskEngine.isReady(this)) {
                binding.status.text = "آماده — موتور Vosk آفلاین"
                binding.micButton.isEnabled = true
                return
            }
        } catch (t: Throwable) {
            binding.status.text = "خطا در بررسی مدل"
            binding.micButton.isEnabled = false
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("دانلود موتور Vosk")
            .setMessage("موتور تشخیص گفتار فارسی آفلاین (حدود ۵۱ مگابایت) یک‌بار دانلود شود؟\n\nبعد از دانلود کاملاً بدون اینترنت کار می‌کند.")
            .setPositiveButton("دانلود") { _, _ -> startModelDownload() }
            .setNegativeButton("لغو") { _, _ ->
                binding.status.text = "دانلود لغو شد"
                binding.micButton.isEnabled = false
            }
            .setCancelable(false)
            .show()
    }

    private fun startModelDownload() {
        lifecycleScope.launch {
            try {
                binding.micButton.isEnabled = false
                binding.progress.isIndeterminate = false
                binding.progress.visibility = android.view.View.VISIBLE
                binding.status.text = "دانلود مدل Vosk…"
                withContext(Dispatchers.IO) {
                    try { VoskEngine.release() } catch (_: Throwable) {}
                    System.gc()
                    VoskEngine.ensureModel(this@MainActivity) { pct ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                binding.progress.progress = pct
                                binding.status.text = if (pct < 95) "دانلود $pct٪" else "آماده‌سازی…"
                            }
                        }
                    }
                    try { VoskEngine.load(this@MainActivity) } catch (_: Exception) {}
                }
                if (isFinishing || isDestroyed) return@launch
                binding.progress.visibility = android.view.View.GONE
                if (VoskEngine.isReady(this@MainActivity)) {
                    binding.status.text = "آماده — موتور Vosk آفلاین"
                    binding.micButton.isEnabled = true
                } else {
                    binding.status.text = "خطا: ${VoskEngine.lastError}"
                    binding.micButton.isEnabled = false
                }
            } catch (e: Throwable) {
                if (!isFinishing && !isDestroyed) {
                    binding.progress.visibility = android.view.View.GONE
                    binding.status.text = "خطا: ${e.message ?: VoskEngine.lastError}"
                }
            }
        }
    }

    private fun startListening() {
        if (isFinishing || isDestroyed) return
        if (!VoskEngine.isReady(this)) {
            Toast.makeText(this, "مدل هنوز آماده نیست", Toast.LENGTH_SHORT).show()
            prepareModel()
            return
        }
        synchronized(stopLock) {
            if (isListening) return
            try {
                try { VoskEngine.load(this) } catch (_: Exception) {}
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    (minBuf * 2).coerceAtLeast(SAMPLE_RATE)
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Toast.makeText(this, "خطا در میکروفون", Toast.LENGTH_SHORT).show()
                    return
                }
                pcmChunks.clear()
                isListening = true
                audioRecord?.startRecording()
                try {
                    val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
                    tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 90)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { tg.release() } catch (_: Exception) {}
                    }, 200)
                } catch (_: Exception) {}
                binding.micButton.text = getString(R.string.btn_stop)
                binding.status.text = "🎤 گوش می‌دهم… (توقف خودکار با سکوت)"
                listenJob = lifecycleScope.launch(Dispatchers.IO) {
                    val buf = ShortArray(SAMPLE_RATE / 10)
                    var speechSeen = false
                    var silentFrames = 0
                    val silenceLimit = 18
                    val energyThr = 700
                    var maxFrames = 400
                    while (isActive && isListening && maxFrames-- > 0) {
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
                }
            } catch (e: Exception) {
                Toast.makeText(this, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
                isListening = false
            }
        }
    }

    private fun stopListening() {
        synchronized(stopLock) {
            if (!isListening) return
        }
        if (!isFinishing && !isDestroyed) {
            binding.micButton.text = getString(R.string.btn_mic)
            binding.status.text = "در حال تشخیص با Vosk…"
            binding.micButton.isEnabled = false
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try { kotlinx.coroutines.delay(350) } catch (_: Exception) {}
            val job = listenJob
            synchronized(stopLock) { isListening = false }
            try { job?.join() } catch (_: Exception) {}
            synchronized(stopLock) {
                try {
                    val ar = audioRecord
                    if (ar != null) {
                        try {
                            val tail = ShortArray(SAMPLE_RATE / 2)
                            var got = 0
                            while (got < tail.size) {
                                val n = ar.read(tail, got, (tail.size - got).coerceAtMost(SAMPLE_RATE / 10))
                                if (n <= 0) break
                                got += n
                            }
                            if (got > 0) pcmChunks.add(tail.copyOf(got))
                        } catch (_: Exception) {}
                    }
                    try { audioRecord?.stop() } catch (_: Exception) {}
                    try { audioRecord?.release() } catch (_: Exception) {}
                    audioRecord = null
                } catch (_: Exception) {}
                listenJob = null
            }
            val chunks = pcmChunks.toList()
            pcmChunks.clear()
            val total = chunks.sumOf { it.size }
            val pcm = if (total > 0) {
                val arr = ShortArray(total)
                var o = 0
                for (c in chunks) {
                    System.arraycopy(c, 0, arr, o, c.size)
                    o += c.size
                }
                arr
            } else ShortArray(0)

            val (text, engine) = DualAsr.transcribe(this@MainActivity, pcm, SAMPLE_RATE)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                binding.micButton.isEnabled = true
                if (text.isNotBlank()) {
                    if (finalText.isNotEmpty()) finalText.append(" ")
                    finalText.append(text)
                    binding.resultText.setText(finalText.toString())
                    binding.resultText.setSelection(binding.resultText.text.length)
                    binding.status.text = "✓ $text"
                } else {
                    val sec = total.toFloat() / SAMPLE_RATE
                    val msg = "چیزی تشخیص داده نشد (${\"%.1f\".format(sec)}s · $engine)"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    binding.status.text = msg
                }
                binding.micButton.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        binding.status.text = "آماده — موتور Vosk آفلاین"
                    }
                }, 2500)
            }
        }
    }

'''
    t = t[:i0] + new + t[i1:]

    t = t.replace(
        'binding.editButton.visibility = android.view.View.GONE',
        'binding.editButton.visibility = android.view.View.VISIBLE'
    )

    if 'SettingsHelper.showSoundVibeSettings' not in t and 'fun showSoundSettings' in t:
        s0 = t.find('    private fun showSoundSettings() {')
        s1 = t.find('    private fun showInvite()', s0)
        if s0 > 0 and s1 > s0:
            t = t[:s0] + '    private fun showSoundSettings() {\n        try {\n            SettingsHelper.showSoundVibeSettings(this, prefs) { setupKeyboardFlow() }\n        } catch (_: Exception) {\n            Toast.makeText(this, "تنظیمات", Toast.LENGTH_SHORT).show()\n        }\n    }\n\n' + t[s1:]

    p.write_text(t, encoding='utf-8')
    print('MainActivity patched for Vosk')

if __name__ == '__main__':
    main()
