package com.codenzi.ceparsivi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import java.util.Arrays

class CepArsiviApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // DEĞİŞTİRİLDİ: AdMob SDK'sını ve test cihazı yapılandırmasını, uygulamanın
        // en başında, güvenli bir şekilde burada yapıyoruz.
        MobileAds.initialize(this) {}
        val testDeviceIds = Arrays.asList("BCF3B4664E529BDE4CC3E6B2CB090F7B")
        val configuration = RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build()
        MobileAds.setRequestConfiguration(configuration)

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