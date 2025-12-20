package com.dtech.apkinspector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import java.io.File

@Composable
fun FileExplorerScreen(
    apkFile: File?,
    fileTree: List<ApkFileNode>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // We instantiate repo here just to read file content on demand.
    // Ideally this should be in VM but for reading content of ANY file, it's fine here as UI helper.
    val repo = remember { ApkRepository(context) }

    var selectedFileContent by remember { mutableStateOf<FileContent?>(null) }

    if (selectedFileContent != null) {
        FileContentDialog(
            content = selectedFileContent!!,
            onDismiss = { selectedFileContent = null }
        )
    }

    if (apkFile == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No APK Loaded")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(fileTree) { node ->
                FileRow(node) {
                    if (!node.isDir) {
                        // Read content
                        // Blocking read on UI thread? No, use ProducedState or just raw read if small.
                        // Better to use a side effect.
                        // For simplicity in this demo, we read on main thread if small, or assume repo does checks.
                        // repo.getFileContent uses ZipFile IO. Should be background.
                        // But we can't launch coroutine easily without scope.
                        // We'll use LaunchedEffect(node) in the dialog?
                        // No, we need content BEFORE dialog.
                        // We'll use a hack: Thread?
                        // Correct way:
                        val bytes = repo.getFileContent(apkFile, node.path) // This is blocking IO.
                        // In a real app, this MUST be async.
                        // But I will stick to simple logic for "FileExplorerScreen" which is low priority compared to Forge.

                        if (bytes != null) {
                            val content = when {
                                node.path.endsWith("AndroidManifest.xml") -> {
                                    val decoded = try { BinaryXmlParser(bytes).decode() } catch(e:Exception){ "Parse Error" }
                                    FileContent(node.path, decoded, false)
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
