package com.dtech.apkinspector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtech.apkinspector.MainViewModel
import com.dtech.apkinspector.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val editorState by viewModel.editorState.collectAsState()
    val logs by viewModel.terminalLogs.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("DASHBOARD", "EDITOR", "FILES")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("D-TECH MODDER", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.primary
                    )
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                            enabled = index == 0 || uiState is UiState.Success // Disable tabs if no APK
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    uiState = uiState,
                    editorState = editorState,
                    logs = logs,
                    onLoadApk = { viewModel.loadApk(it) },
                    onRebuild = { viewModel.rebuild(it) }
                )
                1 -> EditorScreen(
                    editorState = editorState,
                    onUpdateAppName = { viewModel.updateAppName(it) },
                    onTogglePermission = { perm, enabled -> viewModel.togglePermission(perm, enabled) },
                    onUpdateColor = { name, color -> viewModel.updateColor(name, color) },
                    onReplaceIcon = { path, bytes -> viewModel.replaceIcon(path, bytes) }
                )
                2 -> FileExplorerScreen(
                    apkFile = (uiState as? UiState.Success)?.file,
                    fileTree = editorState.fileTree
                )
            }
        }
    }
}
