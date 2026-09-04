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
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    private var isListening = false
    private var finalText = StringBuilder()
    private var currentLang = LANG_FA

    companion object {
        private const val REQ_MIC = 1001
        private const val SAMPLE_RATE = 16000
        private const val PREFS = "hamdel_stt"
        private const val KEY_LANG = "lang"
        private const val KEY_HIDE_INVITE = "hide_invite"
        const val LANG_FA = "fa"
        const val LANG_EN = "en"
        private const val CHANNEL_URL = "https://t.me/Akademi_hamdel"

        private val MODELS = mapOf(
            LANG_FA to ModelSpec(
                "vosk-model-small-fa-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"
            ),
            LANG_EN to ModelSpec(
                "vosk-model-small-en-us-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
            )
        )
    }

    data class ModelSpec(val dirName: String, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        currentLang = prefs.getString(KEY_LANG, LANG_FA) ?: LANG_FA
        updateLangBadge()

        LibVosk.setLogLevel(LogLevel.WARNINGS)

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
        val labels = arrayOf(getString(R.string.lang_fa), getString(R.string.lang_en))
        val codes = arrayOf(LANG_FA, LANG_EN)
        val checked = if (currentLang == LANG_EN) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = codes[which]
                if (selected != currentLang) {
                    if (isListening) stopListening()
                    currentLang = selected
                    prefs.edit().putString(KEY_LANG, selected).apply()
                    updateLangBadge()
                    model?.close()
                    model = null
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
                val modelPath = withContext(Dispatchers.IO) { ensureModelReady() }
                withContext(Dispatchers.IO) {
                    model?.close()
                    model = Model(modelPath)
                }
                binding.status.text = getString(R.string.status_ready)
                binding.progress.visibility = android.view.View.GONE
                binding.micButton.isEnabled = true
            } catch (e: Exception) {
                binding.status.text = "${getString(R.string.status_error)}: ${e.message}"
                binding.progress.visibility = android.view.View.GONE
                binding.micButton.isEnabled = false
            }
        }
    }

    private fun ensureModelReady(): String {
        val spec = MODELS[currentLang] ?: MODELS[LANG_FA]!!
        val base = File(filesDir, "vosk-models")
        val modelDir = File(base, spec.dirName)
        val marker = File(modelDir, "am/final.mdl")
        if (marker.exists()) return modelDir.absolutePath

        runOnUiThread {
            binding.status.text = getString(R.string.status_downloading)
            binding.progress.visibility = android.view.View.VISIBLE
            binding.progress.isIndeterminate = false
            binding.progress.progress = 0
        }

        if (!base.exists()) base.mkdirs()
        val zipFile = File(cacheDir, "${spec.dirName}.zip")
        downloadFile(spec.url, zipFile) { pct ->
            runOnUiThread {
                binding.progress.progress = pct
                binding.status.text = "${getString(R.string.status_downloading)} $pct%"
            }
        }

        runOnUiThread {
            binding.status.text = getString(R.string.status_extracting)
            binding.progress.isIndeterminate = true
        }

        if (modelDir.exists()) modelDir.deleteRecursively()
        unzip(zipFile, base)
        zipFile.delete()
        if (!marker.exists()) throw IllegalStateException("Model incomplete after extract")
        return modelDir.absolutePath
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.requestMethod = "GET"
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                var lastPct = -1
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun startListening() {
        if (model == null) {
            Toast.makeText(this, getString(R.string.status_loading), Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(SAMPLE_RATE / 2)
            )
            audioRecord?.startRecording()
            isListening = true
            binding.micButton.text = getString(R.string.btn_stop)
            binding.status.text = getString(R.string.status_listening)
            listenJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(4096)
                while (isActive && isListening) {
                    val n = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (n > 0) {
                        val rec = recognizer ?: break
                        if (rec.acceptWaveForm(buffer, n)) {
                            val text = extractText(rec.result)
                            if (text.isNotBlank()) {
                                withContext(Dispatchers.Main) {
                                    if (finalText.isNotEmpty()) finalText.append(" ")
                                    finalText.append(text)
                                    binding.resultText.setText(finalText.toString())
                                    binding.resultText.setSelection(binding.resultText.text.length)
                                }
                            }
                        } else {
                            val partial = extractPartial(rec.partialResult)
                            if (partial.isNotBlank()) {
                                withContext(Dispatchers.Main) {
                                    val base = finalText.toString()
                                    val shown = if (base.isBlank()) partial else "$base $partial"
                                    binding.resultText.setText(shown)
                                    binding.resultText.setSelection(binding.resultText.text.length)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            binding.status.text = "${getString(R.string.status_error)}: ${e.message}"
            stopListening()
        }
    }

    private fun stopListening() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try {
            val final = recognizer?.finalResult
            if (final != null) {
                val text = extractText(final)
                if (text.isNotBlank()) {
                    if (finalText.isNotEmpty()) finalText.append(" ")
                    finalText.append(text)
                    binding.resultText.setText(finalText.toString())
                }
            }
        } catch (_: Exception) {}
        recognizer?.close()
        recognizer = null
        binding.micButton.text = getString(R.string.btn_mic)
        binding.status.text = getString(R.string.status_ready)
    }

    private fun extractText(json: String): String =
        try { JSONObject(json).optString("text", "").trim() } catch (_: Exception) { "" }

    private fun extractPartial(json: String): String =
        try { JSONObject(json).optString("partial", "").trim() } catch (_: Exception) { "" }

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
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
            else Toast.makeText(this, getString(R.string.need_mic), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        stopListening()
        model?.close()
        model = null
        super.onDestroy()
    }
}
