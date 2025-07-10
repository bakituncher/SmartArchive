package com.codenzi.ceparsivi

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.codenzi.ceparsivi.databinding.ActivitySettingsBinding

// DÜZELTME: Yeni DialogFragment için listener arayüzünü implement ediyoruz.
class SettingsActivity : AppCompatActivity(), CategoryEntryDialogFragment.CategoryDialogListener {

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

        binding.textViewManageCategories.setOnClickListener {
            showManageCategoriesDialog()
        }

        binding.textViewPrivacyPolicy.setOnClickListener {
            openPrivacyPolicyLink()
        }
    }

    // DÜZELTME: DialogFragment'tan gelen sonucu burada işliyoruz.
    override fun onCategorySaved(newName: String, oldName: String?) {
        if (oldName == null) { // Yeni kategori ekleme
            if (!CategoryManager.addCategory(this, newName)) {
                Toast.makeText(this, getString(R.string.category_already_exists), Toast.LENGTH_SHORT).show()
            }
        } else { // Kategori yeniden adlandırma
            CategoryManager.renameCategory(this, oldName, newName)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(getString(R.string.theme_light), getString(R.string.theme_dark), getString(R.string.theme_system))
        val themeModes = arrayOf(ThemeManager.ThemeMode.LIGHT, ThemeManager.ThemeMode.DARK, ThemeManager.ThemeMode.SYSTEM)
        val currentThemeValue = ThemeManager.getTheme(this)
        val checkedItem = themeModes.indexOfFirst { it.value == currentThemeValue }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_theme))
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                val selectedThemeMode = themeModes[which]
                if (currentThemeValue != selectedThemeMode.value) {
                    ThemeManager.setTheme(this, selectedThemeMode)
                    ThemeManager.applyTheme(selectedThemeMode.value)
                    recreate()
                }
                dialog.dismiss()
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

    private fun showManageCategoriesDialog() {
        val categories = CategoryManager.getCategories(this).sorted().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.manage_categories))
            .setItems(categories) { _, which ->
                showCategoryActionsDialog(categories[which])
            }
            .setPositiveButton(getString(R.string.add_new_category)) { _, _ ->
                showAddOrEditCategoryDialog(null)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCategoryActionsDialog(categoryName: String) {
        val actions = mutableListOf(getString(R.string.action_rename))
        if (!CategoryManager.getDefaultCategories(this).contains(categoryName)) {
            actions.add(getString(R.string.action_delete))
        }

        AlertDialog.Builder(this)
            .setTitle(categoryName)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.action_rename) -> showAddOrEditCategoryDialog(categoryName)
                    getString(R.string.action_delete) -> showDeleteCategoryConfirmationDialog(categoryName)
                }
            }
            .show()
    }

    // DÜZELTME: Bu fonksiyon artık yeni DialogFragment'ı gösteriyor.
    private fun showAddOrEditCategoryDialog(existingCategory: String?) {
        val dialog = CategoryEntryDialogFragment.newInstance(existingCategory)
        dialog.listener = this
        dialog.show(supportFragmentManager, "CategoryEntryDialog")
    }

    private fun showDeleteCategoryConfirmationDialog(categoryName: String) {
        val filesInCategory = CategoryManager.getFilesInCategory(this, categoryName)
        if (filesInCategory.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cannot_delete_category_title))
                .setMessage(getString(R.string.cannot_delete_category_message, filesInCategory.size, categoryName))
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_category_confirmation_title, categoryName))
            .setMessage(getString(R.string.delete_category_confirmation_message))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                CategoryManager.deleteCategory(this, categoryName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
