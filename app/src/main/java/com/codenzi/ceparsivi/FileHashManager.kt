package com.codenzi.ceparsivi

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object FileHashManager {

    private const val PREFS_NAME = "FileHashes"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hashExists(context: Context, hash: String): Boolean {
        return getPrefs(context).contains(hash)
    }

    fun getFileNameForHash(context: Context, hash: String): String? {
        return getPrefs(context).getString(hash, null)
    }

    fun addHash(context: Context, hash: String, fileName: String) {
        getPrefs(context).edit {
            putString(hash, fileName)
        }
    }

    fun removeHashForFile(context: Context, fileName: String) {
        val prefs = getPrefs(context)
        var hashToRemove: String? = null
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (value == fileName) {
                hashToRemove = key
                break
            }
        }

        hashToRemove?.let {
            prefs.edit {
                remove(it)
            }
        }
    }

    suspend fun calculateMD5(context: Context, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val md = MessageDigest.getInstance("MD5")
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } > 0) {
                        md.update(buffer, 0, read)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e("FileHashManager", "Failed to calculate hash from URI", e)
                null
            }
        }
    }
}
