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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.google.api.services.drive.model.File as DriveFile

class GoogleDriveHelper(private val context: Context, account: GoogleSignInAccount) {

    private val drive: Drive
    private val appFolderName = "SmartArchiveBackup"
    private val backupFileName = "smart_archive_backup.zip"
    private val prefsToBackup = arrayOf("AppCategories", "FileCategoryMapping", "FileHashes", "ThemePrefs", "AppPrefs")

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
                result.files[0].id
            } else {
                val folderMetadata = DriveFile().apply {
                    name = appFolderName
                    mimeType = "application/vnd.google-apps.folder"
                }
                val createdFolder = drive.files().create(folderMetadata).setFields("id").execute()
                createdFolder.id
            }
        } catch (e: Exception) {
            Log.e("DriveHelper", "Error getting/creating app folder", e)
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
                SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()).format(date)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("DriveHelper", "Error getting backup date", e)
            null
        }
    }

    suspend fun backupData(): Boolean = withContext(Dispatchers.IO) {
        try {
            val appFolderId = getAppFolderId() ?: return@withContext false
            val zipFile = createBackupZip() ?: return@withContext false

            val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
            val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()

            val fileMetadata = DriveFile().apply { name = backupFileName }
            val mediaContent = FileContent("application/zip", zipFile)

            if (fileList.files.isEmpty()) {
                fileMetadata.parents = listOf(appFolderId)
                drive.files().create(fileMetadata, mediaContent).execute()
            } else {
                val fileId = fileList.files[0].id
                drive.files().update(fileId, fileMetadata, mediaContent).execute()
            }
            zipFile.delete() // Cache'deki zip dosyasını temizle
            true
        } catch (e: Exception) {
            Log.e("DriveHelper", "Error during backup", e)
            false
        }
    }

    private fun createBackupZip(): File? {
        return try {
            val zipFile = File(context.cacheDir, backupFileName)
            if(zipFile.exists()) zipFile.delete()

            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    // "arsiv" klasörünü ve içindekileri ziple
                    val archiveDir = File(context.filesDir, "arsiv")
                    if (archiveDir.exists() && archiveDir.isDirectory) {
                        archiveDir.listFiles()?.forEach { file ->
                            addFileToZip(zos, file, "files/arsiv/${file.name}")
                        }
                    }

                    // SharedPreferences dosyalarını ziple
                    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                    prefsToBackup.forEach { prefName ->
                        val prefsFile = File(prefsDir, "$prefName.xml")
                        if (prefsFile.exists()) {
                            addFileToZip(zos, prefsFile, "shared_prefs/$prefName.xml")
                        }
                    }
                }
            }
            zipFile
        } catch (e: IOException) {
            Log.e("DriveHelper", "Error creating zip file", e)
            null
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, zipPath: String) {
        zos.putNextEntry(ZipEntry(zipPath))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }


    suspend fun restoreData(): Boolean = withContext(Dispatchers.IO) {
        val zipFile = File(context.cacheDir, "restore.zip")

        try {
            val appFolderId = getAppFolderId() ?: return@withContext false
            val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
            val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()

            if (fileList.files.isEmpty()) {
                Log.e("DriveHelper", "No backup file found to restore")
                return@withContext false
            }

            val fileId = fileList.files[0].id

            // 1. Yedek dosyasını indir
            if(zipFile.exists()) zipFile.delete()
            FileOutputStream(zipFile).use { fos ->
                drive.files().get(fileId).executeMediaAndDownloadTo(fos)
            }

            // 2. Mevcut verileri temizle
            cleanLocalData()

            // 3. İndirilen Zip'i aç
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outputFile = File(context.filesDir.parentFile, entry.name)

                    // Güvenlik: Zip'ten çıkan yolun uygulama dizini dışına çıkmadığından emin ol
                    if (!outputFile.canonicalPath.startsWith(context.filesDir.parentFile.canonicalPath)) {
                        throw SecurityException("Zip Path Traversal Attack detected!")
                    }

                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e("DriveHelper", "Error during restore", e)
            // Hata durumunda temiz bir başlangıç için verileri tekrar temizle
            cleanLocalData()
            false
        } finally {
            // İndirilen geçici zip dosyasını her durumda sil
            if (zipFile.exists()) zipFile.delete()
        }
    }

    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        try {
            // 1. Yerel verileri temizle
            cleanLocalData()

            // 2. Google Drive'daki yedeği sil
            val appFolderId = getAppFolderId() ?: return@withContext
            val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
            val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()

            if (fileList.files.isNotEmpty()) {
                val fileId = fileList.files[0].id
                drive.files().delete(fileId).execute()
            }
        } catch(e: Exception) {
            Log.e("DriveHelper", "Error deleting all data", e)
        }
    }

    private fun cleanLocalData() {
        // "arsiv" klasörünü sil
        val archiveDir = File(context.filesDir, "arsiv")
        if (archiveDir.exists()) {
            archiveDir.deleteRecursively()
        }
        // Yedeklenen tüm ayar dosyalarını temizle
        prefsToBackup.forEach { prefName ->
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}