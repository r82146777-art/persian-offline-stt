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

    private val dictFileLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val n = DictionaryStore.importFromUri(this, uri)
                VoskEngine.resetGrammar(this)
                Toast.makeText(this, "$n واژه از فایل اضافه شد", Toast.LENGTH_SHORT).show()
                binding.status.text = "آماده — Vosk + دیکت (${DictionaryStore.allWords(this).size} واژه)"
            } catch (e: Exception) {
                Toast.makeText(this, "خطای فایل: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    @Volatile private var isListening = false
    private val stopLock = Any()
    private var finalText = StringBuilder()
    private var currentLang = LANG_FA
    private val pcmChunks = CopyOnWriteArrayList<ShortArray>()
    @Volatile private var captureRate = 16000

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
        binding.dictButton.setOnClickListener { showDictDialog() }
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
        try { prepareModel() } catch (t: Throwable) {
            binding.status.text = "خطا در شروع — دوباره باز کنید"
        }
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



    private fun showDictDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "کلمه یا عبارت جدید"
            setSingleLine(false)
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("افزودن به دیکت (${DictionaryStore.allWords(this).size} واژه)")
            .setMessage("کلمه بنویسید یا از منوی زیر فایل متنی وارد کنید.")
            .setView(input)
            .setPositiveButton("افزودن") { _, _ ->
                val w = input.text?.toString().orEmpty()
                w.split(Regex("[\n,;]+")).forEach { DictionaryStore.addWord(this, it) }
                VoskEngine.resetGrammar(this)
                Toast.makeText(this, "دیکت به‌روز شد — موتور ریست شد", Toast.LENGTH_SHORT).show()
                binding.status.text = "آماده — Vosk + دیکت (${DictionaryStore.allWords(this).size} واژه)"
            }
            .setNeutralButton("فایل متنی") { _, _ ->
                try {
                    dictFileLauncher.launch(arrayOf("text/plain", "text/*"))
                } catch (e: Exception) {
                    Toast.makeText(this, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun applyEmojis() {
        val current = binding.resultText.text?.toString()?.trim().orEmpty()
        if (current.isBlank()) {
            Toast.makeText(this, "متنی نیست", Toast.LENGTH_SHORT).show()
            return
        }
        val enriched = OfflineAi.addEmojis(current)
        finalText.clear(); finalText.append(enriched)
        binding.resultText.setText(enriched)
        binding.resultText.setSelection(enriched.length)
        Toast.makeText(this, if (enriched != current) "ایموجی اضافه شد" else "ایموجی‌ای پیدا نشد", Toast.LENGTH_SHORT).show()
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
        try {
            if (VoskEngine.isReady(this)) {
                binding.status.text = "آماده — Vosk + دیکت (${DictionaryStore.allWords(this).size} واژه)"
                binding.micButton.isEnabled = true
                Thread {
                    try { VoskEngine.load(this@MainActivity) } catch (_: Exception) {}
                }.start()
                return
            }
        } catch (_: Throwable) {
            binding.status.text = "خطا در بررسی مدل"
            binding.micButton.isEnabled = false
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("دانلود مدل Vosk")
            .setMessage("مدل فارسی سبک Vosk (~۴۵ مگ) یک‌بار دانلود شود؟")
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
                binding.status.text = "دانلود مدل Vosk…"
                withContext(Dispatchers.IO) {
                    try { VoskEngine.release() } catch (_: Throwable) {}
                    System.gc()
                    VoskEngine.ensureModel(this@MainActivity) { pct ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                binding.progress.progress = pct
                                binding.status.text = if (pct < 90) "دانلود $pct٪" else "استخراج $pct٪"
                            }
                        }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                binding.progress.visibility = android.view.View.GONE
                if (VoskEngine.isReady(this@MainActivity)) {
                    withContext(Dispatchers.IO) {
                        try { VoskEngine.load(this@MainActivity) } catch (_: Exception) {}
                    }
                    binding.status.text = "آماده — Vosk + دیکت"
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


    @Volatile private var liveRecognizer: org.vosk.Recognizer? = null

    private fun startListening() {
        if (!VoskEngine.isReady(this)) {
            Toast.makeText(this, "مدل هنوز آماده نیست", Toast.LENGTH_SHORT).show()
            prepareModel()
            return
        }
        synchronized(stopLock) {
            if (isListening) return
            try {
                val rec = VoskEngine.createRecognizer(this)
                if (rec == null) {
                    Toast.makeText(this, "خطا: ${VoskEngine.lastError}", Toast.LENGTH_SHORT).show()
                    return
                }
                liveRecognizer = rec
                // Critical: open mic with real device rate, later force 16 kHz for STT
                val session = AudioCapture.open()
                if (session == null) {
                    Toast.makeText(this, "خطا در میکروفون", Toast.LENGTH_SHORT).show()
                    try { rec.close() } catch (_: Exception) {}
                    liveRecognizer = null
                    return
                }
                audioRecord = session.record
                captureRate = session.captureRate
                pcmChunks.clear()
                isListening = true
                audioRecord?.startRecording()
                binding.micButton.text = getString(R.string.btn_stop)
                binding.status.text = "گوش دادن… (${captureRate}→16kHz)"
                val frameSize = session.bufferShorts
                listenJob = lifecycleScope.launch(Dispatchers.IO) {
                    val buf = ShortArray(frameSize)
                    while (isActive && isListening) {
                        val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                        if (n <= 0) continue
                        pcmChunks.add(buf.copyOf(n))
                        // Live feed: resample chunk to 16k then to Vosk
                        try {
                            val chunk16 = AudioCapture.to16k(buf.copyOf(n), captureRate)
                            val prepared = AudioPreprocessor.prepare(chunk16, 16000)
                            val bytes = ShortArrayToBytes.pcm16ToBytes(prepared)
                            val r = liveRecognizer ?: continue
                            if (r.acceptWaveForm(bytes, bytes.size)) {
                                val text = VoskEngine.parseText(r.result)
                                if (text.isNotBlank()) {
                                    withContext(Dispatchers.Main) {
                                        if (!isFinishing && !isDestroyed) {
                                            binding.resultText.setText(text)
                                            binding.resultText.setSelection(text.length)
                                        }
                                    }
                                }
                            } else {
                                val partial = VoskEngine.parsePartial(r.partialResult)
                                if (partial.isNotBlank()) {
                                    withContext(Dispatchers.Main) {
                                        if (!isFinishing && !isDestroyed) {
                                            binding.resultText.setText(partial)
                                            binding.resultText.setSelection(partial.length)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
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
            isListening = false
        }
        if (!isFinishing && !isDestroyed) {
            binding.micButton.text = getString(R.string.btn_mic)
            binding.status.text = "نهایی‌سازی…"
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try { kotlinx.coroutines.delay(300) } catch (_: Exception) {}
            try { listenJob?.join() } catch (_: Exception) {}
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}
            audioRecord = null
            var finalText = ""
            try {
                val r = liveRecognizer
                if (r != null) {
                    finalText = VoskEngine.parseText(r.finalResult)
                    r.close()
                }
            } catch (_: Exception) {}
            liveRecognizer = null
            if (finalText.isBlank()) {
                // fallback batch
                val total = pcmChunks.sumOf { it.size }
                if (total > 0) {
                    val pcm = ShortArray(total)
                    var o = 0
                    for (c in pcmChunks) {
                        System.arraycopy(c, 0, pcm, o, c.size); o += c.size
                    }
                    finalText = DualAsr.transcribe(this@MainActivity, pcm, captureRate).first
                }
            }
            pcmChunks.clear()
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                binding.micButton.isEnabled = true
                if (finalText.isNotBlank()) {
                    binding.resultText.setText(finalText)
                    binding.resultText.setSelection(finalText.length)
                    this@MainActivity.finalText.clear()
                    this@MainActivity.finalText.append(finalText)
                    binding.status.text = "آماده — Vosk + دیکت"
                } else {
                    binding.status.text = "متنی تشخیص داده نشد"
                }
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
