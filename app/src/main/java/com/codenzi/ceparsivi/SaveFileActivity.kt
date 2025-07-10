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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
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

class SaveFileActivity : AppCompatActivity(), CategoryEntryDialogFragment.CategoryDialogListener {

    private lateinit var progressBar: ProgressBar
    private lateinit var binding: DialogSaveFileBinding
    private lateinit var categoryAdapter: ArrayAdapter<String>

    private lateinit var addNewCategoryString: String
    private lateinit var selectCategoryString: String

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

    override fun onCategorySaved(newName: String, oldName: String?) {
        if (CategoryManager.addCategory(this, newName)) {
            updateCategoryDropdown()
            // DÜZELTME: Yeni eklenen kategoriyi seçili hale getir.
            binding.autoCompleteCategory.setText(newName, false)
        } else {
            Toast.makeText(this, getString(R.string.category_already_exists), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveDialog(fileUri: Uri) {
        val originalFileName = getFileName(fileUri)
        binding = DialogSaveFileBinding.inflate(LayoutInflater.from(this))
        val fileNameWithoutExtension = originalFileName.substringBeforeLast('.', originalFileName)
        val fileExtension = originalFileName.substringAfterLast('.', "")

        binding.editTextFileName.setText(fileNameWithoutExtension)
        binding.textViewExtension.text = if (fileExtension.isNotEmpty()) ".$fileExtension" else ""

        setupCategoryDropdown(fileUri)
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
                // DÜZELTME: Seçili metin AutoCompleteTextView'dan alınıyor.
                val selectedItem = binding.autoCompleteCategory.text.toString()
                val finalCategory: String

                if (newBaseName.isBlank()) {
                    binding.textInputLayout.error = getString(R.string.error_invalid_name)
                    return@setOnClickListener
                } else {
                    binding.textInputLayout.error = null
                }

                if (selectedItem.isBlank() || selectedItem == selectCategoryString) {
                    finalCategory = CategoryManager.getDefaultCategoryNameByExtension(this, originalFileName)
                    Toast.makeText(this, "Kategori otomatik atandı: $finalCategory", Toast.LENGTH_LONG).show()
                } else {
                    finalCategory = selectedItem
                }

                val newName = if (fileExtension.isNotEmpty()) "$newBaseName.$fileExtension" else newBaseName
                dialog.dismiss()
                processSaveRequest(fileUri, newName, finalCategory)
            }
        }
        dialog.show()
    }

    private fun setupCategoryDropdown(fileUri: Uri) {
        addNewCategoryString = getString(R.string.add_new_category_spinner)
        selectCategoryString = getString(R.string.select_a_category)

        // DÜZELTME: ArrayAdapter artık AutoCompleteTextView ile kullanılıyor.
        categoryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        binding.autoCompleteCategory.setAdapter(categoryAdapter)

        updateCategoryDropdown()

        // DÜZELTME: Yeni kategori ekleme mantığı OnItemClickListener ile yönetiliyor.
        binding.autoCompleteCategory.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position).toString()
            if (selected == addNewCategoryString) {
                val dialog = CategoryEntryDialogFragment.newInstance(null)
                dialog.listener = this@SaveFileActivity
                dialog.show(supportFragmentManager, "CategoryEntryDialog")
                // Kullanıcı seçimden vazgeçerse diye metni temizle.
                binding.autoCompleteCategory.setText("", false)
            }
        }
    }

    private fun updateCategoryDropdown() {
        val allCategories = CategoryManager.getCategories(this)
        val defaultCategories = CategoryManager.getDefaultCategories(this)
        val userCategories = allCategories.filter { !defaultCategories.contains(it) }.sorted()

        val dropdownList = mutableListOf(selectCategoryString)
        dropdownList.addAll(userCategories)
        dropdownList.add(addNewCategoryString)

        categoryAdapter.clear()
        categoryAdapter.addAll(dropdownList)
        categoryAdapter.notifyDataSetChanged()
    }

    // ... (Geri kalan fonksiyonlar aynı, değişiklik yapmaya gerek yok)
    private fun processSaveRequest(uri: Uri, newName: String, category: String) {
        lifecycleScope.launch {
            progressBar.isVisible = true
            val hash = FileHashManager.calculateMD5(this@SaveFileActivity, uri)
            if (hash != null && FileHashManager.hashExists(this@SaveFileActivity, hash)) {
                withContext(Dispatchers.Main) {
                    val existingFileName = FileHashManager.getFileNameForHash(this@SaveFileActivity, hash) ?: newName
                    Toast.makeText(this@SaveFileActivity, "Bu dosya zaten '$existingFileName' adıyla mevcut.", Toast.LENGTH_LONG).show()
                    finishWithResult(Activity.RESULT_CANCELED)
                }
                return@launch
            }

            val outputFile = File(filesDir, "arsiv/$newName")
            if (outputFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SaveFileActivity, "'$newName' adında bir dosya zaten var.", Toast.LENGTH_LONG).show()
                    finishWithResult(Activity.RESULT_CANCELED)
                }
                return@launch
            }

            saveFileAndFinish(uri, newName, category, hash)
        }
    }

    private fun saveFileAndFinish(uri: Uri, newName: String, category: String, hash: String?) {
        lifecycleScope.launch {
            val success = copyFileToInternalStorage(uri, newName)
            withContext(Dispatchers.Main) {
                progressBar.isVisible = false
                if (success) {
                    val filePath = File(filesDir, "arsiv/$newName").absolutePath
                    hash?.let { FileHashManager.addHash(applicationContext, it, newName) }
                    CategoryManager.setCategoryForFile(applicationContext, filePath, category)
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
                val defaultCategory = CategoryManager.getDefaultCategoryNameByExtension(this, getFileName(uri))
                imageViewPreview.setImageResource(getIconForDefaultCategory(defaultCategory, extension))
            }
        }
    }

    private suspend fun renderPdfThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap = createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return@withContext bitmap
                    }
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

    private fun getIconForDefaultCategory(categoryName: String, fileName: String): Int {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (categoryName == getString(R.string.category_office)) {
            return when (extension) {
                "pdf" -> R.drawable.ic_file_pdf
                "doc", "docx" -> R.drawable.ic_file_doc
                "ppt", "pptx" -> R.drawable.ic_file_doc
                else -> R.drawable.ic_file_generic
            }
        }
        return when (categoryName) {
            getString(R.string.category_images) -> R.drawable.ic_file_image
            getString(R.string.category_videos) -> R.drawable.ic_file_video
            getString(R.string.category_audio) -> R.drawable.ic_file_audio
            getString(R.string.category_archives) -> R.drawable.ic_file_archive
            else -> R.drawable.ic_file_generic
        }
    }
}