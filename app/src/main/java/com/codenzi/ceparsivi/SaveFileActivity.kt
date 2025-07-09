package com.codenzi.ceparsivi

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.codenzi.ceparsivi.databinding.DialogSaveFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SaveFileActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressBar = ProgressBar(this).apply { isVisible = false }
        setContentView(progressBar)

        val fileUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (intent?.action == Intent.ACTION_SEND && fileUri != null) {
            showSaveDialog(fileUri)
        } else {
            finishWithResult(Activity.RESULT_CANCELED)
        }
    }

    private fun showSaveDialog(fileUri: Uri) {
        val originalFileName = getFileName(fileUri)
        val binding = DialogSaveFileBinding.inflate(LayoutInflater.from(this))
        val fileNameWithoutExtension = originalFileName.substringBeforeLast('.', originalFileName)
        val fileExtension = originalFileName.substringAfterLast('.', "")

        binding.editTextFileName.setText(fileNameWithoutExtension)
        binding.textViewExtension.text = if (fileExtension.isNotEmpty()) ".$fileExtension" else ""

        setupPreview(binding, fileUri, fileExtension)

        val dialog = AlertDialog.Builder(this)
            .setView(binding.root)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> finishWithResult(Activity.RESULT_CANCELED) }
            .setOnCancelListener { finishWithResult(Activity.RESULT_CANCELED) }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newBaseName = binding.editTextFileName.text.toString().trim()
                if (newBaseName.isNotBlank()) {
                    val newName = if (fileExtension.isNotEmpty()) "$newBaseName.$fileExtension" else newBaseName
                    dialog.dismiss()
                    processSaveRequest(fileUri, newName)
                } else {
                    Toast.makeText(this, getString(R.string.error_invalid_name), Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun processSaveRequest(uri: Uri, newName: String) {
        lifecycleScope.launch {
            progressBar.isVisible = true

            // Adım 1: İçerik kontrolü (Hash)
            val hash = FileHashManager.calculateMD5(this@SaveFileActivity, uri)
            if (hash != null && FileHashManager.hashExists(this@SaveFileActivity, hash)) {
                withContext(Dispatchers.Main) {
                    val existingFileName = FileHashManager.getFileNameForHash(this@SaveFileActivity, hash) ?: newName
                    Toast.makeText(this@SaveFileActivity, "Bu dosya zaten '$existingFileName' adıyla mevcut.", Toast.LENGTH_LONG).show()
                    finishWithResult(Activity.RESULT_CANCELED)
                }
                return@launch
            }

            // Adım 2: İsim kontrolü
            val outputFile = File(filesDir, "arsiv/$newName")
            if (outputFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SaveFileActivity, "'$newName' adında bir dosya zaten var. Lütfen farklı bir isim seçin.", Toast.LENGTH_LONG).show()
                    finishWithResult(Activity.RESULT_CANCELED)
                }
                return@launch
            }

            saveFileAndFinish(uri, newName, hash)
        }
    }

    private fun saveFileAndFinish(uri: Uri, newName: String, hash: String?) {
        lifecycleScope.launch {
            val success = copyFileToInternalStorage(uri, newName)
            withContext(Dispatchers.Main) {
                progressBar.isVisible = false
                if (success) {
                    hash?.let { FileHashManager.addHash(applicationContext, it, newName) }
                    Toast.makeText(applicationContext, getString(R.string.file_saved_as, newName), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(applicationContext, getString(R.string.error_file_not_saved), Toast.LENGTH_LONG).show()
                }
                finishWithResult(Activity.RESULT_OK)
            }
        }
    }

    private fun setupPreview(binding: DialogSaveFileBinding, uri: Uri, extension: String) {
        val mimeType = contentResolver.getType(uri) ?: ""
        val imageViewPreview = binding.imageViewPreview
        when {
            mimeType.startsWith("image/") -> {
                imageViewPreview.isVisible = true
                imageViewPreview.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(this).load(uri).into(imageViewPreview)
            }
            mimeType.startsWith("video/") -> {
                imageViewPreview.isVisible = true
                imageViewPreview.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(this).load(uri).placeholder(R.drawable.ic_file_video).into(imageViewPreview)
            }
            extension.equals("pdf", ignoreCase = true) -> {
                imageViewPreview.isVisible = true
                lifecycleScope.launch {
                    val bitmap = renderPdfThumbnail(uri)
                    if (bitmap != null) {
                        imageViewPreview.setImageBitmap(bitmap)
                    } else {
                        imageViewPreview.setImageResource(R.drawable.ic_file_pdf)
                    }
                }
            }
            else -> {
                imageViewPreview.isVisible = true
                val categoryResId = getFileCategoryResId(extension)
                imageViewPreview.setImageResource(getFileIcon(categoryResId, extension))
            }
        }
    }

    private suspend fun renderPdfThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val renderer = PdfRenderer(pfd)
                renderer.openPage(0).use { page ->
                    val bitmap = createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return@withContext bitmap
                }
            }
            return@withContext null
        } catch (e: Exception) {
            Log.e("SaveFileActivity", "PDF önizlemesi oluşturulamadı", e)
            return@withContext null
        }
    }

    private suspend fun copyFileToInternalStorage(uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val outputDir = File(filesDir, "arsiv")
                if (!outputDir.exists()) outputDir.mkdirs()
                FileOutputStream(File(outputDir, newName)).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("SaveFileActivity", "Dosya kopyalanamadı", e)
            false
        }
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val colIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (colIndex >= 0) return it.getString(colIndex)
            }
        }
        return uri.path?.let { File(it).name } ?: "isimsiz_dosya_${System.currentTimeMillis()}"
    }

    private fun finishWithResult(resultCode: Int) {
        setResult(resultCode)
        finish()
    }

    @StringRes
    private fun getFileCategoryResId(extension: String): Int {
        return when (extension.lowercase()) {
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt" -> R.string.category_office
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> R.string.category_images
            "mp4", "mkv", "avi", "mov", "3gp", "webm" -> R.string.category_videos
            "mp3", "wav", "m4a", "aac", "flac", "ogg" -> R.string.category_audio
            "zip", "rar", "7z", "tar", "gz" -> R.string.category_archives
            else -> R.string.category_other
        }
    }

    private fun getFileIcon(categoryResId: Int, extension: String): Int {
        if (categoryResId == R.string.category_office) {
            return when (extension.lowercase()) {
                "pdf" -> R.drawable.ic_file_pdf
                "doc", "docx" -> R.drawable.ic_file_doc
                "ppt", "pptx" -> R.drawable.ic_file_doc
                else -> R.drawable.ic_file_generic
            }
        }
        return when (categoryResId) {
            R.string.category_images -> R.drawable.ic_file_image
            R.string.category_videos -> R.drawable.ic_file_video
            R.string.category_audio -> R.drawable.ic_file_audio
            R.string.category_archives -> R.drawable.ic_file_archive
            else -> R.drawable.ic_file_generic
        }
    }
}