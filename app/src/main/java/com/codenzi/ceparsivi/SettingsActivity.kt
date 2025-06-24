package com.codenzi.ceparsivi

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.codenzi.ceparsivi.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)


        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        binding.textViewTheme.setOnClickListener {
            showThemeSelectionDialog()
        }


        binding.textViewPrivacyPolicy.setOnClickListener {
            openPrivacyPolicyLink()
        }
    }


    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
        )
        val themeModes =
            arrayOf(ThemeManager.ThemeMode.LIGHT, ThemeManager.ThemeMode.DARK, ThemeManager.ThemeMode.SYSTEM)
        val currentThemeValue = ThemeManager.getTheme(this)

        val checkedItem = themeModes.indexOfFirst { it.value == currentThemeValue }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_theme))
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                val selectedThemeMode = themeModes[which]


                if (currentThemeValue != selectedThemeMode.value) {

                    ThemeManager.setTheme(this, selectedThemeMode)

                    ThemeManager.applyTheme(selectedThemeMode.value)
                }

                dialog.dismiss()


                if (currentThemeValue != selectedThemeMode.value) {
                    this.recreate()
                }
            }
            .show()
    }

    private fun openPrivacyPolicyLink() {
        val privacyPolicyUrl = getString(R.string.privacy_policy_url)
        val intent = Intent(Intent.ACTION_VIEW, privacyPolicyUrl.toUri())
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.error_no_app_for_action), Toast.LENGTH_SHORT).show()
        }
    }
}