package com.codenzi.ceparsivi

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // DÜZELTME: İlk olarak kullanıcının zaten giriş yapıp yapmadığını kontrol et.
        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastSignedInAccount != null) {
            // Eğer kullanıcı zaten giriş yapmışsa, tanıtım/öneri ekranlarını gösterme,
            // doğrudan ana ekrana git. Bu, döngüyü tamamen engeller.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Eğer kullanıcı giriş yapmamışsa, önceki akışa devam et.
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)

        if (isFirstLaunch) {
            // Uygulama ilk kez açılıyorsa, tanıtım ekranına git.
            startActivity(Intent(this, IntroActivity::class.java))
        } else {
            // Tanıtımı görmüş ama hala giriş yapmamışsa, giriş öneri ekranına git.
            // (Bu ekrandan "Daha Sonra" diyerek çıkarsa, MainActivity'e geçer)
            startActivity(Intent(this, LoginSuggestionActivity::class.java))
        }
        finish()
    }
}