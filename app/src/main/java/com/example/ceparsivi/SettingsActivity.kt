package com.example.ceparsivi

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ceparsivi.databinding.ActivitySettingsBinding // Bu import artık hata vermeyecek

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Oluşturulan layout dosyasını aktiviteye bağlıyoruz
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Aktivite oluşturulur oluşturulmaz tema seçim diyaloğunu göster
        showThemeSelectionDialog()
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf("Aydınlık", "Karanlık", "Sistem Varsayılanı")
        val themeModes = arrayOf(ThemeManager.ThemeMode.LIGHT, ThemeManager.ThemeMode.DARK, ThemeManager.ThemeMode.SYSTEM)
        val currentThemeValue = ThemeManager.getTheme(this)

        val checkedItem = themeModes.indexOfFirst { it.value == currentThemeValue }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Uygulama Teması")
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                val selectedTheme = themeModes[which]
                ThemeManager.setTheme(this, selectedTheme)
                dialog.dismiss()
                finish() // Diyalog kapandığında aktiviteyi de kapat
            }
            .setOnCancelListener {
                finish() // Geri tuşuyla veya dışarı tıklayarak iptal edilirse aktiviteyi kapat
            }
            .show()
    }
}