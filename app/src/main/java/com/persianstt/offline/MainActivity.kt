package com.persianstt.offline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        binding.clearButton.setOnClickListener {
            finalText.clear()
            binding.resultText.setText("")
        }
        if (!prefs.getBoolean(KEY_HIDE_INVITE, false)) showInvite()
        prepareModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_language -> { showLanguagePicker(); true }
            R.id.action_sound -> {
                val on = !prefs.getBoolean(KEY_SOUND, true)
                prefs.edit().putBoolean(KEY_SOUND, on).apply()
                Toast.makeText(this, if (on) R.string.sound_on else R.string.sound_off, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_vibe -> {
                val on = !prefs.getBoolean(KEY_VIBE, true)
                prefs.edit().putBoolean(KEY_VIBE, on).apply()
                Toast.makeText(this, if (on) R.string.vibe_on else R.string.vibe_off, Toast.LENGTH_SHORT).show()
                true
            }
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
                WhisperEngine.release()
                prepareModel()
            }.show()
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
        lifecycleScope.launch {
            try {
                binding.micButton.isEnabled = false
                binding.progress.isIndeterminate = false
                binding.progress.visibility = android.view.View.VISIBLE
                binding.status.text = "دانلود مدل Vosk فارسی…"
                withContext(Dispatchers.IO) {
                    VoskEngine.ensureModels(this@MainActivity) { pct ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                binding.progress.progress = pct
                                binding.status.text = "دانلود مدل $pct%"
                            }
                        }
                    }
                    VoskEngine.load(this@MainActivity, currentLang)
                    try {
                        if (!WhisperEngine.isReady(this@MainActivity)) {
                            WhisperEngine.ensureModel(this@MainActivity) {}
                        }
                    } catch (_: Exception) {}
                }
                if (isFinishing || isDestroyed) return@launch
                binding.status.text = "آماده — موتور Vosk آفلاین (فارسی)"
                binding.progress.visibility = android.view.View.GONE
                binding.micButton.isEnabled = true
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                binding.status.text = "خطا: ${e.message}"
                binding.progress.visibility = android.view.View.GONE
                binding.micButton.isEnabled = false
            }
        }
    }

    private fun startListening() {
        if (isFinishing || isDestroyed) return
        if (!VoskEngine.isAnyReady(this) && !WhisperEngine.isReady(this)) {
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
            isListening = false
            listenJob?.cancel()
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}
            audioRecord = null
        }
        if (!isFinishing && !isDestroyed) {
            binding.micButton.text = getString(R.string.btn_mic)
            binding.status.text = "در حال تشخیص…"
        }
        val chunks = pcmChunks.toList()
        pcmChunks.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            val total = chunks.sumOf { it.size }
            val pcm = ShortArray(total)
            var o = 0
            for (c in chunks) {
                System.arraycopy(c, 0, pcm, o, c.size)
                o += c.size
            }
            var text = ""
            if (pcm.size >= SAMPLE_RATE / 2) {
                try {
                    if (currentLang == LANG_FA) {
                        VoskEngine.load(this@MainActivity, "fa")
                        text = VoskEngine.transcribe(pcm, SAMPLE_RATE)
                        if (text.length < 2) {
                            WhisperEngine.load(this@MainActivity, "fa")
                            text = WhisperEngine.transcribe(pcm, SAMPLE_RATE)
                        }
                    } else {
                        WhisperEngine.load(this@MainActivity, "en")
                        text = WhisperEngine.transcribe(pcm, SAMPLE_RATE)
                        if (text.length < 2) {
                            VoskEngine.load(this@MainActivity, "en")
                            text = VoskEngine.transcribe(pcm, SAMPLE_RATE)
                        }
                    }
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (text.isNotBlank()) {
                    if (finalText.isNotEmpty()) finalText.append(" ")
                    finalText.append(text)
                    binding.resultText.setText(finalText.toString())
                    binding.resultText.setSelection(binding.resultText.text.length)
                } else {
                    Toast.makeText(this@MainActivity, "چیزی تشخیص داده نشد", Toast.LENGTH_SHORT).show()
                }
                binding.status.text = "آماده — موتور Vosk آفلاین"
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
        WhisperEngine.release()
        super.onDestroy()
    }
}
