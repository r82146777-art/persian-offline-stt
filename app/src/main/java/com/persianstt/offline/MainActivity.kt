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
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
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
            if (isListening) stopListening() else startListening()
        }
        binding.copyButton.setOnClickListener { copyText() }
        binding.clearButton.setOnClickListener {
            finalText.clear()
            binding.resultText.setText("")
        }

        prepareModel()
        maybeShowInvite()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_language -> { showLanguagePicker(); true }
            R.id.action_sound -> {
                val on = !(prefs.getBoolean(KEY_SOUND, true))
                prefs.edit().putBoolean(KEY_SOUND, on).apply()
                Toast.makeText(this, if (on) R.string.sound_on else R.string.sound_off, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_vibe -> {
                val on = !(prefs.getBoolean(KEY_VIBE, true))
                prefs.edit().putBoolean(KEY_VIBE, on).apply()
                Toast.makeText(this, if (on) R.string.vibe_on else R.string.vibe_off, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_help -> { showHelp(); true }
            R.id.action_about -> { showAbout(); true }
            R.id.action_channel -> { openChannel(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateLangBadge() {
        binding.langBadge.text = if (currentLang == LANG_EN) getString(R.string.lang_en) else getString(R.string.lang_fa)
    }

    private fun showLanguagePicker() {
        val labels = arrayOf(getString(R.string.lang_fa), getString(R.string.lang_en), "خودکار (هر دو)")
        val codes = arrayOf(LANG_FA, LANG_EN, "")
        val checked = when (currentLang) {
            LANG_EN -> 1
            LANG_FA -> 0
            else -> 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = codes[which]
                if (selected != currentLang) {
                    if (isListening) stopListening()
                    currentLang = selected
                    prefs.edit().putString(KEY_LANG, selected).apply()
                    updateLangBadge()
                    WhisperEngine.release()
                    Toast.makeText(this, R.string.lang_changed, Toast.LENGTH_SHORT).show()
                    prepareModel()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.invite_cancel, null)
            .show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.btn_copy_link) { _, _ -> copyChannelLink() }
            .show()
    }

    private fun copyChannelLink() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("channel", CHANNEL_URL))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openChannel() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL_URL)))
        } catch (_: Exception) {
            copyChannelLink()
        }
    }

    private fun maybeShowInvite() {
        if (prefs.getBoolean(KEY_HIDE_INVITE, false)) return
        val checkBox = CheckBox(this).apply {
            text = getString(R.string.invite_dont_show)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(48, 24, 48, 8)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            val msg = android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.invite_body)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 15f
            }
            addView(msg)
            addView(checkBox)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.invite_title)
            .setView(container)
            .setCancelable(true)
            .setNegativeButton(R.string.invite_cancel) { _, _ ->
                if (checkBox.isChecked) prefs.edit().putBoolean(KEY_HIDE_INVITE, true).apply()
            }
            .setPositiveButton(R.string.invite_join) { _, _ ->
                if (checkBox.isChecked) prefs.edit().putBoolean(KEY_HIDE_INVITE, true).apply()
                openChannel()
            }
            .show()
    }

    private fun prepareModel() {
        lifecycleScope.launch {
            try {
                binding.micButton.isEnabled = false
                binding.progress.isIndeterminate = false
                binding.progress.visibility = android.view.View.VISIBLE
                binding.status.text = "دانلود مدل Whisper (دقت بالا)…"
                withContext(Dispatchers.IO) {
                    WhisperEngine.ensureModel(this@MainActivity) { pct ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                binding.progress.progress = pct
                                binding.status.text = "دانلود Whisper $pct%"
                            }
                        }
                    }
                    WhisperEngine.load(this@MainActivity, currentLang)
                }
                if (isFinishing || isDestroyed) return@launch
                binding.status.text = "آماده — موتور Whisper آفلاین"
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
        if (!WhisperEngine.isReady(this)) {
            Toast.makeText(this, "مدل هنوز آماده نیست", Toast.LENGTH_SHORT).show()
            prepareModel()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (isListening) return

        try {
            pcmChunks.clear()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("AudioRecord buffer error")
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(SAMPLE_RATE / 2)
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("AudioRecord not initialized")
            }
            ar.startRecording()
            synchronized(stopLock) {
                audioRecord = ar
                isListening = true
            }
            binding.micButton.text = getString(R.string.btn_stop)
            binding.status.text = getString(R.string.status_listening)

            listenJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(4096)
                while (isActive && isListening) {
                    val n = try {
                        synchronized(stopLock) { audioRecord?.read(buffer, 0, buffer.size) ?: -1 }
                    } catch (_: Exception) { -1 }
                    if (n > 0) pcmChunks.add(buffer.copyOf(n))
                }
            }
        } catch (e: Exception) {
            binding.status.text = "خطا: ${e.message}"
            stopListening()
        }
    }

    private fun stopListening() {
        isListening = false
        try { listenJob?.cancel() } catch (_: Exception) {}
        listenJob = null

        mainHandler.post {
            synchronized(stopLock) {
                try {
                    audioRecord?.let { ar ->
                        try {
                            if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar.stop()
                        } catch (_: Exception) {}
                        try { ar.release() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                audioRecord = null
            }

            if (!isFinishing && !isDestroyed) {
                binding.micButton.text = getString(R.string.btn_mic)
                binding.status.text = "در حال تشخیص Whisper…"
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
                val text = if (pcm.isNotEmpty()) WhisperEngine.transcribe(pcm, SAMPLE_RATE) else ""
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (text.isNotBlank()) {
                        if (finalText.isNotEmpty()) finalText.append(" ")
                        finalText.append(text)
                        binding.resultText.setText(finalText.toString())
                        binding.resultText.setSelection(binding.resultText.text.length)
                    }
                    binding.status.text = "آماده — موتور Whisper آفلاین"
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
        WhisperEngine.release()
        super.onDestroy()
    }
}
