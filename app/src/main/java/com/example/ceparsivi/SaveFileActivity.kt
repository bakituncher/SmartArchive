package com.example.ceparsivi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SaveFileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type != null) {
            val fileUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (fileUri != null) {
                val originalFileName = getFileName(fileUri)
                showSaveDialog(fileUri, originalFileName)
            } else {
                Toast.makeText(this, getString(R.string.file_not_received), Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun showSaveDialog(fileUri: Uri, originalFileName: String) {
        val fileExtension = originalFileName.substringAfterLast('.', "")
        val fileNameWithoutExtension = originalFileName.substringBeforeLast('.', originalFileName)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(50, 40, 50, 0)
        }

        val editTextFileName = EditText(this).apply {
            setText(fileNameWithoutExtension)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }

        val textViewExtension = TextView(this).apply {
            text = if (fileExtension.isNotEmpty()) ".$fileExtension" else ""
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 8
            }
        }

        layout.addView(editTextFileName)
        if (fileExtension.isNotEmpty()) {
            layout.addView(textViewExtension)
        }

        // AlertDialog'u bir değişkende tutarak manuel kapatma kontrolü sağlıyoruz
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.give_file_a_name))
            .setView(layout)
            .setPositiveButton(getString(R.string.save), null) // Listener'ı null yapıyoruz
            .setNegativeButton(getString(R.string.cancel)) { d, _ ->
                d.cancel()
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val newBaseName = editTextFileName.text.toString().trim()
                if (newBaseName.isNotBlank()) {
                    val newName = if (fileExtension.isNotEmpty()) {
                        "$newBaseName.$fileExtension"
                    } else {
                        newBaseName
                    }

                    // --- DÜZELTME: Kaydetmeden önce dosya var mı diye kontrol et ---
                    val outputFile = File(filesDir, "arsiv/$newName")
                    if (outputFile.exists()) {
                        // Dosya varsa, kullanıcıya sor. Diyalog kapanmasın.
                        showOverwriteConfirmationDialog(fileUri, newName, dialog)
                    } else {
                        // Dosya yoksa, direkt kaydet ve tüm diyalogları/aktiviteyi kapat.
                        dialog.dismiss()
                        saveFileAndFinish(fileUri, newName)
                    }
                } else {
                    Toast.makeText(this, getString(R.string.error_invalid_name), Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    // YENİ FONKSİYON: Kullanıcıya üzerine yazma onayı soran diyalog.
    private fun showOverwriteConfirmationDialog(uri: Uri, name: String, parentDialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.file_already_exists))
            .setMessage(getString(R.string.overwrite_confirmation, name))
            .setPositiveButton(getString(R.string.replace)) { _, _ ->
                parentDialog.dismiss() // Ana diyalogu da kapat
                saveFileAndFinish(uri, name)
            }
            .setNegativeButton(getString(R.string.cancel), null) // Sadece bu küçük diyalogu kapatır
            .show()
    }

    // YENİ FONKSİYON: Kod tekrarını önlemek için kaydetme ve bitirme işlemleri.
    private fun saveFileAndFinish(uri: Uri, newName: String) {
        lifecycleScope.launch {
            val success = copyFileToInternalStorage(uri, newName)
            if (success) {
                Toast.makeText(applicationContext, getString(R.string.file_saved_as, newName), Toast.LENGTH_LONG).show()
                val mainActivityIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(mainActivityIntent)
            } else {
                Toast.makeText(applicationContext, getString(R.string.error_file_not_saved), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    // Arka planda çalışması için suspend olarak işaretlendi
    private suspend fun copyFileToInternalStorage(uri: Uri, newName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val outputDir = File(filesDir, "arsiv")
                    if (!outputDir.exists()) {
                        outputDir.mkdirs()
                    }
                    val outputFile = File(outputDir, newName)
                    FileOutputStream(outputFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d("SaveFileActivity", "Dosya başarıyla kaydedildi: ${File(filesDir, "arsiv/$newName").absolutePath}")
                true
            } catch (e: Exception) {
                Log.e("SaveFileActivity", "Dosya kopyalanamadı", e)
                false
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (columnIndex >= 0) {
                            result = it.getString(columnIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SaveFileActivity", "ContentResolver ile dosya adı alınamadı", e)
            }
        }

        if (result == null) {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) {
                    path.substring(cut + 1)
                } else {
                    path
                }
            }
        }
        return result ?: "isimsiz_dosya"
    }
}
