package com.codenzi.ceparsivi

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.codenzi.ceparsivi.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SearchView.OnQueryTextListener, ActionMode.Callback, FileDetailsBottomSheet.FileDetailsListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: ArchivedFileAdapter
    private var actionMode: ActionMode? = null
    private var currentSortOrder = SortOrder.DATE_DESC
    private var currentViewMode = ViewMode.LIST

    private val keyViewMode = "key_view_mode"
    private var activeTheme: String? = null
    private val fileUrisToProcess = ArrayDeque<Uri>()

    private var latestTmpUri: Uri? = null

    private var pendingCameraAction: (() -> Unit)? = null

    private enum class SortOrder {
        DATE_DESC, NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            pendingCameraAction?.invoke()
        } else {
            Toast.makeText(this, "Kamera izni olmadan bu özellik kullanılamaz.", Toast.LENGTH_LONG).show()
        }
        pendingCameraAction = null
    }

    private val takeImageResult = registerForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
        if (isSuccess) {
            latestTmpUri?.let { uri -> launchSaveActivity(uri) }
        }
    }

    private val takeVideoResult = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { isSuccess ->
        if (isSuccess) {
            latestTmpUri?.let { uri -> launchSaveActivity(uri) }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            fileUrisToProcess.addAll(uris)
            processNextUriInQueue()
        }
    }

    private val saveActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED || result.resultCode == Activity.RESULT_OK) {
            processNextUriInQueue()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(keyViewMode, currentViewMode.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        activeTheme = ThemeManager.getTheme(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.recyclerViewFiles.setPadding(0, 0, 0, systemBars.bottom)
            view.updatePadding(left = systemBars.left, right = systemBars.right)
            insets
        }

        if (savedInstanceState != null) {
            val savedViewModeName = savedInstanceState.getString(keyViewMode)
            if (savedViewModeName != null) {
                currentViewMode = try {
                    ViewMode.valueOf(savedViewModeName)
                } catch (_: IllegalArgumentException) {
                    ViewMode.LIST
                }
            }
        }

        // GERİ YÜKLEME SONRASI KONTROLÜ
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("restore_completed", false)) {
            // İşareti temizle ki her açılışta olmasın
            prefs.edit { putBoolean("restore_completed", false) }
        }

        checkFirstLaunch()
        setSupportActionBar(binding.toolbar)
        setupRecyclerView()

        binding.buttonAddFile.setOnClickListener {
            showAddOptionsDialog()
        }

        checkAndPromptForRestore()
    }

    private fun checkAndPromptForRestore() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val restorePromptShown = prefs.getBoolean("restorePromptShown_for_${account.id}", false)

            if(!restorePromptShown) {
                lifecycleScope.launch {
                    val driveHelper = GoogleDriveHelper(this@MainActivity, account)
                    val backupDate = driveHelper.getBackupDate()
                    if (backupDate != null) {
                        withContext(Dispatchers.Main) {
                            showRestorePromptDialog(backupDate)
                        }
                    } else {
                        // Eğer yedek yoksa bile, bu sorgunun yapıldığını işaretle ki her açılışta sormasın.
                        prefs.edit { putBoolean("restorePromptShown_for_${account.id}", true) }
                    }
                }
            }
        }
    }

    private fun showRestorePromptDialog(backupDate: String) {
        AlertDialog.Builder(this)
            .setTitle("Yedek Bulundu")
            .setMessage("$backupDate tarihli bir yedeğiniz bulundu. Verilerinizi şimdi geri yüklemek ister misiniz?")
            .setPositiveButton("Evet, Ayarlara Git") { _, _ ->
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                Toast.makeText(this, "Lütfen Ayarlar menüsünden 'Geri Yükle' butonunu kullanın.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hayır, Teşekkürler", null)
            .setOnDismissListener {
                val account = GoogleSignIn.getLastSignedInAccount(this)
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                prefs.edit { putBoolean("restorePromptShown_for_${account?.id}", true) }
            }
            .show()
    }

    private fun showAddOptionsDialog() {
        val options = arrayOf(
            getString(R.string.option_take_photo),
            getString(R.string.option_record_video),
            getString(R.string.option_from_local_files)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_file_dialog_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkCameraPermissionAndTakePhoto()
                    1 -> checkCameraPermissionAndTakeVideo()
                    2 -> filePickerLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun checkCameraPermissionAndTakePhoto() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                takeImage()
            }
            else -> {
                pendingCameraAction = { takeImage() }
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun checkCameraPermissionAndTakeVideo() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                takeVideo()
            }
            else -> {
                pendingCameraAction = { takeVideo() }
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun takeImage() {
        lifecycleScope.launch {
            getTmpFileUri().let { uri ->
                latestTmpUri = uri
                takeImageResult.launch(uri)
            }
        }
    }

    private fun takeVideo() {
        lifecycleScope.launch {
            getTmpFileUri(isVideo = true).let { uri ->
                latestTmpUri = uri
                takeVideoResult.launch(uri)
            }
        }
    }

    private fun getTmpFileUri(isVideo: Boolean = false): Uri {
        val extension = if (isVideo) ".mp4" else ".jpg"
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tmpFile = File.createTempFile("media_${timeStamp}_", extension, cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }
        return FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.provider", tmpFile)
    }

    private fun launchSaveActivity(uri: Uri) {
        val intent = Intent(this, SaveFileActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = contentResolver.getType(uri) ?: if (uri.toString().endsWith(".mp4")) "video/mp4" else "image/jpeg"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        saveActivityLauncher.launch(intent)
    }

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)
        if (isFirstLaunch) {
            val currentLanguage = Locale.getDefault().language
            val imageResource = if (currentLanguage == "tr") R.drawable.learn else R.drawable.learn_en
            binding.onboardingImageView.setImageResource(imageResource)
            binding.onboardingOverlay.isVisible = true
        }
        binding.closeOnboardingButton.setOnClickListener {
            binding.onboardingOverlay.animate()
                .alpha(0f)
                .withEndAction { binding.onboardingOverlay.isVisible = false }
                .setDuration(300)
                .start()
            prefs.edit { putBoolean("isFirstLaunch", false) }
        }
    }

    override fun onResume() {
        super.onResume()
        val newTheme = ThemeManager.getTheme(this)
        if (activeTheme != null && activeTheme != newTheme) {
            ThemeManager.applyTheme(newTheme)
            recreate()
            return
        }
        lifecycleScope.launch { updateFullList() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { updateFullList() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val viewToggleItem = menu.findItem(R.id.action_toggle_view)
        viewToggleItem.icon = if (currentViewMode == ViewMode.LIST) ContextCompat.getDrawable(this, R.drawable.ic_view_grid) else ContextCompat.getDrawable(this, R.drawable.ic_view_list)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as? SearchView
        searchView?.setOnQueryTextListener(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_toggle_view -> {
                toggleViewMode()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleViewMode() {
        currentViewMode = if (currentViewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        fileAdapter.viewMode = currentViewMode
        setupLayoutManager()
        invalidateOptionsMenu()
    }

    private fun setupLayoutManager() {
        binding.recyclerViewFiles.layoutManager = if (currentViewMode == ViewMode.LIST) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        // Adapter'ın itemCount'undan büyük pozisyonları kontrol ederek çökmeyi önle
                        return if (fileAdapter.itemCount > position && position >= 0 && fileAdapter.getItemViewType(position) == ArchivedFileAdapter.VIEW_TYPE_HEADER) 3 else 1
                    }
                }
            }
        }
        binding.recyclerViewFiles.adapter = fileAdapter
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            getString(R.string.sort_date_desc),
            getString(R.string.sort_name_asc),
            getString(R.string.sort_name_desc),
            getString(R.string.sort_size_asc),
            getString(R.string.sort_size_desc)
        )
        val currentSelection = currentSortOrder.ordinal
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort_dialog_title))
            .setSingleChoiceItems(sortOptions, currentSelection) { dialog, which ->
                currentSortOrder = SortOrder.entries[which]
                lifecycleScope.launch { updateFullList() }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun updateFullList() {
        val allFiles = readFilesFromDisk()
        val secondaryComparator = when (currentSortOrder) {
            SortOrder.NAME_ASC -> compareBy { it.fileName.lowercase() }
            SortOrder.NAME_DESC -> compareByDescending { it.fileName.lowercase() }
            SortOrder.SIZE_ASC -> compareBy { it.size }
            SortOrder.SIZE_DESC -> compareByDescending { it.size }
            SortOrder.DATE_DESC -> compareByDescending<ArchivedFile> { File(it.filePath).lastModified() }
        }

        val categoryOrder = CategoryManager.getCategories(this).sorted()
        val sortedFiles = allFiles.sortedWith(
            compareBy<ArchivedFile> {
                val index = categoryOrder.indexOf(it.category)
                if (index == -1) Int.MAX_VALUE else index
            }.then(secondaryComparator)
        )

        val query = (binding.toolbar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.query?.toString()
        val filteredFiles = if (query.isNullOrBlank()) {
            sortedFiles
        } else {
            sortedFiles.filter { it.fileName.contains(query, ignoreCase = true) }
        }

        val finalList = buildListWithHeaders(filteredFiles)
        withContext(Dispatchers.Main) {
            fileAdapter.submitList(finalList)
            updateUI(finalList.isEmpty())
        }
    }

    private suspend fun readFilesFromDisk(): List<ArchivedFile> {
        return withContext(Dispatchers.IO) {
            val archiveDir = File(filesDir, "arsiv")
            val savedFiles = mutableListOf<ArchivedFile>()
            if (archiveDir.exists() && archiveDir.isDirectory) {
                archiveDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    val categoryName = CategoryManager.getCategoryForFile(this@MainActivity, file.absolutePath)
                        ?: CategoryManager.getDefaultCategoryNameByExtension(this@MainActivity, file.name)
                    val lastModifiedDate = Date(file.lastModified())
                    val formattedDate = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(lastModifiedDate)
                    savedFiles.add(ArchivedFile(file.name, file.absolutePath, formattedDate, categoryName, file.length()))
                }
            }
            savedFiles
        }
    }

    private fun buildListWithHeaders(files: List<ArchivedFile>): List<ListItem> {
        if (files.isEmpty()) return emptyList()
        val listWithHeaders = mutableListOf<ListItem>()
        val groupedByCategory = files.groupBy { it.category }

        val categoryOrder = CategoryManager.getCategories(this).sorted()
        categoryOrder.forEach { categoryName ->
            groupedByCategory[categoryName]?.let { filesInCategory ->
                if (filesInCategory.isNotEmpty()) {
                    listWithHeaders.add(ListItem.HeaderItem(categoryName))
                    listWithHeaders.addAll(filesInCategory.map { ListItem.FileItem(it) })
                }
            }
        }
        return listWithHeaders
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        lifecycleScope.launch { updateFullList() }
        return true
    }

    private fun setupRecyclerView() {
        fileAdapter = ArchivedFileAdapter(
            onItemClick = { file ->
                if (fileAdapter.isSelectionMode) {
                    toggleSelection(file)
                } else {
                    openFile(file)
                }
            },
            onItemLongClick = { file ->
                if (!fileAdapter.isSelectionMode) {
                    if (actionMode == null) {
                        actionMode = startSupportActionMode(this)
                    }
                    toggleSelection(file)
                }
                true
            }
        )
        fileAdapter.viewMode = currentViewMode
        setupLayoutManager()
    }

    private fun toggleSelection(file: ArchivedFile) {
        fileAdapter.toggleSelection(file.filePath)
        val count = fileAdapter.getSelectedFileCount()
        if (count == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = resources.getQuantityString(R.plurals.items_selected, count, count)
            actionMode?.invalidate()
        }
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.contextual_action_menu, menu)
        fileAdapter.isSelectionMode = true
        binding.toolbar.animate().alpha(0f).setDuration(200).start()
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val selectedCount = fileAdapter.getSelectedFileCount()
        menu.findItem(R.id.action_details)?.isVisible = selectedCount == 1
        menu.findItem(R.id.action_share)?.isVisible = selectedCount > 0
        menu.findItem(R.id.action_move)?.isVisible = selectedCount > 0
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val selectedFiles = fileAdapter.getSelectedFiles(fileAdapter.currentList)
        return when (item.itemId) {
            R.id.action_delete -> {
                showMultiDeleteConfirmationDialog(selectedFiles)
                true
            }
            R.id.action_share -> {
                shareFiles(selectedFiles)
                mode.finish()
                true
            }
            R.id.action_move -> {
                showCategorySelectionDialog(selectedFiles)
                mode.finish()
                true
            }
            R.id.action_details -> {
                if (selectedFiles.isNotEmpty()) {
                    showFileDetails(selectedFiles.first())
                }
                mode.finish()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        fileAdapter.clearSelections()
        actionMode = null
        binding.toolbar.animate().alpha(1f).setDuration(200).start()
    }

    private fun showMultiDeleteConfirmationDialog(filesToDelete: List<ArchivedFile>) {
        val count = filesToDelete.size
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_confirmation_title, count, count))
            .setMessage(getString(R.string.delete_confirmation_message))
            .setPositiveButton(getString(R.string.yes_delete)) { _, _ ->
                lifecycleScope.launch {
                    deleteFiles(filesToDelete)
                    actionMode?.finish()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showFileDetails(file: ArchivedFile) {
        val bottomSheet = FileDetailsBottomSheet.newInstance(file)
        bottomSheet.listener = this
        bottomSheet.show(supportFragmentManager, "FileDetailsBottomSheet")
    }

    private fun shareFiles(files: List<ArchivedFile>) {
        if (files.isEmpty()) {
            Toast.makeText(this, getString(R.string.files_shared_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>()
        files.forEach { file ->
            val fileToShare = File(file.filePath)
            if (fileToShare.exists()) {
                val authority = "${applicationContext.packageName}.provider"
                uris.add(FileProvider.getUriForFile(this, authority, fileToShare))
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, getString(R.string.files_shared_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.error_no_app_for_action), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onShareClicked(file: ArchivedFile) {
        shareFiles(listOf(file))
    }

    override fun onDeleteClicked(file: ArchivedFile) {
        showMultiDeleteConfirmationDialog(listOf(file))
    }

    override fun onMoveClicked(file: ArchivedFile) {
        showCategorySelectionDialog(listOf(file))
    }

    private fun showCategorySelectionDialog(filesToMove: List<ArchivedFile>) {
        if (filesToMove.isEmpty()) return

        val currentCategories = filesToMove.map { it.category }.toSet()
        val categories = CategoryManager.getCategories(this)
            .filter { !currentCategories.contains(it) }
            .sorted()
            .toTypedArray()

        if (categories.isEmpty()) {
            Toast.makeText(this, "Taşınacak başka kategori bulunmuyor.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_move_category))
            .setItems(categories) { dialog, which ->
                val newCategory = categories[which]
                lifecycleScope.launch {
                    filesToMove.forEach { file ->
                        CategoryManager.setCategoryForFile(applicationContext, file.filePath, newCategory)
                    }
                    val message = if (filesToMove.size == 1) {
                        getString(R.string.file_moved_to_category, newCategory)
                    } else {
                        "${filesToMove.size} dosya '$newCategory' kategorisine taşındı."
                    }
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                    updateFullList()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }


    private suspend fun deleteFiles(files: List<ArchivedFile>) {
        var deletedCount = 0
        withContext(Dispatchers.IO) {
            files.forEach {
                CategoryManager.removeCategoryForFile(applicationContext, it.filePath)
                FileHashManager.removeHashForFile(applicationContext, it.fileName)
                val fileToDelete = File(it.filePath)
                if (fileToDelete.exists() && fileToDelete.delete()) {
                    deletedCount++
                }
            }
        }
        val message = if (deletedCount == 1) getString(R.string.file_deleted_toast) else getString(R.string.files_deleted_toast, deletedCount)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        updateFullList()
    }

    override fun onQueryTextSubmit(query: String?): Boolean = false

    private fun updateUI(isEmpty: Boolean) {
        binding.recyclerViewFiles.isVisible = !isEmpty
        binding.layoutEmpty.isVisible = isEmpty
    }

    private fun openFile(file: ArchivedFile) {
        val fileToOpen = File(file.filePath)
        if (!fileToOpen.exists()) {
            Toast.makeText(this, getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        val authority = "${applicationContext.packageName}.provider"
        val fileUri = FileProvider.getUriForFile(this, authority, fileToOpen)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileToOpen.extension) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun processNextUriInQueue() {
        if (fileUrisToProcess.isNotEmpty()) {
            val uriToProcess = fileUrisToProcess.removeFirst()
            val intent = Intent(this, SaveFileActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uriToProcess)
                type = contentResolver.getType(uriToProcess) ?: "*/*"
            }
            saveActivityLauncher.launch(intent)
        }
    }
}