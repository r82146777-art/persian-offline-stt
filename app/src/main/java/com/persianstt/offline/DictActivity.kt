package com.persianstt.offline

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedReader
import java.io.InputStreamReader

class DictActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val words = mutableListOf<String>()
    private val pickFile = 4401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dict)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        listView = findViewById(R.id.wordList)
        reload()

        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "text/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "فایل دیکت"), pickFile)
        }
        findViewById<MaterialButton>(R.id.btnClearCustom).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("بازنشانی دیکشنری")
                .setMessage("همه کلمات سفارشی پاک شود و فقط لیست پیش‌فرض بماند؟")
                .setPositiveButton("بله") { _, _ ->
                    getSharedPreferences("hamdel_dict", MODE_PRIVATE).edit().remove("words").apply()
                    reload()
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(this, "بازنشانی شد — موتور با دیکت جدید کار می‌کند", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("لغو", null)
                .show()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val w = words[position]
            if (w == "[unk]") {
                Toast.makeText(this, "این مورد سیستمی است", Toast.LENGTH_SHORT).show()
                return@setOnItemLongClickListener true
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("حذف کلمه")
                .setMessage("«$w» حذف شود؟")
                .setPositiveButton("حذف") { _, _ ->
                    DictStore.removeWord(this, w)
                    reload()
                    setResult(Activity.RESULT_OK)
                }
                .setNegativeButton("لغو", null)
                .show()
            true
        }
    }

    private fun reload() {
        words.clear()
        words.addAll(DictStore.getWords(this).sortedWith(compareBy({ it == "[unk]" }, { it })))
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, words)
        listView.adapter = adapter
        supportActionBar?.subtitle = "${words.size} کلمه"
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "کلمه جدید"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("افزودن به دیکت")
            .setView(input)
            .setPositiveButton("افزودن") { _, _ ->
                val w = input.text?.toString()?.trim().orEmpty()
                if (w.isNotEmpty()) {
                    DictStore.addWord(this, w)
                    reload()
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(this, "اضافه شد — موتور به‌روز می‌شود", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFile || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                val imported = mutableListOf<String>()
                reader.lineSequence().forEach { line ->
                    line.split(',', '،', ' ', '\t', ';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && it != "[unk]" }
                        .forEach { imported.add(it) }
                }
                if (imported.isNotEmpty()) {
                    DictStore.addWords(this, imported)
                    reload()
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(this, "${imported.size} کلمه از فایل اضافه شد", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "کلمه‌ای در فایل نبود", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در خواندن فایل: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
