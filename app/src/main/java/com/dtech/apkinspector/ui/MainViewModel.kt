package com.dtech.apkinspector.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dtech.apkinspector.data.ApkFileNode
import com.dtech.apkinspector.data.ApkRepository
import com.dtech.apkinspector.domain.ApkForge
import com.dtech.apkinspector.domain.StructuredParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MainUiState(
    val apkUri: Uri? = null,
    val isProcessing: Boolean = false,
    val statusMessage: String = "Select an APK to begin",
    val manifestBytes: ByteArray? = null,
    val arscBytes: ByteArray? = null,
    val appLabel: String? = null,
    val packageName: String? = null,
    val error: String? = null,
    val fileTree: List<ApkFileNode> = emptyList(),
    val modifiedFiles: Map<String, ByteArray> = emptyMap(),
    val currentApkFile: File? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val context = application.applicationContext
    private val apkForge = ApkForge(context)
    private val parser = StructuredParsers()
    private val repository = ApkRepository(context)

    fun loadApkFromFile(file: File) {
        loadApkInternal(file, Uri.fromFile(file))
    }

    fun loadApk(uri: Uri) {
         _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Loading APK...",
            apkUri = uri,
            error = null,
            manifestBytes = null,
            arscBytes = null,
            appLabel = null,
            packageName = null,
            fileTree = emptyList(),
            modifiedFiles = emptyMap(),
            currentApkFile = null
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Load APK into temp file for random access
                val file = repository.loadApkFromUri(uri)
                    ?: throw Exception("Failed to load APK from URI")

                loadApkInternal(file, uri)
            } catch(e: Exception) {
                 e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "Error: ${e.message}",
                        error = e.message
                    )
                }
            }
        }
    }

    private fun loadApkInternal(file: File, uri: Uri) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Analyzing APK...",
            apkUri = uri,
            error = null,
            manifestBytes = null,
            arscBytes = null,
            appLabel = null,
            packageName = null,
            fileTree = emptyList(),
            modifiedFiles = emptyMap(),
            currentApkFile = null
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Read Manifest and ARSC
                val manifest = repository.getFileContent(file, "AndroidManifest.xml")
                val arsc = repository.getFileContent(file, "resources.arsc")

                // Load File Tree
                val tree = repository.getFileTree(file)

                if (manifest == null) {
                    throw Exception("AndroidManifest.xml not found in APK")
                }

                // Pre-validate parsing to ensure safety
                var label = "Unknown"
                var pkg = ""
                try {
                    label = parser.getAppLabel(manifest, arsc)
                    pkg = parser.getPackageName(manifest)
                } catch (e: Exception) {
                    // Log but don't fail loading entirely
                    e.printStackTrace()
                    label = "Error Parsing"
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "APK Loaded. Ready to Mod.",
                        manifestBytes = manifest,
                        arscBytes = arsc,
                        appLabel = label,
                        packageName = pkg,
                        fileTree = tree,
                        currentApkFile = file
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "Error: ${e.message}",
                        error = e.message
                    )
                }
            }
        }
    }

    fun updateManifest(bytes: ByteArray) {
        _uiState.value = _uiState.value.copy(manifestBytes = bytes)
    }

    fun updateArsc(bytes: ByteArray) {
        _uiState.value = _uiState.value.copy(arscBytes = bytes)
    }

    fun updateFile(path: String, bytes: ByteArray) {
        val currentState = _uiState.value
        val mods = currentState.modifiedFiles.toMutableMap()
        mods[path] = bytes

        var newManifest = currentState.manifestBytes
        var newArsc = currentState.arscBytes

        if (path == "AndroidManifest.xml") newManifest = bytes
        if (path == "resources.arsc") newArsc = bytes

        _uiState.value = currentState.copy(
            modifiedFiles = mods,
            manifestBytes = newManifest,
            arscBytes = newArsc
        )
    }

    fun getFileContent(path: String): ByteArray? {
        val currentState = _uiState.value
        // Return modified content if exists
        if (currentState.modifiedFiles.containsKey(path)) {
            return currentState.modifiedFiles[path]
        }
        // Else load from file
        val file = currentState.currentApkFile ?: return null
        return repository.getFileContent(file, path)
    }

    fun saveApk() {
        val currentState = _uiState.value
        val uri = currentState.apkUri ?: return

        _uiState.value = currentState.copy(
            isProcessing = true,
            statusMessage = "Rebuilding & Signing..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Save to app external dir for simplicity
                val dest = File(context.getExternalFilesDir(null), "modded.apk")

                val mods = mutableMapOf<String, ByteArray>()
                mods.putAll(currentState.modifiedFiles)

                // Prioritize explicit state for Identity/Visuals tabs if they differ
                if (currentState.manifestBytes != null) mods["AndroidManifest.xml"] = currentState.manifestBytes
                if (currentState.arscBytes != null) mods["resources.arsc"] = currentState.arscBytes

                apkForge.build(uri, dest, mods)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "Saved to: ${dest.absolutePath}"
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "Build Failed: ${e.message}",
                        error = e.message
                    )
                }
            }
        }
    }
}
