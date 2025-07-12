package com.codenzi.ceparsivi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CepArsiviApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val theme = ThemeManager.getTheme(this)
        ThemeManager.applyTheme(theme)
        createBackupNotificationChannel()
    }

    private fun createBackupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "BackupChannel"
            val channelName = getString(R.string.backup_channel_name)
            val channelDescription = getString(R.string.backup_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
            }
            val notificationManager: NotificationManager =
                getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}