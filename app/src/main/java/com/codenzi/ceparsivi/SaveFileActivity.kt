package com.codenzi.ceparsivi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SaveFileActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        progressBar = ProgressBar(this).apply {
            isVisible = false
        }
        setContentView(progressBar)


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

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.give_file_a_name))
            .setView(layout)
            .setPositiveButton(getString(R.string.save), null)
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

                    val newName = if (fileExtension.isNotEmpty()) "$newBaseName.$fileExtension" else newBaseName
                    handleSaveRequest(fileUri, newName, dialog)
                } else {
                    Toast.makeText(this, getString(R.string.error_invalid_name), Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun handleSaveRequest(uri: Uri, newName: String, parentDialog: AlertDialog) {
        progressBar.isVisible = true
        lifecycleScope.launch {
            val hash = FileHashManager.calculateMD5(this@SaveFileActivity, uri)
            withContext(Dispatchers.Main) {
                progressBar.isVisible = false
                if (hash != null && FileHashManager.hashExists(this@SaveFileActivity, hash)) {
                    val existingFileName = FileHashManager.getFileNameForHash(this@SaveFileActivity, hash)
                    showDuplicateContentDialog(uri, newName, existingFileName ?: "", parentDialog)
                } else {
                    checkForNameAndSave(uri, newName, hash, parentDialog)
                }
            }
        }
    }

    private fun checkForNameAndSave(uri: Uri, newName: String, hash: String?, parentDialog: AlertDialog) {
        val outputFile = File(filesDir, "arsiv/$newName")
        if (outputFile.exists()) {
            showOverwriteConfirmationDialog(uri, newName, hash, parentDialog)
        } else {
            parentDialog.dismiss()
            saveFileAndFinish(uri, newName, hash)
        }
    }

    private fun showDuplicateContentDialog(uri: Uri, newName: String, existingFileName: String, parentDialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.duplicate_file_found))
            .setMessage(getString(R.string.duplicate_file_message, existingFileName, newName))
            .setPositiveButton(getString(R.string.action_save_copy)) { _, _ ->
                checkForNameAndSave(uri, newName, null, parentDialog)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                parentDialog.dismiss()
                finish()
            }
            .setOnCancelListener {
                parentDialog.dismiss()
                finish()
            }
            .show()
    }

    private fun showOverwriteConfirmationDialog(uri: Uri, name: String, hash: String?, parentDialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.file_already_exists))
            .setMessage(getString(R.string.overwrite_confirmation, name))
            .setPositiveButton(getString(R.string.replace)) { _, _ ->
                parentDialog.dismiss()
                FileHashManager.removeHashForFile(this, name)
                saveFileAndFinish(uri, name, hash)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                parentDialog.dismiss()
                finish()
            }
            .setOnCancelListener {
                parentDialog.dismiss()
                finish()
            }
            .show()
    }

    private fun saveFileAndFinish(uri: Uri, newName: String, hash: String?) {
        lifecycleScope.launch {
            progressBar.isVisible = true
            val success = copyFileToInternalStorage(uri, newName)
            withContext(Dispatchers.Main) {
                progressBar.isVisible = false
                if (success) {
                    hash?.let { FileHashManager.addHash(applicationContext, it, newName) }
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
    }

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
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result?.takeIf { it.isNotBlank() } ?: "isimsiz_dosya_${System.currentTimeMillis()}"
    }
}
