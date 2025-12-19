package com.dtech.apkinspector.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtech.apkinspector.domain.ApkForge
import com.dtech.apkinspector.domain.StructuredParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apkUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Select an APK to begin") }

    // Core Logic Instances
    val apkForge = remember { ApkForge(context) }
    val parser = remember { StructuredParsers() }

    // State for Editors
    var manifestBytes by remember { mutableStateOf<ByteArray?>(null) }
    var arscBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Modifications
    val edits = remember { mutableStateMapOf<String, ByteArray>() } // Path -> Bytes

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            apkUri = uri
            statusMessage = "Loading APK..."
            isProcessing = true
            scope.launch(Dispatchers.IO) {
                try {
                    // Extract minimal files for editing (Manifest, ARSC)
                    // We need a helper to just read files without full unzip?
                    // ApkForge.unzip does full unzip. Let's stick to full unzip to temp for now?
                    // Or read specifically.
                    // For performance, reading just 2 files is better.
                    // But ApkForge logic relies on unzip.
                    // Let's unzip fully to a "workspace" dir.
                    val workspace = File(context.cacheDir, "workspace")
                    // apkForge.unzip(uri, workspace) // Not public
                    // We need to implement workspace logic in MainScreen or expose it.

                    // Simplification: Read Manifest/Arsc from stream directly
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        java.util.zip.ZipInputStream(input).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (entry.name == "AndroidManifest.xml") {
                                    manifestBytes = zis.readBytes()
                                } else if (entry.name == "resources.arsc") {
                                    arscBytes = zis.readBytes()
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        statusMessage = "APK Loaded. Ready to Mod."
                        isProcessing = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Error: ${e.message}"
                        isProcessing = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("D-TECH MODDER", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            if (apkUri != null && !isProcessing) {
                ExtendedFloatingActionButton(
                    onClick = {
                        isProcessing = true
                        statusMessage = "Rebuilding & Signing..."
                        scope.launch(Dispatchers.IO) {
                            try {
                                val dest = File(context.getExternalFilesDir(null), "modded.apk") // Save to app dir for now
                                // Apply our edits
                                // We need to update edits map based on EditorTabs state.
                                // Actually EditorTabs should update 'manifestBytes' and 'arscBytes' directly.

                                val mods = mutableMapOf<String, ByteArray>()
                                if (manifestBytes != null) mods["AndroidManifest.xml"] = manifestBytes!!
                                if (arscBytes != null) mods["resources.arsc"] = arscBytes!!

                                apkForge.build(apkUri!!, dest, mods)

                                withContext(Dispatchers.Main) {
                                    statusMessage = "Saved to: ${dest.absolutePath}"
                                    Toast.makeText(context, "Build Success!", Toast.LENGTH_LONG).show()
                                    isProcessing = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusMessage = "Build Failed: ${e.message}"
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    icon = { Icon(Icons.Default.Build, "Build") },
                    text = { Text("REBUILD") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (apkUri == null) {
                Button(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive")) }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("OPEN APK")
                }
            } else {
                if (isProcessing) {
                    CircularProgressIndicator()
                } else {
                    // Editor Tabs
                    EditorTabs(
                        manifestBytes = manifestBytes,
                        arscBytes = arscBytes,
                        onManifestChanged = { manifestBytes = it },
                        onArscChanged = { arscBytes = it }
                    )
                }
            }
        }
    }
}
