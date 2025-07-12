package com.codenzi.ceparsivi

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.google.api.services.drive.model.File as DriveFile

class GoogleDriveHelper(private val context: Context, account: GoogleSignInAccount) {

    private val drive: Drive
    private val appFolderName = "SmartArchiveBackup"
    private val backupFileName = "smart_archive_backup.zip"
    private val prefsToBackup = arrayOf("AppCategories", "FileCategoryMapping", "FileHashes", "ThemePrefs")
    private val TAG = "GoogleDriveHelper"

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_FILE)
        ).setSelectedAccount(account.account)

        drive = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(context.getString(R.string.app_name)).build()
    }

    private suspend fun getAppFolderId(): String? = withContext(Dispatchers.IO) {
        try {
            val query = "mimeType='application/vnd.google-apps.folder' and name='$appFolderName' and trashed=false"
            val result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
            if (result.files.isNotEmpty()) {
                Log.d(TAG, "App folder found with ID: ${result.files[0].id}")
                result.files[0].id
            } else {
                Log.d(TAG, "App folder not found, creating new one")
                val folderMetadata = DriveFile().apply {
                    name = appFolderName
                    mimeType = "application/vnd.google-apps.folder"
                }
                val createdFolder = drive.files().create(folderMetadata).setFields("id").execute()
                Log.d(TAG, "New app folder created with ID: ${createdFolder.id}")
                createdFolder.id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting/creating app folder: ${e.message}", e)
            null
        }
    }

    suspend fun getBackupDate(): String? = withContext(Dispatchers.IO) {
        try {
            val appFolderId = getAppFolderId() ?: return@withContext null
            val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
            val result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(modifiedTime)").execute()
            if (result.files.isNotEmpty()) {
                val modifiedTime = result.files[0].modifiedTime.value
                val date = Date(modifiedTime)
                val formattedDate = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()).format(date)
                Log.d(TAG, "Backup found with date: $formattedDate")
                formattedDate
            } else {
                Log.d(TAG, "No backup file found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting backup date: ${e.message}", e)
            null
        }
    }

    suspend fun backupData(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting backup process")
            val appFolderId = getAppFolderId() ?: return@withContext false
            
            val zipFile = java.io.File(context.cacheDir, backupFileName)
            if (zipFile.exists()) {
                zipFile.delete()
            }
            
            Log.d(TAG, "Creating backup zip file")
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    // Backup archive directory
                    val archiveDir = java.io.File(context.filesDir, "arsiv")
                    if (archiveDir.exists() && archiveDir.isDirectory) {
                        Log.d(TAG, "Backing up ${archiveDir.listFiles()?.size ?: 0} files from archive")
                        archiveDir.listFiles()?.forEach { file ->
                            val entry = ZipEntry("arsiv/${file.name}")
                            zos.putNextEntry(entry)
                            FileInputStream(file).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    
                    // Backup shared preferences
                    prefsToBackup.forEach { prefName ->
                        val prefsFile = java.io.File("${context.applicationInfo.dataDir}/shared_prefs/$prefName.xml")
                        if (prefsFile.exists()) {
                            Log.d(TAG, "Backing up preferences: $prefName")
                            val entry = ZipEntry("shared_prefs/$prefName.xml")
                            zos.putNextEntry(entry)
                            FileInputStream(prefsFile).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            
            Log.d(TAG, "Uploading backup to Google Drive")
            val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
            val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
            val fileMetadata = DriveFile().apply { name = backupFileName }
            val mediaContent = FileContent("application/zip", zipFile)
            
            if (fileList.files.isEmpty()) {
                fileMetadata.parents = listOf(appFolderId)
                drive.files().create(fileMetadata, mediaContent).execute()
                Log.d(TAG, "New backup file created on Google Drive")
            } else {
                val fileId = fileList.files[0].id
                drive.files().update(fileId, fileMetadata, mediaContent).execute()
                Log.d(TAG, "Existing backup file updated on Google Drive")
            }
            
            zipFile.delete()
            Log.d(TAG, "Backup completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during backup: ${e.message}", e)
            false
        }
    }

    suspend fun restoreData(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting restore process")
            val appFolderId = getAppFolderId() ?: return@withContext false
            val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
            val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()

            if (fileList.files.isEmpty()) {
                Log.e(TAG, "No backup file found to restore")
                return@withContext false
            }

            val fileId = fileList.files[0].id
            val zipFile = java.io.File(context.cacheDir, "restore.zip")
            if (zipFile.exists()) {
                zipFile.delete()
            }
            
            Log.d(TAG, "Downloading backup from Google Drive")
            FileOutputStream(zipFile).use { fos ->
                drive.files().get(fileId).executeMediaAndDownloadTo(fos)
            }
            
            if (!zipFile.exists() || zipFile.length() == 0L) {
                Log.e(TAG, "Downloaded backup file is empty or corrupted")
                return@withContext false
            }
            
            Log.d(TAG, "Creating backup of current data before restore")
            val backupDir = java.io.File(context.cacheDir, "temp_backup")
            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            }
            backupDir.mkdirs()
            
            // Backup current data temporarily
            val archiveDir = java.io.File(context.filesDir, "arsiv")
            if (archiveDir.exists()) {
                archiveDir.copyRecursively(java.io.File(backupDir, "arsiv"))
            }
            
            // Backup preferences
            prefsToBackup.forEach { prefName ->
                val prefsFile = java.io.File("${context.applicationInfo.dataDir}/shared_prefs/$prefName.xml")
                if (prefsFile.exists()) {
                    val prefBackupDir = java.io.File(backupDir, "shared_prefs")
                    prefBackupDir.mkdirs()
                    prefsFile.copyTo(java.io.File(prefBackupDir, "$prefName.xml"))
                }
            }
            
            try {
                Log.d(TAG, "Extracting backup data")
                // Now clean local data and restore
                cleanLocalData()
                
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val outputFile = java.io.File(context.applicationInfo.dataDir, entry.name)
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            Log.d(TAG, "Restored file: ${entry.name}")
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                
                zipFile.delete()
                backupDir.deleteRecursively() // Clean up temp backup since restore was successful
                Log.d(TAG, "Restore completed successfully")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during restore extraction, restoring from temp backup: ${e.message}", e)
                // Restore from temp backup
                val tempArchiveDir = java.io.File(backupDir, "arsiv")
                if (tempArchiveDir.exists()) {
                    tempArchiveDir.copyRecursively(java.io.File(context.filesDir, "arsiv"))
                }
                
                val tempPrefsDir = java.io.File(backupDir, "shared_prefs")
                if (tempPrefsDir.exists()) {
                    tempPrefsDir.listFiles()?.forEach { prefFile ->
                        val targetFile = java.io.File("${context.applicationInfo.dataDir}/shared_prefs/${prefFile.name}")
                        prefFile.copyTo(targetFile, overwrite = true)
                    }
                }
                
                backupDir.deleteRecursively()
                zipFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during restore: ${e.message}", e)
            false
        }
    }

    suspend fun deleteAllData() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting delete all data process")
                cleanLocalData()
                val appFolderId = getAppFolderId()
                if (appFolderId != null) {
                    val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
                    val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
                    if (fileList.files.isNotEmpty()) {
                        val fileId = fileList.files[0].id
                        drive.files().delete(fileId).execute()
                        Log.d(TAG, "Backup file deleted from Google Drive")
                    }
                }
                Log.d(TAG, "All data deleted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting all data: ${e.message}", e)
            }
        }
    }

    private fun cleanLocalData() {
        try {
            Log.d(TAG, "Cleaning local data")
            val archiveDir = java.io.File(context.filesDir, "arsiv")
            if (archiveDir.exists()) {
                archiveDir.deleteRecursively()
                Log.d(TAG, "Archive directory cleaned")
            }
            prefsToBackup.forEach { prefName ->
                context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().apply()
                Log.d(TAG, "Preferences cleaned: $prefName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning local data: ${e.message}", e)
        }
    }
}