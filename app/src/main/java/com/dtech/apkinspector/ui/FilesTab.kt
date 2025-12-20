package com.dtech.apkinspector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dtech.apkinspector.data.ApkFileNode
import com.dtech.apkinspector.data.BinaryXmlParser
import com.dtech.apkinspector.analyzer.DexAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesTab(
    fileTree: List<ApkFileNode>,
    onFileContentRequest: (String) -> ByteArray?,
    onFileUpdate: (String, ByteArray) -> Unit
) {
    var currentPath by remember { mutableStateOf("") }
    var viewingFile by remember { mutableStateOf<ApkFileNode?>(null) }
    var viewingContent by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()

    // If viewing a file, show the editor/viewer
    if (viewingFile != null) {
        FileEditor(
            node = viewingFile!!,
            initialContent = viewingContent,
            onSave = { newBytes ->
                onFileUpdate(viewingFile!!.path, newBytes)
                viewingFile = null // Close editor
            },
            onClose = { viewingFile = null }
        )
        return
    }

    // File Browser
    Column(modifier = Modifier.fillMaxSize()) {
        // Breadcrumb / Nav Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentPath.isNotEmpty()) {
                IconButton(onClick = {
                    // Go up
                    currentPath = currentPath.removeSuffix("/").substringBeforeLast('/', "")
                    if (currentPath.isNotEmpty() || currentPath.contains("/")) currentPath += "/"
                    else currentPath = ""
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Up")
                }
            }
            Text(
                text = if (currentPath.isEmpty()) "/" else "/$currentPath",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        HorizontalDivider()

        val nodes = remember(fileTree, currentPath) {
            val prefix = currentPath
            val filtered = fileTree.filter { it.path.startsWith(prefix) }
            val grouped = mutableMapOf<String, ApkFileNode>()

            filtered.forEach { node ->
                val rel = node.path.removePrefix(prefix)
                if (rel.isNotEmpty()) {
                    val segment = rel.substringBefore('/')
                    val isDir = rel.contains('/') || node.isDir
                    // If we already have this segment, prefer directory? Or treat as same.
                    if (!grouped.containsKey(segment)) {
                        // Create a synthetic node for directory if needed
                        if (isDir) {
                            grouped[segment] = ApkFileNode(prefix + segment + "/", 0, 0, true)
                        } else {
                            grouped[segment] = node
                        }
                    }
                }
            }
            grouped.values.sortedWith(compareBy({ !it.isDir }, { it.path }))
        }

        LazyColumn {
            items(nodes) { node ->
                val name = node.path.removePrefix(currentPath).removeSuffix("/")
                FileRow(name, node.isDir, node.size) {
                    if (node.isDir) {
                        currentPath = node.path
                    } else {
                        // Open File
                        scope.launch(Dispatchers.IO) {
                            val content = onFileContentRequest(node.path)
                            withContext(Dispatchers.Main) {
                                viewingContent = content
                                viewingFile = node
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(name: String, isDir: Boolean, size: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if(isDir) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (!isDir) {
                Text("$size bytes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditor(
    node: ApkFileNode,
    initialContent: ByteArray?,
    onSave: (ByteArray) -> Unit,
    onClose: () -> Unit
) {
    val isText = isTextFile(node.path)
    val isXml = node.path.endsWith(".xml", ignoreCase = true)
    val isDex = node.path.endsWith(".dex", ignoreCase = true)

    // Attempt to detect if XML is binary
    val isBinaryXml = remember(initialContent) {
        if (initialContent != null && isXml) {
             // Check magic number (Type 0x0003 for XML Chunk)
             if (initialContent.size >= 2) {
                 val type = (initialContent[0].toInt() and 0xFF) or ((initialContent[1].toInt() and 0xFF) shl 8)
                 type == 0x0003
             } else false
        } else false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(node.path.substringAfterLast('/')) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
            }
        )

        if (initialContent == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Error loading content")
            }
        } else if (isDex) {
            DexViewer(initialContent)
        } else if (isBinaryXml) {
            // Decoded XML View (Read Only)
            val parser = remember(initialContent) { BinaryXmlParser(initialContent) }
            val decoded = remember(initialContent) { parser.decode() }

            Column(modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (parser.isValid) "Binary XML (Read-Only)" else "Binary XML (Invalid/Obfuscated)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if(parser.isValid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                )
                HorizontalDivider()
                Text(decoded, fontFamily = FontFamily.Monospace)
            }
        } else if (isText) {
            TextEditor(String(initialContent), onSave)
        } else {
            HexViewer(initialContent)
        }
    }
}

@Composable
fun TextEditor(initialText: String, onSave: (ByteArray) -> Unit) {
    var text by remember { mutableStateOf(initialText) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = {
                onSave(text.toByteArray())
            }) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("Save")
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxSize().padding(8.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        )
    }
}

@Composable
fun DexViewer(bytes: ByteArray) {
    val info = remember(bytes) { DexAnalyzer.analyzeDexBytes(bytes) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("DEX Info", style = MaterialTheme.typography.headlineSmall)
        Text("Strings: ${info.strings}")
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(info.extractedStrings) { str ->
                Text(str, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
            }
        }
    }
}

fun isTextFile(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".txt") || lower.endsWith(".xml") || lower.endsWith(".json") ||
           lower.endsWith(".html") || lower.endsWith(".properties") || lower.endsWith(".js") ||
           lower.endsWith(".css") || lower.endsWith(".svg") || lower.endsWith(".yaml") || lower.endsWith(".yml")
}
