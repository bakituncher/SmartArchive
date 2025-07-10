package com.codenzi.ceparsivi

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Uygulama içindeki kategorileri ve dosya-kategori eşleşmelerini yöneten singleton nesne.
 * Verileri SharedPreferences üzerinde saklar.
 */
object CategoryManager {

    private const val PREFS_CATEGORIES = "AppCategories"
    private const val KEY_CATEGORY_SET = "user_defined_categories_set"

    private const val PREFS_FILE_MAP = "FileCategoryMapping"
    private val gson = Gson()

    // Kategori listesini yöneten SharedPreferences
    private fun getCategoryPrefs(context: Context) =
        context.getSharedPreferences(PREFS_CATEGORIES, Context.MODE_PRIVATE)

    // Dosya-kategori haritasını yöneten SharedPreferences
    private fun getFileMapPrefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE_MAP, Context.MODE_PRIVATE)

    /**
     * Varsayılan kategorileri döndürür.
     */
    internal fun getDefaultCategories(context: Context): Set<String> {
        return setOf(
            context.getString(R.string.category_office),
            context.getString(R.string.category_images),
            context.getString(R.string.category_videos),
            context.getString(R.string.category_audio),
            context.getString(R.string.category_archives),
            context.getString(R.string.category_other)
        )
    }

    /**
     * Kullanıcı tanımlı ve varsayılan tüm kategorileri bir set olarak döndürür.
     * DÜZELTME: SharedPreferences'ten dönen orijinal setin değiştirilmemesi için
     * her zaman yeni bir kopya oluşturulup döndürülüyor. Bu, veri kaybı sorununu çözer.
     */
    fun getCategories(context: Context): MutableSet<String> {
        val prefs = getCategoryPrefs(context)
        val savedSet = prefs.getStringSet(KEY_CATEGORY_SET, null)
        return if (savedSet != null) {
            HashSet(savedSet) // Orijinal set yerine kopyasını döndür
        } else {
            val defaultCategories = getDefaultCategories(context)
            prefs.edit { putStringSet(KEY_CATEGORY_SET, defaultCategories) }
            defaultCategories.toMutableSet()
        }
    }

    /**
     * Kategori listesine yeni bir kategori ekler.
     * @return Ekleme başarılıysa true, kategori zaten varsa false döner.
     */
    fun addCategory(context: Context, categoryName: String): Boolean {
        val categories = getCategories(context)
        if (categories.any { it.equals(categoryName, ignoreCase = true) }) {
            return false // Kategori zaten var
        }
        categories.add(categoryName)
        getCategoryPrefs(context).edit { putStringSet(KEY_CATEGORY_SET, categories) }
        return true
    }

    /**
     * Bir kategoriyi siler.
     * @return Silme başarılıysa true, kategori varsayılan veya kullanımda olduğu için silinemiyorsa false döner.
     */
    fun deleteCategory(context: Context, categoryName: String): Boolean {
        if (getDefaultCategories(context).contains(categoryName)) {
            return false // Varsayılan kategoriler silinemez.
        }
        if (getFilesInCategory(context, categoryName).isNotEmpty()) {
            return false // Kategori kullanımda.
        }

        val categories = getCategories(context)
        categories.remove(categoryName)
        getCategoryPrefs(context).edit { putStringSet(KEY_CATEGORY_SET, categories) }
        return true
    }

    /**
     * Bir kategoriyi yeniden adlandırır.
     */
    fun renameCategory(context: Context, oldName: String, newName: String) {
        // 1. Kategori setini güncelle
        val categories = getCategories(context)
        categories.remove(oldName)
        categories.add(newName)
        getCategoryPrefs(context).edit { putStringSet(KEY_CATEGORY_SET, categories) }

        // 2. Bu kategoriyi kullanan tüm dosyaların eşleşmesini güncelle
        val fileMap = getFileCategoryMap(context)
        val updatedMap = fileMap.toMutableMap()
        fileMap.forEach { (filePath, category) ->
            if (category == oldName) {
                updatedMap[filePath] = newName
            }
        }
        saveFileCategoryMap(context, updatedMap)
    }

    /**
     * Belirli bir dosyanın hangi kategoriye ait olduğunu döndürür.
     */
    fun getCategoryForFile(context: Context, filePath: String): String? {
        return getFileCategoryMap(context)[filePath]
    }

    /**
     * Bir dosya için kategori ataması yapar.
     */
    fun setCategoryForFile(context: Context, filePath: String, categoryName: String) {
        val fileMap = getFileCategoryMap(context).toMutableMap()
        fileMap[filePath] = categoryName
        saveFileCategoryMap(context, fileMap)
    }

    /**
     * Bir dosyanın kategori atamasını kaldırır (dosya silindiğinde kullanılır).
     */
    fun removeCategoryForFile(context: Context, filePath: String) {
        val fileMap = getFileCategoryMap(context).toMutableMap()
        fileMap.remove(filePath)
        saveFileCategoryMap(context, fileMap)
    }

    /**
     * Belirli bir kategorideki tüm dosyaların yollarını döndürür.
     */
    fun getFilesInCategory(context: Context, categoryName: String): List<String> {
        return getFileCategoryMap(context).filter { it.value == categoryName }.keys.toList()
    }

    /**
     * Bir dosya adı uzantısına göre varsayılan kategori adını döndürür.
     */
    fun getDefaultCategoryNameByExtension(context: Context, fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt" -> context.getString(R.string.category_office)
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> context.getString(R.string.category_images)
            "mp4", "mkv", "avi", "mov", "3gp", "webm" -> context.getString(R.string.category_videos)
            "mp3", "wav", "m4a", "aac", "flac", "ogg" -> context.getString(R.string.category_audio)
            "zip", "rar", "7z", "tar", "gz" -> context.getString(R.string.category_archives)
            else -> context.getString(R.string.category_other)
        }
    }

    // --- Private Helper Fonksiyonlar ---

    private fun getFileCategoryMap(context: Context): Map<String, String> {
        val json = getFileMapPrefs(context).getString(PREFS_FILE_MAP, "{}")
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(json, type) ?: emptyMap()
    }

    private fun saveFileCategoryMap(context: Context, map: Map<String, String>) {
        val json = gson.toJson(map)
        getFileMapPrefs(context).edit { putString(PREFS_FILE_MAP, json) }
    }
}