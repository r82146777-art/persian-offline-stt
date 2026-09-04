package com.persianstt.offline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.persianstt.offline.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    private var isListening = false
    private var finalText = StringBuilder()

    companion object {
        private const val REQ_MIC = 1001
        private const val SAMPLE_RATE = 16000
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"
        private const val MODEL_DIR_NAME = "vosk-model-small-fa-0.42"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
    }

    private fun prepareModel() {
        lifecycleScope.launch {
            try {
                binding.micButton.isEnabled = false
                val modelPath = withContext(Dispatchers.IO) { ensureModelReady() }
                withContext(Dispatchers.IO) { model = Model(modelPath) }
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
        val base = File(filesDir, "vosk-models")
        val modelDir = File(base, MODEL_DIR_NAME)
        val marker = File(modelDir, "am/final.mdl")
        if (marker.exists()) return modelDir.absolutePath
        runOnUiThread {
            binding.status.text = getString(R.string.status_downloading)
            binding.progress.visibility = android.view.View.VISIBLE
            binding.progress.progress = 0
        }
        if (!base.exists()) base.mkdirs()
        val zipFile = File(cacheDir, "vosk-model-small-fa-0.42.zip")
        downloadFile(MODEL_URL, zipFile) { pct ->
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
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                Toast.makeText(this, getString(R.string.need_mic), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        model?.close()
        model = null
        super.onDestroy()
    }
}
