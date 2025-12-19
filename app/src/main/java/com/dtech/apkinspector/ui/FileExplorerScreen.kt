package com.dtech.apkinspector.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dtech.apkinspector.data.ApkFileNode
import com.dtech.apkinspector.data.ApkRepository
import com.dtech.apkinspector.data.BinaryXmlParser
import com.dtech.apkinspector.analyzer.DexAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    apkUri: Uri?,
    appPackage: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ApkRepository(context) }

    var fileList by remember { mutableStateOf<List<ApkFileNode>>(emptyList()) }
    var selectedFileContent by remember { mutableStateOf<FileContent?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Load file list
    LaunchedEffect(apkUri, appPackage) {
        isLoading = true
        launch(Dispatchers.IO) {
            val file = if (apkUri != null) repo.loadApkFromUri(apkUri) else null
            if (file != null) {
                fileList = repo.getFileTree(file)
            }
            isLoading = false
        }
    }

    if (selectedFileContent != null) {
        FileContentDialog(
            content = selectedFileContent!!,
            onDismiss = { selectedFileContent = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Explorer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                items(fileList) { node ->
                    FileRow(node) {
                        if (!node.isDir) {
                            scope.launch(Dispatchers.IO) {
                                val file = if (apkUri != null) repo.loadApkFromUri(apkUri) else null
                                if (file != null) {
                                    val bytes = repo.getFileContent(file, node.path)
                                    if (bytes != null) {
                                        val content = when {
                                            node.path.endsWith("AndroidManifest.xml") -> {
                                                val decoded = BinaryXmlParser().decode(bytes)
                                                FileContent(node.path, decoded, false)
                                            }
                                            node.path.endsWith(".dex") -> {
                                                // Create a temp file for DexAnalyzer (hacky but works for now)
                                                val tempDex = File(context.cacheDir, "temp.dex")
                                                tempDex.writeBytes(bytes)
                                                // We can't use the File-based analyzer easily here since it expects APK zip
                                                // So we just show "Binary DEX" or Hex
                                                // Ideally we'd parse the byte array directly.
                                                // Let's just show Hex for DEX in this explorer view
                                                // OR extracted strings if we had a byte-array parser exposed.
                                                FileContent(node.path, null, true, bytes)
                                            }
                                            isTextFile(node.path) -> {
                                                FileContent(node.path, String(bytes), false)
                                            }
                                            else -> {
                                                FileContent(node.path, null, true, bytes)
                                            }
                                        }
                                        selectedFileContent = content
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class FileContent(
    val name: String,
    val text: String?,
    val isBinary: Boolean,
    val bytes: ByteArray? = null
)

fun isTextFile(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".txt") || lower.endsWith(".xml") || lower.endsWith(".json") ||
           lower.endsWith(".html") || lower.endsWith(".properties") || lower.endsWith(".js")
}

@Composable
fun FileRow(node: ApkFileNode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (node.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(node.path, style = MaterialTheme.typography.bodyMedium)
            Text("${node.size} bytes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun FileContentDialog(content: FileContent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(content.name) },
        text = {
            if (content.isBinary && content.bytes != null) {
                // Use HexViewer
                HexViewer(content.bytes)
            } else {
                // Text Viewer
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                     Text(
                        text = content.text ?: "Empty",
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    )
}
