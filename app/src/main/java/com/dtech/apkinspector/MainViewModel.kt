package com.dtech.apkinspector

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dtech.apkinspector.data.ApkRepository
import com.dtech.apkinspector.data.ArscEditor
import com.dtech.apkinspector.data.BinaryXmlParser
import com.dtech.apkinspector.domain.ApkForge
import com.dtech.apkinspector.domain.StructuredParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ApkRepository(application)
    private val apkForge = ApkForge(application)
    private val structuredParsers = StructuredParsers()

    // States
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _editorState = MutableStateFlow(EditorState())
    val editorState = _editorState.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<String>>(emptyList())
    val terminalLogs = _terminalLogs.asStateFlow()

    // Internal working file
    private var loadedApkFile: File? = null

    fun loadApk(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = UiState.Loading("Loading APK...")
                log("Loading APK from $uri...")

                val file = repository.loadApkFromUri(uri)
                if (file == null) {
                    _uiState.value = UiState.Error("Failed to copy APK from URI")
                    log("Error: Failed to copy APK")
                    return@launch
                }
                loadedApkFile = file
                log("APK copied to cache: ${file.name} (${file.length() / 1024} KB)")

                // Parse structure
                val tree = repository.getFileTree(file)

                // Read Manifest & ARSC
                val manifestBytes = repository.getFileContent(file, "AndroidManifest.xml")
                val arscBytes = repository.getFileContent(file, "resources.arsc")

                if (manifestBytes == null) {
                    _uiState.value = UiState.Error("Invalid APK: No AndroidManifest.xml")
                    log("Error: No Manifest found")
                    return@launch
                }

                // Initial Parsing
                log("Parsing Manifest...")
                val manifestParser = BinaryXmlParser(manifestBytes)
                val permissions = manifestParser.getPermissions()
                val pkgName = structuredParsers.getPackageName(manifestBytes)
                val appLabel = structuredParsers.getAppLabel(manifestBytes, arscBytes)

                var iconPath: String? = null
                if (arscBytes != null) {
                    val iconId = structuredParsers.getAppIconId(manifestBytes)
                    if (iconId != null) {
                        iconPath = structuredParsers.resolveIconPath(arscBytes, iconId)
                    }
                }

                val colors = if (arscBytes != null) structuredParsers.getColors(arscBytes) else emptyMap()

                _editorState.value = EditorState(
                    manifestBytes = manifestBytes,
                    arscBytes = arscBytes,
                    fileTree = tree,
                    permissions = permissions,
                    appName = appLabel,
                    packageName = pkgName,
                    iconPath = iconPath,
                    colors = colors
                )

                _uiState.value = UiState.Success(file)
                log("APK Loaded Successfully.")

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("Crash: ${e.message}")
                log("Crash: ${e.stackTraceToString()}")
            }
        }
    }

    fun togglePermission(permission: String, enabled: Boolean) {
        val current = _editorState.value
        if (current.manifestBytes == null) return

        if (!enabled) {
            // Remove permission
            val newRemoved = current.removedPermissions + permission
            _editorState.value = current.copy(removedPermissions = newRemoved)
            log("Permission scheduled for removal: $permission")
        } else {
            // Re-enable (un-remove)
            val newRemoved = current.removedPermissions - permission
            _editorState.value = current.copy(removedPermissions = newRemoved)
            log("Permission restored: $permission")
        }
    }

    fun updateAppName(newName: String) {
        val current = _editorState.value
        _editorState.value = current.copy(appName = newName)
        log("App Name updated to: $newName")
    }

    fun updateColor(name: String, color: Int) {
        // We defer applying until rebuild, or we apply locally to bytes?
        // StructuredParsers returns new bytes.
        // It's better to keep modifications in a separate map or update the source bytes in state.
        // Let's update source bytes in state to reflect changes immediately if we were re-parsing.
        // But re-parsing whole arsc on every color change is heavy?
        // Actually ArscEditor is fast enough for single change.

        viewModelScope.launch(Dispatchers.IO) {
            val current = _editorState.value
            if (current.arscBytes != null) {
                try {
                    val newArsc = structuredParsers.updateColor(current.arscBytes, name, color)
                    // Also update colors map
                    val newColors = current.colors.toMutableMap()
                    newColors[name] = color

                    _editorState.value = current.copy(arscBytes = newArsc, colors = newColors)
                    log("Color $name updated.")
                } catch (e: Exception) {
                    log("Failed to update color: ${e.message}")
                }
            }
        }
    }

    fun replaceIcon(targetPath: String, pngBytes: ByteArray) {
        val current = _editorState.value
        val newMods = current.modifications.toMutableMap()
        newMods[targetPath] = pngBytes
        _editorState.value = current.copy(modifications = newMods)
        log("Icon replacement scheduled for: $targetPath")
    }

    fun rebuild(targetUri: Uri?) {
        // If targetUri is needed (SAF CreateDocument), or we just save to predefined location.
        // The prompt says "Export: Save the final APK to the device Downloads folder."
        // SAF requires user interaction to save to Downloads usually, or we save to app-specific and share.
        // Or we use the uri from CreateDocument.
        // We will assume 'targetUri' is passed from UI (CreateDocument result).

        if (targetUri == null || loadedApkFile == null) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = UiState.Loading("Rebuilding...")
                log("Starting Rebuild Process...")

                val current = _editorState.value
                val finalMods = current.modifications.toMutableMap()

                // 1. Apply Manifest Changes
                if (current.manifestBytes != null) {
                    log("Applying Manifest edits...")
                    var workingManifest = current.manifestBytes

                    // a) App Name / Package (via StructuredParsers)
                    // Note: We need to verify if App Name changed in Arsc or Manifest.
                    // StructuredParsers.updateAppLabel handles both if we pass both.
                    val labelRes = structuredParsers.updateAppLabel(workingManifest, current.arscBytes, current.appName)
                    if (labelRes.manifest != null) workingManifest = labelRes.manifest!!
                    if (labelRes.arsc != null) {
                        // Update our ARSC bytes reference for next steps
                        _editorState.value = current.copy(arscBytes = labelRes.arsc)
                    }

                    // b) Permissions (BinaryXmlParser)
                    if (current.removedPermissions.isNotEmpty()) {
                        log("Removing ${current.removedPermissions.size} permissions...")
                        val parser = BinaryXmlParser(workingManifest)
                        current.removedPermissions.forEach { parser.removePermission(it) }
                        workingManifest = parser.rebuild()
                    }

                    finalMods["AndroidManifest.xml"] = workingManifest
                }

                // 2. Apply ARSC Changes
                // ARSC bytes in state are already updated by updateColor/updateAppName
                if (current.arscBytes != null) {
                    finalMods["resources.arsc"] = current.arscBytes
                }

                // 3. Forge
                log("Forging APK...")
                // We need a temp file for output before copying to Uri
                val tempOut = File(getApplication<Application>().cacheDir, "gen_signed.apk")

                // ApkForge.build expects Uri for source. We have file.
                // We can use Uri.fromFile(loadedApkFile)
                apkForge.build(Uri.fromFile(loadedApkFile), tempOut, finalMods)

                log("Signing Complete.")

                // 4. Copy to Target Uri
                log("Saving to destination...")
                getApplication<Application>().contentResolver.openOutputStream(targetUri)?.use { out ->
                    tempOut.inputStream().use { it.copyTo(out) }
                }

                log("Done! Saved to ${targetUri.path}")
                _uiState.value = UiState.Success(loadedApkFile!!) // Keep loaded file

            } catch (e: Exception) {
                e.printStackTrace()
                log("Build Failed: ${e.message}")
                _uiState.value = UiState.Error("Build Failed: ${e.message}")
            }
        }
    }

    private fun log(msg: String) {
        val list = _terminalLogs.value.toMutableList()
        list.add("> $msg")
        _terminalLogs.value = list
    }
}

sealed class UiState {
    object Idle : UiState()
    data class Loading(val message: String) : UiState()
    data class Success(val file: File) : UiState()
    data class Error(val message: String) : UiState()
}

data class EditorState(
    val manifestBytes: ByteArray? = null,
    val arscBytes: ByteArray? = null,
    val fileTree: List<com.dtech.apkinspector.data.ApkFileNode> = emptyList(),
    val permissions: List<String> = emptyList(),
    val removedPermissions: List<String> = emptyList(),
    val appName: String = "",
    val packageName: String = "",
    val iconPath: String? = null,
    val colors: Map<String, Int> = emptyMap(),
    val modifications: Map<String, ByteArray> = emptyMap()
)
