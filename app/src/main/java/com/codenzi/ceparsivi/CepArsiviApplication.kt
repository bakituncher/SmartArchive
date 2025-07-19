// bakituncher/smartarchive/SmartArchive-070b874748e2ed482bd05355047ace4f73079c28/app/src/main/java/com/codenzi/ceparsivi/CepArsiviApplication.kt

package com.codenzi.ceparsivi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import java.util.Arrays

class CepArsiviApplication : Application() {

    private var areAdsInitialized = false

    override fun onCreate() {
        super.onCreate()
        // DEĞİŞİKLİK: Reklamları doğrudan başlatmak yerine, sadece tema ve bildirim kanalı ayarlarını yapıyoruz.
        // Reklamlar, kullanıcı rızası alındıktan sonra SplashActivity'den çağrılarak başlatılacak.
        val theme = ThemeManager.getTheme(this)
        ThemeManager.applyTheme(theme)
        createBackupNotificationChannel()
    }

    // YENİ EKLENDİ: Reklamları sadece rıza alındıktan sonra başlatmak için bu fonksiyon kullanılacak.
    // Bu, fonksiyonun birden fazla kez çağrılmasını engeller.
    fun initializeMobileAds() {
        if (areAdsInitialized) return

        MobileAds.initialize(this) {}
        // Test cihazı yapılandırmasını güvenli bir şekilde burada yapıyoruz.
        // val testDeviceIds = Arrays.asList("BCF3B4664E529BDE4CC3E6B2CB090F7B")
        // val configuration = RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build()
        // MobileAds.setRequestConfiguration(configuration)
        areAdsInitialized = true
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