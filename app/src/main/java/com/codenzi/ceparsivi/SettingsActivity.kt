// Konum: app/src/main/java/com/codenzi/ceparsivi/SettingsActivity.kt

package com.codenzi.ceparsivi

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            setBackupButtonsEnabled(false)
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, getString(R.string.notification_permission_denied), Toast.LENGTH_LONG).show()
                binding.switchAutoBackup.isChecked = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupGoogleSignIn()
        loadSettings()
        setupPrivacyOptionsButton()

        // Click Listeners
        binding.textViewTheme.setOnClickListener { showThemeSelectionDialog() }
        binding.textViewManageCategories.setOnClickListener { showManageCategoriesDialog() }
        binding.textViewPrivacyPolicy.setOnClickListener { openPrivacyPolicyLink() }
        binding.textViewTermsOfUse.setOnClickListener { openTermsOfUseLink() }
        binding.textViewContactUs.setOnClickListener { openContactUsEmail() }

        binding.buttonDriveSignInOut.setOnClickListener {
            if (GoogleSignIn.getLastSignedInAccount(this) == null) signIn() else signOut()
        }
        binding.buttonBackup.setOnClickListener { backupData() }
        binding.buttonRestore.setOnClickListener { restoreData() }
        binding.buttonDeleteBackup.setOnClickListener { showDeleteConfirmationDialog() }
        binding.switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            handleAutoBackupSwitch(isChecked)
        }
    }

    // --- DÜZELTİLEN VE EN SAĞLAM HALE GETİRİLEN FONKSİYON ---
    private fun openContactUsEmail() {
        val emailAddress = getString(R.string.contact_us_email)
        val subject = getString(R.string.contact_us_subject)

        // En uyumlu yöntem: Konuyu doğrudan "mailto" URI'sine bir parametre olarak eklemek.
        // Bu, tüm e-posta uygulamalarının konuyu doğru bir şekilde almasını sağlar.
        val mailtoUri = Uri.parse("mailto:$emailAddress?subject=${Uri.encode(subject)}")

        val intent = Intent(Intent.ACTION_SENDTO, mailtoUri)

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Eğer cihazda hiçbir e-posta uygulaması bulunamazsa kullanıcıyı bilgilendir.
            Toast.makeText(this, "No email client found to handle this request.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTermsOfUseLink() {
        val termsUrl = getString(R.string.terms_of_use_url)
        val intent = Intent(Intent.ACTION_VIEW, termsUrl.toUri())
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.error_no_app_for_action), Toast.LENGTH_SHORT).show()
        }
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

    // --- BU NOKTADAN SONRAKİ TÜM KODLAR TAMAMEN AYNI KALACAK ---
    // Hiçbir değişiklik yapılmadı, mevcut işlevsellik korunuyor.

    private fun setupPrivacyOptionsButton() {
        val consentInformation = UserMessagingPlatform.getConsentInformation(this)

        if (consentInformation.privacyOptionsRequirementStatus == com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) {
            binding.textViewPrivacySettings.visibility = View.VISIBLE
            binding.dividerPrivacySettings.visibility = View.VISIBLE
            binding.textViewPrivacySettings.setOnClickListener {
                UserMessagingPlatform.showPrivacyOptionsForm(this) { formError ->
                    if (formError != null) {
                        Log.w("SettingsActivity", "Gizlilik seçenekleri formu hatası: ${formError.message}")
                        Toast.makeText(this, getString(R.string.privacy_options_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            binding.textViewPrivacySettings.visibility = View.GONE
            binding.dividerPrivacySettings.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        updateUI(GoogleSignIn.getLastSignedInAccount(this))
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        binding.switchAutoBackup.isChecked = prefs.getBoolean("auto_backup_enabled", false)
    }

    private fun handleAutoBackupSwitch(isEnabled: Boolean) {
        if (isEnabled) {
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account == null) {
                Toast.makeText(this, getString(R.string.auto_backup_sign_in_required), Toast.LENGTH_LONG).show()
                binding.switchAutoBackup.isChecked = false
                return
            }
            checkAndRequestNotificationPermission()
            BackupScheduler.schedulePeriodicBackup(this)
            Toast.makeText(this, getString(R.string.auto_backup_enabled_toast), Toast.LENGTH_SHORT).show()
        } else {
            BackupScheduler.cancelPeriodicBackup(this)
            Toast.makeText(this, getString(R.string.auto_backup_disabled_toast), Toast.LENGTH_SHORT).show()
        }
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit {
            putBoolean("auto_backup_enabled", isEnabled)
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {}
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.notification_permission_title))
                        .setMessage(getString(R.string.notification_permission_rationale))
                        .setPositiveButton(getString(R.string.ok)) { _, _ ->
                            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
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
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener(this) {
            updateUI(null)
            Toast.makeText(this, "Oturum kapatıldı.", Toast.LENGTH_SHORT).show()
            if (binding.switchAutoBackup.isChecked) {
                binding.switchAutoBackup.isChecked = false
            }
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)!!
            Toast.makeText(this, "Hoşgeldin, ${account.displayName}", Toast.LENGTH_SHORT).show()
            updateUI(account)
            checkBackupAndPrompt(account)
        } catch (e: ApiException) {
            Log.w("SignIn", "signInResult:failed code=" + e.statusCode)
            updateUI(null)
        }
    }

    private fun updateUI(account: GoogleSignInAccount?) {
        if (account != null) {
            binding.textViewDriveStatus.text = getString(R.string.status_signed_in_as, account.email)
            binding.buttonDriveSignInOut.text = getString(R.string.action_sign_out)
            setBackupButtonsEnabled(true)
            binding.switchAutoBackup.isEnabled = true
            driveHelper = GoogleDriveHelper(this, account)
            checkLastBackup()
        } else {
            binding.textViewDriveStatus.text = getString(R.string.status_not_signed_in)
            binding.textViewLastBackup.visibility = View.GONE
            binding.buttonDriveSignInOut.text = getString(R.string.action_sign_in)
            setBackupButtonsEnabled(false)
            binding.switchAutoBackup.isEnabled = false
            driveHelper = null
        }
    }

    private fun setBackupButtonsEnabled(isEnabled: Boolean) {
        binding.buttonBackup.isEnabled = isEnabled
        binding.buttonRestore.isEnabled = isEnabled
        binding.buttonDeleteBackup.isEnabled = isEnabled
    }

    private fun checkLastBackup() {
        binding.textViewLastBackup.text = getString(R.string.last_backup_checking)
        binding.textViewLastBackup.visibility = View.VISIBLE
        lifecycleScope.launch {
            val backupMetadata = withContext(Dispatchers.IO) { driveHelper?.getBackupMetadata() }
            binding.textViewLastBackup.text = if (backupMetadata != null) {
                val formattedDate = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(backupMetadata.second))
                getString(R.string.last_backup_at, formattedDate)
            } else {
                getString(R.string.last_backup_never)
            }
        }
    }

    private fun checkBackupAndPrompt(account: GoogleSignInAccount) {
        val localDriveHelper = GoogleDriveHelper(this, account)
        lifecycleScope.launch(Dispatchers.IO) {
            val backupMetadata = localDriveHelper.getBackupMetadata() ?: return@launch
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val lastPromptedTimestamp = prefs.getLong("last_prompted_timestamp_${account.id}", 0L)
            if (backupMetadata.second > lastPromptedTimestamp) {
                withContext(Dispatchers.Main) {
                    showRestorePromptDialog(backupMetadata.second, account.id!!)
                }
            }
        }
    }

    private fun showRestorePromptDialog(backupTimestamp: Long, accountId: String) {
        val formattedDate = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(backupTimestamp))
        AlertDialog.Builder(this)
            .setTitle("Yedek Bulundu")
            .setMessage("$formattedDate tarihli bir yedeğiniz bulundu. Verilerinizi şimdi geri yüklemek ister misiniz?")
            .setPositiveButton("Evet, Geri Yükle") { _, _ -> performRestore() }
            .setNegativeButton("Hayır, Teşekkürler", null)
            .setOnDismissListener {
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                prefs.edit { putLong("last_prompted_timestamp_${accountId}", backupTimestamp) }
            }
            .show()
    }

    private fun backupData() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Yedekleme Başlatılıyor")
            .setMessage("Verileriniz Google Drive'a yedekleniyor. Lütfen bekleyin...")
            .setCancelable(false)
            .show()
        setBackupButtonsEnabled(false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { driveHelper?.backupData() }
            dialog.dismiss()
            setBackupButtonsEnabled(true)
            if (result == true) {
                Toast.makeText(this@SettingsActivity, "Yedekleme başarıyla tamamlandı!", Toast.LENGTH_LONG).show()
                checkLastBackup()
            } else {
                Toast.makeText(this@SettingsActivity, "Yedekleme sırasında bir hata oluştu. Lütfen internet bağlantınızı kontrol edin.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreData() {
        AlertDialog.Builder(this)
            .setTitle("Verileri Geri Yükle")
            .setMessage("Mevcut tüm verileriniz silinecek ve son yedeklemedeki verilerle değiştirilecektir. Bu işlem geri alınamaz. Onaylıyor musunuz?")
            .setPositiveButton("Evet, Geri Yükle") { _, _ -> performRestore() }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun performRestore() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Geri Yükleniyor")
            .setMessage("Yedekleriniz indiriliyor, lütfen bekleyin...")
            .setCancelable(false)
            .show()
        setBackupButtonsEnabled(false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { driveHelper?.restoreData() }
            dialog.dismiss()
            setBackupButtonsEnabled(true)
            when (result) {
                RestoreResult.SUCCESS -> {
                    CategoryManager.invalidate()
                    FileHashManager.invalidate()
                    Toast.makeText(this@SettingsActivity, "Veriler başarıyla geri yüklendi. Uygulama yeniden başlatılıyor.", Toast.LENGTH_LONG).show()
                    restartApp()
                }
                RestoreResult.NO_BACKUP_FOUND -> showRestoreError("Geri yüklenecek bir yedek bulunamadı.")
                RestoreResult.DOWNLOAD_FAILED -> showRestoreError("Yedek indirilemedi. İnternet bağlantınızı veya Google Drive izinlerinizi kontrol edin.")
                RestoreResult.VALIDATION_FAILED -> showRestoreError("Yedek dosyası bozuk veya geçersiz. Lütfen yeni bir yedek almayı deneyin.")
                RestoreResult.RESTORE_FAILED -> showRestoreError("Dosyalar geri yüklenemedi. Cihazınızda yeterli alan olduğundan emin olun.")
                else -> showRestoreError("Bilinmeyen bir hata oluştu. Lütfen daha sonra tekrar deneyin.")
            }
        }
    }

    private fun showRestoreError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Geri Yükleme Başarısız")
            .setMessage(message)
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
        val componentName = intent.component!!
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tüm Verileri Sil")
            .setMessage("Bu işlem, hem bu cihazdaki tüm arşivlenmiş dosyalarınızı hem de Google Drive'daki yedeğinizi kalıcı olarak silecektir. Bu işlem geri alınamaz. Emin misiniz?")
            .setPositiveButton("Evet, Hepsini Sil") { _, _ -> deleteAllData() }
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
            withContext(Dispatchers.IO) { driveHelper?.deleteAllData() }
            dialog.dismiss()
            Toast.makeText(this@SettingsActivity, "Tüm verileriniz başarıyla silindi.", Toast.LENGTH_LONG).show()
            checkLastBackup()
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
                }
                dialog.dismiss()
            }
            .show()
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