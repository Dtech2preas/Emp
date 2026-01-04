package com.dtech.apkinspector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.loadApk(uri)
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
            if (state.apkUri != null && !state.isProcessing) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.saveApk()
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
                colors = CardDefaults.cardColors(
                    containerColor = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    text = state.statusMessage,
                    modifier = Modifier.padding(16.dp),
                    color = if (state.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                )
            }

            if (state.apkUri == null) {
                HomeScreen(
                    onPickFile = { picker.launch(arrayOf("application/vnd.android.package-archive")) },
                    onAppSelected = { file -> viewModel.loadApkFromFile(file) }
                )
            } else {
                if (state.isProcessing) {
                    CircularProgressIndicator()
                } else {
                    // Editor Tabs
                    if (state.manifestBytes != null) {
                        EditorTabs(
                            manifestBytes = state.manifestBytes,
                            arscBytes = state.arscBytes,
                            fileTree = state.fileTree,
                            onManifestChanged = viewModel::updateManifest,
                            onArscChanged = viewModel::updateArsc,
                            onFileUpdate = viewModel::updateFile,
                            onFileContentRequest = viewModel::getFileContent
                        )
                    } else if (state.error == null) {
                         Text("Manifest not found or failed to load.")
                    }
                }
            }
        }
    }
}
