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
                Toast.makeText(this, "Dosya alınamadı.", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun showSaveDialog(fileUri: Uri, originalFileName: String) {
        // Dosya adını ve uzantısını ayır
        val fileExtension = originalFileName.substringAfterLast('.', "")
        val fileNameWithoutExtension = originalFileName.substringBeforeLast('.', originalFileName)

        // Dialog için özel bir layout oluştur
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


        AlertDialog.Builder(this)
            .setTitle("Dosyaya Bir İsim Ver")
            .setView(layout)
            .setPositiveButton("Kaydet") { dialog, _ ->
                val newBaseName = editTextFileName.text.toString().trim()
                if (newBaseName.isNotBlank()) {
                    val newName = if (fileExtension.isNotEmpty()) {
                        "$newBaseName.$fileExtension"
                    } else {
                        newBaseName
                    }

                    if (copyFileToInternalStorage(fileUri, newName)) {
                        Toast.makeText(this, "'$newName' adıyla kaydedildi!", Toast.LENGTH_LONG).show()
                        // MainActivity'yi başlatarak listenin güncellenmesini tetikle
                        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(mainActivityIntent)
                    } else {
                        Toast.makeText(this, "Hata: Dosya kaydedilemedi.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Lütfen geçerli bir isim girin.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("İptal") { dialog, _ ->
                dialog.cancel()
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun copyFileToInternalStorage(uri: Uri, newName: String): Boolean {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val outputDir = File(filesDir, "arsiv")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            val outputFile = File(outputDir, newName)
            val outputStream = FileOutputStream(outputFile)

            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("SaveFileActivity", "Dosya başarıyla kaydedildi: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("SaveFileActivity", "Dosya kopyalanamadı", e)
            false
        }
    }

    // --- DÜZELTME ---
    // Gereksiz kontrol kaldırıldı ve fonksiyon daha güvenli hale getirildi.
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        // İlk olarak en güvenilir yöntem olan content resolver'ı dene
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(columnIndex >= 0) {
                        result = it.getString(columnIndex)
                    }
                }
            }
        }

        // Eğer ilk yöntem işe yaramazsa, dosya yolunu (path) ayrıştırmayı dene.
        if (result == null) {
            // uri.path null olabilir, bu yüzden '?' ile güvenli çağrı yapıyoruz.
            result = uri.path?.let { path ->
                // lastIndexOf, '/' bulamazsa -1 döner.
                val cut = path.lastIndexOf('/')
                if (cut != -1) {
                    // Eğer '/' bulunduysa, ondan sonraki kısmı al.
                    path.substring(cut + 1)
                } else {
                    // Bulunmadıysa, yolun kendisini dosya adı olarak kabul et.
                    path
                }
            }
        }

        // Sonuç hala null ise, varsayılan bir isim döndür.
        return result ?: "isimsiz_dosya"
    }
}