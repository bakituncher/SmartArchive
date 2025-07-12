package com.codenzi.ceparsivi

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.codenzi.ceparsivi.databinding.ActivitySettingsBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity(), CategoryEntryDialogFragment.CategoryDialogListener {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveHelper: GoogleDriveHelper? = null

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        } else {
            Toast.makeText(this, "Google ile giriş başarısız oldu.", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupGoogleSignIn()

        binding.textViewTheme.setOnClickListener { showThemeSelectionDialog() }
        binding.textViewManageCategories.setOnClickListener { showManageCategoriesDialog() }
        binding.textViewPrivacyPolicy.setOnClickListener { openPrivacyPolicyLink() }

        binding.buttonDriveSignInOut.setOnClickListener {
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account == null) {
                signIn()
            } else {
                signOut()
            }
        }

        binding.buttonBackup.setOnClickListener { backupData() }
        binding.buttonRestore.setOnClickListener { restoreData() }
        binding.buttonDeleteBackup.setOnClickListener { showDeleteConfirmationDialog() }
    }

    override fun onStart() {
        super.onStart()
        updateUI(GoogleSignIn.getLastSignedInAccount(this))
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestServerAuthCode(getString(R.string.default_web_client_id))
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener(this) {
            updateUI(null)
            Toast.makeText(this, "Oturum kapatıldı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)!!
            Toast.makeText(this, "Hoşgeldin, ${account.displayName}", Toast.LENGTH_SHORT).show()
            updateUI(account)
        } catch (e: ApiException) {
            Log.w("SignIn", "signInResult:failed code=" + e.statusCode)
            updateUI(null)
        }
    }

    private fun updateUI(account: GoogleSignInAccount?) {
        if (account != null) {
            binding.textViewDriveStatus.text = "Giriş yapıldı: ${account.email}"
            binding.buttonDriveSignInOut.text = "Çıkış Yap"
            binding.buttonBackup.isEnabled = true
            binding.buttonRestore.isEnabled = true
            binding.buttonDeleteBackup.isEnabled = true
            driveHelper = GoogleDriveHelper(this, account)
            checkLastBackup()

        } else {
            binding.textViewDriveStatus.text = "Giriş yapılmadı"
            binding.textViewLastBackup.visibility = View.GONE
            binding.buttonDriveSignInOut.text = "Giriş Yap"
            binding.buttonBackup.isEnabled = false
            binding.buttonRestore.isEnabled = false
            binding.buttonDeleteBackup.isEnabled = false
            driveHelper = null
        }
    }

    private fun checkLastBackup() {
        binding.textViewLastBackup.text = "Yedek kontrol ediliyor..."
        binding.textViewLastBackup.visibility = View.VISIBLE
        lifecycleScope.launch {
            val backupDate = driveHelper?.getBackupDate()
            withContext(Dispatchers.Main) {
                binding.textViewLastBackup.text = if (backupDate != null) {
                    "Son Yedekleme: $backupDate"
                } else {
                    "Daha önce yedek alınmamış."
                }
            }
        }
    }

    private fun backupData() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Yedekleme Başlatılıyor")
            .setMessage("Verileriniz Google Drive'a yedekleniyor. Lütfen bekleyin...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = driveHelper?.backupData()
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (result == true) {
                    Toast.makeText(this@SettingsActivity, "Yedekleme başarıyla tamamlandı!", Toast.LENGTH_LONG).show()
                    checkLastBackup()
                } else {
                    Toast.makeText(this@SettingsActivity, "Yedekleme sırasında bir hata oluştu.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restoreData() {
        AlertDialog.Builder(this)
            .setTitle("Verileri Geri Yükle")
            .setMessage("Mevcut tüm verileriniz silinecek ve son yedeklemedeki verilerle değiştirilecektir. Bu işlem geri alınamaz. Onaylıyor musunuz?")
            .setPositiveButton("Evet, Geri Yükle") { _, _ ->
                performRestore()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun performRestore() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Geri Yükleniyor")
            .setMessage("Verileriniz geri yükleniyor. Lütfen bekleyin...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = driveHelper?.restoreData()
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (result == true) {
                    // KESİN ÇÖZÜM: Singleton nesnelerin hafızadaki önbelleğini temizle.
                    // Bu, uygulamanın yeni verileri diskten okumasını zorunlu kılar.
                    CategoryManager.invalidate()
                    FileHashManager.invalidate()

                    Toast.makeText(this@SettingsActivity, "Veriler başarıyla geri yüklendi. Uygulama yeniden başlatılıyor.", Toast.LENGTH_LONG).show()

                    // Uygulamayı yeniden başlatarak temiz bir başlangıç yap.
                    val intent = packageManager.getLaunchIntentForPackage(packageName)!!
                    val componentName = intent.component!!
                    val mainIntent = Intent.makeRestartActivityTask(componentName)
                    startActivity(mainIntent)
                    Runtime.getRuntime().exit(0)
                } else {
                    Toast.makeText(this@SettingsActivity, "Geri yükleme sırasında bir hata oluştu.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tüm Verileri Sil")
            .setMessage("Bu işlem, hem bu cihazdaki tüm arşivlenmiş dosyalarınızı hem de Google Drive'daki yedeğinizi kalıcı olarak silecektir. Bu işlem geri alınamaz. Emin misiniz?")
            .setPositiveButton("Evet, Hepsini Sil") { _, _ ->
                deleteAllData()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun deleteAllData() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Veriler Siliniyor")
            .setMessage("Tüm verileriniz siliniyor...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            driveHelper?.deleteAllData()
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                Toast.makeText(this@SettingsActivity, "Tüm verileriniz başarıyla silindi.", Toast.LENGTH_LONG).show()
                checkLastBackup()
            }
        }
    }

    override fun onCategorySaved(newName: String, oldName: String?) {
        if (oldName == null) {
            if (!CategoryManager.addCategory(this, newName)) {
                Toast.makeText(this, getString(R.string.category_already_exists), Toast.LENGTH_SHORT).show()
            }
        } else {
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