package com.persianstt.offline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.persianstt.offline.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    @Volatile private var isListening = false
    private val stopLock = Any()
    private var finalText = StringBuilder()
    private var currentLang = LANG_FA
    private val pcmChunks = CopyOnWriteArrayList<ShortArray>()

    companion object {
        private const val REQ_MIC = 1001
        private const val SAMPLE_RATE = 16000
        private const val PREFS = "hamdel_stt"
        private const val KEY_LANG = "lang"
        private const val KEY_HIDE_INVITE = "hide_invite"
        private const val KEY_SOUND = "key_sound"
        private const val KEY_VIBE = "key_vibe"
        const val LANG_FA = "fa"
        const val LANG_EN = "en"
        private const val CHANNEL_URL = "https://t.me/Akademi_hamdel"
    }

    

override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        currentLang = prefs.getString(KEY_LANG, LANG_FA) ?: LANG_FA
        updateLangBadge()
        binding.micButton.setOnClickListener {
            if (isListening) stopListening() else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                } else startListening()
            }
        }
        binding.copyButton.setOnClickListener { copyText() }
        binding.editButton.setOnClickListener { showEditTextDialog() }
        binding.emojiButton.setOnClickListener { applyEmojis() }
        binding.clearButton.setOnClickListener {
            finalText.clear()
            binding.resultText.setText("")
        }

        binding.dictationButton.setOnClickListener { showDictationHelp() }
        // long-press status → keyboard setup
        binding.status.setOnLongClickListener {
            setupKeyboardFlow()
            true
        }
        binding.dictationButton.setOnLongClickListener {
            setupKeyboardFlow()
            true
        }

        // Opened from RecognitionService when mic permission missing
        if (intent?.getBooleanExtra("request_mic", false) == true) {
            ensureMicPermission()
        }
        if (!prefs.getBoolean(KEY_HIDE_INVITE, false)) showInvite()
        SymSpell.ensureLoaded(this)
        OfflineAi.ensure(this)
        prepareModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_language -> { showLanguagePicker(); true }
            R.id.action_settings -> { showSoundSettings(); true }
            R.id.action_help -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.help_title)
                    .setMessage(R.string.help_msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            R.id.action_about -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.about_title)
                    .setMessage(R.string.about_msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            R.id.action_channel -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL_URL)))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateLangBadge() {
        try {
            binding.langBadge.text = if (currentLang == LANG_EN) "EN" else "FA"
        } catch (_: Exception) {}
    }

    private fun showLanguagePicker() {
        val items = arrayOf(getString(R.string.lang_fa), getString(R.string.lang_en))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_title)
            .setItems(items) { _, which ->
                currentLang = if (which == 1) LANG_EN else LANG_FA
                prefs.edit().putString(KEY_LANG, currentLang).apply()
                updateLangBadge()
                Toast.makeText(this, R.string.lang_changed, Toast.LENGTH_SHORT).show()
                VoskEngine.release()
                
                
                prepareModel()
            }.show()
    }



    private fun showEditTextDialog() {
        val current = binding.resultText.text?.toString()?.trim().orEmpty()
        if (current.isBlank()) {
            Toast.makeText(this, "متنی برای اصلاح نیست", Toast.LENGTH_SHORT).show()
            return
        }
        binding.status.text = "اصلاح با LLM آفلاین (Qwen)…"
        lifecycleScope.launch {
            val fixed = try {
                val llm = OfflineLlm.correctText(this@MainActivity, current)
                when {
                    llm.isNotBlank() -> llm
                    else -> withContext(Dispatchers.IO) {
                        OfflineVoiceAi.improve(this@MainActivity, current)
                            .ifBlank { OfflineAi.correctText(this@MainActivity, current) }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.IO) {
                    OfflineAi.correctText(this@MainActivity, current)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            val out = fixed.ifBlank { current }
            finalText.clear(); finalText.append(out)
            binding.resultText.setText(out)
            binding.resultText.setSelection(out.length)
            val msg = when {
                OfflineLlm.lastError.isNotBlank() && out == current ->
                    "LLM: ${OfflineLlm.lastError}"
                out != current -> "اصلاح شد (LLM آفلاین Qwen)"
                else -> "متن از قبل درست بود"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            binding.status.text = "آماده — Shenava + Qwen آفلاین"
        }
    }

    private fun applyEmojis() {
        val current = binding.resultText.text?.toString()?.trim().orEmpty()
        if (current.isBlank()) {
            Toast.makeText(this, "متنی نیست", Toast.LENGTH_SHORT).show()
            return
        }
        binding.status.text = "ایموجی با LLM آفلاین…"
        lifecycleScope.launch {
            val enriched = try {
                val llm = OfflineLlm.addEmojis(this@MainActivity, current)
                if (llm.isNotBlank()) llm
                else withContext(Dispatchers.IO) { OfflineAi.addEmojis(current) }
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { OfflineAi.addEmojis(current) }
            }
            if (isFinishing || isDestroyed) return@launch
            finalText.clear(); finalText.append(enriched)
            binding.resultText.setText(enriched)
            binding.resultText.setSelection(enriched.length)
            Toast.makeText(this@MainActivity, "ایموجی اضافه شد", Toast.LENGTH_SHORT).show()
            binding.status.text = "آماده — Shenava + Qwen آفلاین"
        }
    }

    private fun showSoundSettings() {
        val soundOn = prefs.getBoolean(KEY_SOUND, true)
        val vibeOn = prefs.getBoolean(KEY_VIBE, true)
        val volume = prefs.getInt("sound_volume", 60)
        val effect = prefs.getInt("sound_effect", 0)
        val effectNames = arrayOf("کلیک سامسونگ", "تیک نرم", "پاپ", "شاتر دوربین", "گیتار")
        val status = buildString {
            append("صدا: "); append(if (soundOn) "روشن" else "خاموش")
            append("  |  ویبره: "); append(if (vibeOn) "روشن" else "خاموش")
            append("\nافکت: "); append(effectNames.getOrElse(effect) { effectNames[0] })
            append("  |  بلندی: "); append(volume); append("%")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("تنظیمات صدا و ویبره")
            .setMessage(status)
            .setPositiveButton(if (soundOn) "خاموش کردن صدا" else "روشن کردن صدا") { _, _ ->
                prefs.edit().putBoolean(KEY_SOUND, !soundOn).apply()
                Toast.makeText(this, if (!soundOn) "صدا روشن شد" else "صدا خاموش شد", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (vibeOn) "خاموش ویبره" else "روشن ویبره") { _, _ ->
                prefs.edit().putBoolean(KEY_VIBE, !vibeOn).apply()
                Toast.makeText(this, if (!vibeOn) "ویبره روشن شد" else "ویبره خاموش شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("افکت و بلندی") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("انتخاب افکت صدا")
                    .setItems(effectNames) { _, which ->
                        prefs.edit().putInt("sound_effect", which).apply()
                        KeySoundPlayer.play(this, which, volume)
                        val levels = arrayOf("۲۰٪", "۴۰٪", "۶۰٪", "۸۰٪", "۱۰۰٪")
                        MaterialAlertDialogBuilder(this)
                            .setTitle("میزان بلندی صدا")
                            .setItems(levels) { _, w ->
                                val v = (w + 1) * 20
                                prefs.edit().putInt("sound_volume", v).apply()
                                KeySoundPlayer.play(this, which, v)
                                Toast.makeText(this, "افکت: ${effectNames[which]} — ${levels[w]}", Toast.LENGTH_SHORT).show()
                            }.show()
                    }.show()
            }
            .show()
    }

    private fun showInvite() {
        val box = CheckBox(this).apply { text = getString(R.string.invite_hide) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.invite_title)
            .setMessage(R.string.invite_msg)
            .setView(box)
            .setPositiveButton(R.string.invite_ok) { _, _ ->
                if (box.isChecked) prefs.edit().putBoolean(KEY_HIDE_INVITE, true).apply()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL_URL)))
            }
            .setNegativeButton(R.string.invite_cancel) { _, _ ->
                if (box.isChecked) prefs.edit().putBoolean(KEY_HIDE_INVITE, true).apply()
            }.show()
    }

    private fun prepareModel() {
        if (ShenavaEngine.isReady(this) || VoskEngine.isReady(this)) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        when {
                            ShenavaEngine.isReady(this@MainActivity) -> ShenavaEngine.load(this@MainActivity)
                            else -> VoskEngine.load(this@MainActivity)
                        }
                    } catch (_: Throwable) { false }
                }
                if (!isFinishing && !isDestroyed) {
                    if (ok) {
                        binding.status.text = "آماده — Shenava + Qwen آفلاین"
                        binding.micButton.isEnabled = true
                    } else {
                        binding.status.text = "خطا: ${(ShenavaEngine.lastError.ifBlank { VoskEngine.lastError }).ifBlank { "بارگذاری ناموفق" }}"
                        binding.micButton.isEnabled = false
                    }
                }
            }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("دانلود موتور")
            .setMessage("موتور آفلاین فارسی Shenava (~۱۰۰ مگ) دانلود شود؟\n(مدل آماده، نه ساخت دستی)")
            .setPositiveButton("بله") { _, _ -> startModelDownload() }
            .setNegativeButton("خیر") { _, _ ->
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
                binding.status.text = "دانلود…"
                withContext(Dispatchers.IO) {
                    try { ShenavaEngine.release() } catch (_: Throwable) {}
                    try { VoskEngine.release() } catch (_: Throwable) {}
                    try { VoskEngine.release() } catch (_: Throwable) {}
                    try { Qwen3Engine.release() } catch (_: Throwable) {}
                    try { VoskEngine.release() } catch (_: Throwable) {}
                    System.gc()
                    ShenavaEngine.ensureModel(this@MainActivity) { pct ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                binding.progress.progress = pct
                                binding.status.text = when {
                                    pct < 86 -> "دانلود $pct٪"
                                    pct < 100 -> "استخراج $pct٪"
                                    else -> "آماده"
                                }
                            }
                        }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                // Load in a separate step; never kill UI if OOM
                binding.status.text = "بارگذاری سبک…"
                binding.progress.isIndeterminate = true
                val ok = withContext(Dispatchers.IO) {
                    try {
                        System.gc()
                        ShenavaEngine.load(this@MainActivity)
                    } catch (_: OutOfMemoryError) {
                        ShenavaEngine.lastError = "حافظه کم — اپ را دوباره باز کنید"
                        false
                    } catch (_: Throwable) {
                        false
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                binding.progress.isIndeterminate = false
                binding.progress.visibility = android.view.View.GONE
                if (ok) {
                    binding.status.text = "آماده — Shenava"
                    binding.micButton.isEnabled = true
                    // download offline LLM package (Qwen) if missing
                    if (!OfflineLlm.isReady(this@MainActivity)) {
                        binding.status.text = "دانلود LLM آفلاین Qwen (~۴۰۰ مگ)…"
                        binding.progress.visibility = android.view.View.VISIBLE
                        binding.progress.isIndeterminate = false
                        val llmOk = withContext(Dispatchers.IO) {
                            try {
                                OfflineLlm.ensureModel(this@MainActivity) { pct ->
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed) {
                                            binding.progress.progress = pct
                                            binding.status.text = "دانلود Qwen $pct٪"
                                        }
                                    }
                                }
                                true
                            } catch (_: Exception) { false }
                        }
                        binding.progress.visibility = android.view.View.GONE
                        binding.status.text = if (llmOk) "آماده — Shenava + Qwen آفلاین"
                            else "Shenava آماده — LLM: ${OfflineLlm.lastError}"
                    } else {
                        binding.status.text = "آماده — Shenava + Qwen آفلاین"
                    }
                } else if (ShenavaEngine.isReady(this@MainActivity) || VoskEngine.isReady(this@MainActivity)) {
                    binding.status.text = "دانلود شد — یک‌بار اپ را ببندید و باز کنید"
                    binding.micButton.isEnabled = false
                } else {
                    binding.status.text = "خطا: ${ShenavaEngine.lastError.ifBlank { VoskEngine.lastError }}"
                    binding.micButton.isEnabled = false
                }
            } catch (e: OutOfMemoryError) {
                if (!isFinishing && !isDestroyed) {
                    binding.progress.visibility = android.view.View.GONE
                    binding.status.text = "حافظه کم — برنامه‌های دیگر را ببندید"
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
        if (!ShenavaEngine.isReady(this) && !VoskEngine.isReady(this)) {
            Toast.makeText(this, "مدل هنوز آماده نیست", Toast.LENGTH_SHORT).show()
            prepareModel()
            return
        }
        synchronized(stopLock) {
            if (isListening) return
            try {
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
                binding.micButton.text = getString(R.string.btn_stop)
                binding.status.text = getString(R.string.status_listening)
                listenJob = lifecycleScope.launch(Dispatchers.IO) {
                    val buf = ShortArray(SAMPLE_RATE / 5)
                    while (isActive && isListening) {
                        val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                        if (n > 0) pcmChunks.add(buf.copyOf(n))
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
            binding.status.text = "در حال تشخیص…"
            binding.micButton.isEnabled = false
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // keep mic open a bit so last phonemes arrive
            try { kotlinx.coroutines.delay(500) } catch (_: Exception) {}
            val job = listenJob
            synchronized(stopLock) {
                isListening = false
            }
            // let the reader loop exit cleanly (don't cancel mid-read)
            try { job?.join() } catch (_: Exception) {}
            synchronized(stopLock) {
                try {
                    val ar = audioRecord
                    if (ar != null) {
                        try {
                            val tail = ShortArray(SAMPLE_RATE / 2)
                            var got = 0
                            // drain up to ~0.5s
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
                    val msg = "چیزی تشخیص داده نشد (${"%.1f".format(sec)}s · $engine)"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    binding.status.text = msg
                }
                binding.micButton.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        binding.status.text = "آماده — Shenava + Qwen آفلاین"
                    }
                }, 3000)
            }
        }
    }

    private fun copyText() {
        val text = binding.resultText.text?.toString().orEmpty()
        if (text.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("stt", text))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (isFinishing || isDestroyed) return
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                binding.micButton.post {
                    if (!isFinishing && !isDestroyed) startListening()
                }
            } else {
                Toast.makeText(this, getString(R.string.need_mic), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        VoskEngine.release()
        
        super.onDestroy()
    }

    private fun showDictationHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("دیکته صوتی (سیستم)")
            .setMessage(
                "برای دیکته در واتساپ، پیام‌رسان و هر برنامه:\n\n" +
                "۱) تنظیمات گوشی → زبان و ورودی / سیستم\n" +
                "۲) تشخیص گفتار / Speech services\n" +
                "۳) «Vosk آفلاین فارسی» را انتخاب کنید\n\n" +
                "مجوز میکروفون باید داده شده باشد.\n" +
                "داخل خود این برنامه هم دکمه میکروفون = Vosk آفلاین است."
            )
            .setPositiveButton("باز کردن تنظیمات") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("باشه", null)
            .show()
    }

    private fun openVoiceInputSettings() {
        val attempts = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in attempts) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        android.widget.Toast.makeText(
            this,
            "تنظیمات پیدا نشد. دستی بروید: تنظیمات ← زبان ← ورودی صوتی",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }


    /** Enable IME + pick it + try show keyboard on edit fields. */
    private fun setupKeyboardFlow() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (_: Exception) {
            openVoiceInputSettings()
        }
        Toast.makeText(
            this,
            "۱) کیبورد این برنامه را روشن کنید\n۲) در پنجره بعد آن را انتخاب کنید",
            Toast.LENGTH_LONG
        ).show()
        binding.root.postDelayed({
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (_: Exception) {}
        }, 1500)
    }

    private fun tryShowKeyboard() {
        val et = binding.resultText
        et.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // after returning from IME settings, offer picker once
            binding.root.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                try {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    val enabled = imm.enabledInputMethodList.any {
                        it.packageName == packageName
                    }
                    if (enabled) {
                        // soft show on our field
                        tryShowKeyboard()
                    }
                } catch (_: Exception) {}
            }, 400)
        }
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            android.widget.Toast.makeText(this, "مجوز میکروفون از قبل داده شده", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        androidx.core.app.ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
        )
    }

}
