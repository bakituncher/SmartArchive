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

    // YENİ EKLENDİ: Hafızadaki verileri temizlemek için bir anahtar
    private var isInvalidated = true
    private var categoriesCache: MutableSet<String>? = null
    private var fileMapCache: Map<String, String>? = null

    // YENİ EKLENDİ: Dışarıdan çağrılarak hafızayı temizleyecek fonksiyon
    fun invalidate() {
        isInvalidated = true
        categoriesCache = null
        fileMapCache = null
    }

    private fun loadCategoriesIfNeeded(context: Context) {
        if (!isInvalidated && categoriesCache != null) return

        val prefs = context.getSharedPreferences(PREFS_CATEGORIES, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(KEY_CATEGORY_SET, null)
        categoriesCache = if (savedSet != null) {
            HashSet(savedSet)
        } else {
            val defaultCategories = getDefaultCategories(context)
            prefs.edit { putStringSet(KEY_CATEGORY_SET, defaultCategories) }
            defaultCategories.toMutableSet()
        }
    }

    private fun loadFileMapIfNeeded(context: Context) {
        if (!isInvalidated && fileMapCache != null) return

        val prefs = context.getSharedPreferences(PREFS_FILE_MAP, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_FILE_MAP, "{}")
        val type = object : TypeToken<Map<String, String>>() {}.type
        fileMapCache = gson.fromJson(json, type) ?: emptyMap()
    }

    private fun ensureInitialized(context: Context) {
        if (isInvalidated) {
            loadCategoriesIfNeeded(context)
            loadFileMapIfNeeded(context)
            isInvalidated = false
        }
    }

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

    fun getCategories(context: Context): MutableSet<String> {
        ensureInitialized(context)
        return categoriesCache?.toMutableSet() ?: mutableSetOf()
    }

    fun addCategory(context: Context, categoryName: String): Boolean {
        ensureInitialized(context)
        val categories = categoriesCache ?: return false
        if (categories.any { it.equals(categoryName, ignoreCase = true) }) {
            return false // Kategori zaten var
        }
        categories.add(categoryName)
        context.getSharedPreferences(PREFS_CATEGORIES, Context.MODE_PRIVATE).edit { putStringSet(KEY_CATEGORY_SET, categories) }
        return true
    }

    fun deleteCategory(context: Context, categoryName: String): Boolean {
        ensureInitialized(context)
        if (getDefaultCategories(context).contains(categoryName)) {
            return false // Varsayılan kategoriler silinemez.
        }
        if (getFilesInCategory(context, categoryName).isNotEmpty()) {
            return false // Kategori kullanımda.
        }

        val categories = categoriesCache ?: return false
        categories.remove(categoryName)
        context.getSharedPreferences(PREFS_CATEGORIES, Context.MODE_PRIVATE).edit { putStringSet(KEY_CATEGORY_SET, categories) }
        return true
    }

    fun renameCategory(context: Context, oldName: String, newName: String) {
        ensureInitialized(context)
        // 1. Kategori setini güncelle
        val categories = categoriesCache ?: return
        categories.remove(oldName)
        categories.add(newName)
        context.getSharedPreferences(PREFS_CATEGORIES, Context.MODE_PRIVATE).edit { putStringSet(KEY_CATEGORY_SET, categories) }

        // 2. Bu kategoriyi kullanan tüm dosyaların eşleşmesini güncelle
        val fileMap = fileMapCache?.toMutableMap() ?: return
        val updatedMap = fileMap.toMutableMap()
        fileMap.forEach { (filePath, category) ->
            if (category == oldName) {
                updatedMap[filePath] = newName
            }
        }
        saveFileCategoryMap(context, updatedMap)
    }

    fun getCategoryForFile(context: Context, filePath: String): String? {
        ensureInitialized(context)
        return fileMapCache?.get(filePath)
    }

    fun setCategoryForFile(context: Context, filePath: String, categoryName: String) {
        ensureInitialized(context)
        val fileMap = fileMapCache?.toMutableMap() ?: mutableMapOf()
        fileMap[filePath] = categoryName
        saveFileCategoryMap(context, fileMap)
    }

    fun removeCategoryForFile(context: Context, filePath: String) {
        ensureInitialized(context)
        val fileMap = fileMapCache?.toMutableMap() ?: return
        fileMap.remove(filePath)
        saveFileCategoryMap(context, fileMap)
    }

    fun getFilesInCategory(context: Context, categoryName: String): List<String> {
        ensureInitialized(context)
        return fileMapCache?.filter { it.value == categoryName }?.keys?.toList() ?: emptyList()
    }

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

    private fun saveFileCategoryMap(context: Context, map: Map<String, String>) {
        val json = gson.toJson(map)
        context.getSharedPreferences(PREFS_FILE_MAP, Context.MODE_PRIVATE).edit { putString(PREFS_FILE_MAP, json) }
        fileMapCache = map.toMutableMap()
    }
}