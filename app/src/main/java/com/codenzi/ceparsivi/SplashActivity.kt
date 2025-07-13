package com.codenzi.ceparsivi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

class SplashActivity : AppCompatActivity() {

    private lateinit var consentInformation: ConsentInformation
    // Bu, uygulamanın bir sonraki ekrana sadece bir kez geçtiğinden emin olmak içindir.
    private val isNextActivityStarted = AtomicBoolean(false)
    private val TAG = "SplashActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        consentInformation = UserMessagingPlatform.getConsentInformation(this)

        // Testler sırasında onayı sıfırlamak için bu satırın yorumunu kaldırabilirsiniz.
        // Her testten önce uygulamayı telefondan tamamen kaldırmak daha garantili bir yoldur.
        // consentInformation.reset()

        // Sadece Avrupa Ekonomik Alanı ve Birleşik Krallık'taki kullanıcılara
        // onay formu göstermek için parametreleri ayarlıyoruz. Diğer bölgelerde
        // form otomatik olarak gösterilmez ve akış devam eder.
        val params = ConsentRequestParameters
            .Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        // Onay bilgilerini güncelleme talebi gönderiyoruz.
        consentInformation.requestConsentInfoUpdate(
            this,
            params,
            {
                // Onay bilgileri başarıyla güncellendi.
                // Eğer form göstermek gerekiyorsa (kullanıcı Avrupa'daysa vb.),
                // SDK bu formu yükleyip gösterecek.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { loadAndShowError ->
                    if (loadAndShowError != null) {
                        Log.w(TAG, "Onay formu yüklenirken veya gösterilirken hata oldu: ${loadAndShowError.message}")
                    }

                    // Form gösterilse de, hata da olsa, süreç bittiğinde bir sonraki ekrana geç.
                    // Bu, uygulamanın burada takılıp kalmasını engeller.
                    proceedToNextActivity()
                }
            },
            { requestConsentError ->
                // Onay bilgilerini güncellerken bir hata oldu (örn: internet yok).
                // Bu durumda bile uygulamanın açılmasına devam et.
                Log.w(TAG, "Onay bilgisi alınamadı: ${requestConsentError.message}")
                proceedToNextActivity()
            }
        )
    }

    private fun proceedToNextActivity() {
        // Bu fonksiyonun sadece bir kez çalışmasını garanti altına alıyoruz.
        if (isNextActivityStarted.getAndSet(true)) {
            return
        }

        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastSignedInAccount != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)

        if (isFirstLaunch) {
            startActivity(Intent(this, IntroActivity::class.java))
        } else {
            startActivity(Intent(this, LoginSuggestionActivity::class.java))
        }
        finish()
    }
}