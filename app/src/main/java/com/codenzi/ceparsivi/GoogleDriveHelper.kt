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
            val zipFile = java.io.File(context.cacheDir, backupFileName)
            if (zipFile.exists()) {
                zipFile.delete()
            }
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val archiveDir = java.io.File(context.filesDir, "arsiv")
                    if (archiveDir.exists() && archiveDir.isDirectory) {
                        archiveDir.listFiles()?.forEach { file ->
                            val entry = ZipEntry("arsiv/${file.name}")
                            zos.putNextEntry(entry)
                            FileInputStream(file).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    prefsToBackup.forEach { prefName ->
                        val prefsFile = java.io.File("${context.applicationInfo.dataDir}/shared_prefs/$prefName.xml")
                        if (prefsFile.exists()) {
                            val entry = ZipEntry("shared_prefs/$prefName.xml")
                            zos.putNextEntry(entry)
                            FileInputStream(prefsFile).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
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
            zipFile.delete()
            true
        } catch (e: Exception) {
            Log.e("DriveHelper", "Error during backup", e)
            false
        }
    }

    suspend fun restoreData(): Boolean = withContext(Dispatchers.IO) {
        try {
            val appFolderId = getAppFolderId() ?: return@withContext false
            val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
            val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()

            if (fileList.files.isEmpty()) {
                Log.e("DriveHelper", "No backup file found to restore")
                return@withContext false
            }

            val fileId = fileList.files[0].id
            cleanLocalData()
            val zipFile = java.io.File(context.cacheDir, "restore.zip")
            if (zipFile.exists()) {
                zipFile.delete()
            }
            FileOutputStream(zipFile).use { fos ->
                drive.files().get(fileId).executeMediaAndDownloadTo(fos)
            }
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outputFile = java.io.File(context.applicationInfo.dataDir, entry.name)
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            zipFile.delete()
            true
        } catch (e: Exception) {
            Log.e("DriveHelper", "Error during restore", e)
            false
        }
    }

    /**
     * DÜZELTME: Fonksiyon, içindeki 'if' ifadelerinin bir değer döndürme zorunluluğunu ortadan kaldıracak şekilde düzeltildi.
     * Derleme hatasını gidermek için 'if' bloklarının sonuna açıkça Unit döndürülüyor.
     */
    suspend fun deleteAllData() {
        withContext(Dispatchers.IO) {
            try {
                cleanLocalData()
                val appFolderId = getAppFolderId()
                if (appFolderId != null) {
                    val query = "'$appFolderId' in parents and name='$backupFileName' and trashed=false"
                    val fileList = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
                    if (fileList.files.isNotEmpty()) {
                        val fileId = fileList.files[0].id
                        drive.files().delete(fileId).execute()
                    }
                }
                Unit // <-- Bu satır eklendi
            } catch (e: Exception) {
                Log.e("DriveHelper", "Error deleting all data", e)
                Unit // <-- Bu satır eklendi
            }
        }
    }

    private fun cleanLocalData() {
        val archiveDir = java.io.File(context.filesDir, "arsiv")
        if (archiveDir.exists()) {
            archiveDir.deleteRecursively()
        }
        prefsToBackup.forEach { prefName ->
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}