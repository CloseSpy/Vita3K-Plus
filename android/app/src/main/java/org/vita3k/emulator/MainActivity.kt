package org.vita3k.emulator

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vita3k.emulator.data.AppInfo
import org.vita3k.emulator.data.AppStorage
import org.vita3k.emulator.ui.navigation.AppNavigation
import org.vita3k.emulator.ui.theme.Vita3KTheme
import org.vita3k.emulator.ui.viewmodel.AppsListViewModel
import org.vita3k.emulator.ui.viewmodel.InstallViewModel
import org.vita3k.emulator.ui.viewmodel.SettingsViewModel
import org.vita3k.emulator.ui.viewmodel.UserManagementViewModel

class MainActivity : AppCompatActivity() {
    private val appsListViewModel: AppsListViewModel by viewModels()
    private val installViewModel: InstallViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val userManagementViewModel: UserManagementViewModel by viewModels()

    private val emulatorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (appsListViewModel.initialized) {
            appsListViewModel.reloadAppsList()
        }
    }

    private var pendingFileCallback: ((String?) -> Unit)? = null
    private var pendingArchiveFolderCallback: ((List<String>) -> Unit)? = null
    private var pendingInstallFileExtensions: Set<String>? = null
    private var pendingArchiveFolderExtensions: Set<String>? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        val extensions = pendingInstallFileExtensions ?: emptySet()
        pendingFileCallback = null
        pendingInstallFileExtensions = null
        val uri = result.data?.data
        if (callback == null) return@registerForActivityResult
        lifecycleScope.launch {
            val path = if (result.resultCode == RESULT_OK && uri != null) {
                withContext(Dispatchers.IO) { StorageAccess.stageDocument(this@MainActivity, uri, extensions) }
            } else null
            if (result.resultCode == RESULT_OK && path == null) {
                Toast.makeText(this@MainActivity, "Unable to read the selected file.", Toast.LENGTH_SHORT).show()
            }
            callback(path)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingArchiveFolderCallback
        val extensions = pendingArchiveFolderExtensions ?: emptySet()
        pendingArchiveFolderCallback = null
        pendingArchiveFolderExtensions = null
        val tree = result.data?.data
        if (callback == null) return@registerForActivityResult
        lifecycleScope.launch {
            val paths = if (result.resultCode == RESULT_OK && tree != null) {
                withContext(Dispatchers.IO) { StorageAccess.stageTree(this@MainActivity, tree, extensions) }
            } else emptyList()
            if (result.resultCode == RESULT_OK && paths.isEmpty()) {
                Toast.makeText(this@MainActivity, "No selected archives could be staged.", Toast.LENGTH_SHORT).show()
            }
            callback(paths)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prepareFrontendRuntime()
        setTheme(R.style.Theme_Vita3K)

        val storagePath = AppStorage.storageRootPath(this)
        appsListViewModel.initialize(storagePath)

        setContent {
            Vita3KTheme {
                AppNavigation(
                    appsListViewModel = appsListViewModel,
                    installViewModel = installViewModel,
                    settingsViewModel = settingsViewModel,
                    userManagementViewModel = userManagementViewModel,
                    onAppLaunch = { app -> launchApp(app.titleId, app.title) },
                    onCreateAppShortcut = { app -> createAppShortcut(app) }
                )
            }
        }
    }

    fun requestStorageFolderChange(onResult: (String?) -> Unit) {
        onResult(AppStorage.defaultStoragePath(this))
    }

    fun requestFolderPath(onResult: (String?) -> Unit) {
        onResult(null)
    }

    fun requestFilePath(mimeTypes: Array<String>, onResult: (String?) -> Unit) {
        pendingFileCallback = onResult
        pendingArchiveFolderCallback = null
        pendingInstallFileExtensions = null
        pendingArchiveFolderExtensions = null
        filePickerLauncher.launch(StorageAccess.createFilePickerIntent(mimeTypes))
    }

    fun requestInstallFilePath(
        allowedExtensions: Set<String>,
        onResult: (String?) -> Unit
    ) {
        pendingFileCallback = onResult
        pendingArchiveFolderCallback = null
        pendingInstallFileExtensions = allowedExtensions
        pendingArchiveFolderExtensions = null
        filePickerLauncher.launch(StorageAccess.createFilePickerIntent(arrayOf("*/*")))
    }

    fun requestArchiveFolderPaths(
        allowedExtensions: Set<String>,
        onResult: (List<String>) -> Unit
    ) {
        pendingFileCallback = null
        pendingArchiveFolderCallback = onResult
        pendingInstallFileExtensions = null
        pendingArchiveFolderExtensions = allowedExtensions
        folderPickerLauncher.launch(StorageAccess.createFolderPickerIntent())
    }

    override fun onResume() {
        super.onResume()
        prepareFrontendRuntime()
    }

    private fun launchApp(titleId: String, appTitle: String) {
        emulatorLauncher.launch(Emulator.createLaunchIntent(this, titleId, appTitle))
    }

    private fun createAppShortcut(app: AppInfo) {
        val created = Emulator.createShortcut(this, app.titleId, app.title, app.iconFile)
        val messageResId = if (created) {
            R.string.shortcut_create_requested
        } else {
            R.string.shortcut_create_unsupported
        }
        Toast.makeText(this, getString(messageResId), Toast.LENGTH_SHORT).show()
    }

    private fun prepareFrontendRuntime() {
        NativeLib.prepareFrontend()
    }
}
