package com.codenzi.ceparsivi

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.codenzi.ceparsivi.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showThemeSelectionDialog()
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
        )
        val themeModes = arrayOf(ThemeManager.ThemeMode.LIGHT, ThemeManager.ThemeMode.DARK, ThemeManager.ThemeMode.SYSTEM)
        val currentThemeValue = ThemeManager.getTheme(this)

        val checkedItem = themeModes.indexOfFirst { it.value == currentThemeValue }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_theme))
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                val selectedTheme = themeModes[which]
                ThemeManager.setTheme(this, selectedTheme)
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }
}
