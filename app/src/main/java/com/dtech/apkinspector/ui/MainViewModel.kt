package com.dtech.apkinspector.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dtech.apkinspector.domain.ApkForge
import com.dtech.apkinspector.domain.StructuredParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

data class MainUiState(
    val apkUri: Uri? = null,
    val isProcessing: Boolean = false,
    val statusMessage: String = "Select an APK to begin",
    val manifestBytes: ByteArray? = null,
    val arscBytes: ByteArray? = null,
    val appLabel: String? = null,
    val packageName: String? = null,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val context = application.applicationContext
    private val apkForge = ApkForge(context)
    private val parser = StructuredParsers()

    fun loadApk(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Loading APK...",
            apkUri = uri,
            error = null,
            manifestBytes = null,
            arscBytes = null,
            appLabel = null,
            packageName = null
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var manifest: ByteArray? = null
                var arsc: ByteArray? = null

                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name == "AndroidManifest.xml") {
                                manifest = zis.readBytes()
                            } else if (entry.name == "resources.arsc") {
                                arsc = zis.readBytes()
                            }
                            entry = zis.nextEntry
                        }
                    }
                }

                if (manifest == null) {
                    throw Exception("AndroidManifest.xml not found in APK")
                }

                // Pre-validate parsing to ensure safety
                var label = "Unknown"
                var pkg = ""
                try {
                    label = parser.getAppLabel(manifest!!, arsc)
                    pkg = parser.getPackageName(manifest!!)
                } catch (e: Exception) {
                    // Log but don't fail loading entirely if possible?
                    // Actually, if parsing fails, we might want to warn user but allow Hex editing?
                    // For now, let's capture the error so user knows.
                    throw Exception("Failed to parse Manifest: ${e.message}")
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "APK Loaded. Ready to Mod.",
                        manifestBytes = manifest,
                        arscBytes = arsc,
                        appLabel = label,
                        packageName = pkg
                    )
                }
            } catch (e: Exception) {
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

    fun saveApk() {
        val currentState = _uiState.value
        val uri = currentState.apkUri ?: return

        _uiState.value = currentState.copy(
            isProcessing = true,
            statusMessage = "Rebuilding & Signing..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Save to app external dir for simplicity (as in original code)
                val dest = File(context.getExternalFilesDir(null), "modded.apk")

                val mods = mutableMapOf<String, ByteArray>()
                if (currentState.manifestBytes != null) mods["AndroidManifest.xml"] = currentState.manifestBytes
                if (currentState.arscBytes != null) mods["resources.arsc"] = currentState.arscBytes

                apkForge.build(uri, dest, mods)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "Saved to: ${dest.absolutePath}"
                    )
                    // Toast can be handled by UI observing status or a separate Event flow
                    // For now statusMessage is enough
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
