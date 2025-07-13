package com.codenzi.ceparsivi

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)
        val hasSeenLoginSuggestion = prefs.getBoolean("has_seen_login_suggestion", false)

        when {
            isFirstLaunch -> {
                // Uygulama ilk kez açılıyorsa, tanıtım ekranına git.
                startActivity(Intent(this, IntroActivity::class.java))
            }
            !hasSeenLoginSuggestion -> {
                // Tanıtımı görmüş ama giriş öneri ekranını görmemişse, o ekrana git.
                startActivity(Intent(this, LoginSuggestionActivity::class.java))
            }
            else -> {
                // Her iki ekranı da görmüşse, doğrudan ana ekrana git.
                startActivity(Intent(this, MainActivity::class.java))
            }
        }
        finish()
    }
}