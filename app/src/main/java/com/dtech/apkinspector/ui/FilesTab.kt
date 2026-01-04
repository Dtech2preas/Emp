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
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val isImage = isImageFile(node.path)

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

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // This part needs a way to read bytes from Uri, but Composable context makes it tricky without ViewModel.
        // For simplicity, we assume we can't easily read bytes here without passing a callback up or using context.
        // However, we can ask user to pick, but we need to read it.
    }
    // To properly implement replacement, we need context
    val context = androidx.compose.ui.platform.LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
         if (uri != null) {
             try {
                 context.contentResolver.openInputStream(uri)?.use {
                     onSave(it.readBytes())
                 }
             } catch (e: Exception) {
                 e.printStackTrace()
             }
         }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(node.path.substringAfterLast('/')) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
            },
            actions = {
                if (isImage) {
                    IconButton(onClick = { launcher.launch("image/*") }) {
                        Icon(Icons.Default.Save, "Replace") // Reuse Save icon for Replace
                    }
                }
            }
        )

        if (initialContent == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Error loading content")
            }
        } else if (isDex) {
            DexViewer(initialContent, onSave)
        } else if (isImage) {
             Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                 AsyncImage(
                     model = initialContent,
                     contentDescription = "Image content",
                     modifier = Modifier.fillMaxSize()
                 )
             }
        } else if (isBinaryXml) {
            XmlEditor(initialContent, onSave)
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
fun DexViewer(bytes: ByteArray, onSave: ((ByteArray) -> Unit)? = null) {
    val info = remember(bytes) { DexAnalyzer.analyzeDexBytes(bytes) }
    var editMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // We only support replacing specific strings.
    // State to hold pending replacements: Map<Original, New>
    val replacements = remember { mutableStateMapOf<String, String>() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
             Text("DEX Info: ${info.classes} Classes", style = MaterialTheme.typography.titleMedium)
             if (onSave != null) {
                 Row {
                    FilterChip(
                        selected = editMode,
                        onClick = { editMode = !editMode },
                        label = { Text(if(editMode) "Done Editing" else "Edit Strings") }
                    )
                    if (editMode && replacements.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                             val newBytes = DexAnalyzer.replaceStrings(bytes, replacements)
                             onSave(newBytes)
                             editMode = false
                             replacements.clear()
                        }) {
                            Text("Save")
                        }
                    }
                 }
             }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (editMode) {
             OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Strings") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        LazyColumn {
            val filtered = if (searchQuery.isNotEmpty()) {
                info.extractedStrings.filter { it.contains(searchQuery, true) }
            } else {
                info.extractedStrings
            }

            items(filtered) { str ->
                if (editMode) {
                    var pendingVal by remember(str) { mutableStateOf(replacements[str] ?: str) }
                    OutlinedTextField(
                        value = pendingVal,
                        onValueChange = { newVal ->
                            if (newVal.length <= str.length) {
                                pendingVal = newVal
                                if (newVal != str) {
                                    replacements[str] = newVal
                                } else {
                                    replacements.remove(str)
                                }
                            }
                        },
                        label = { Text("Max len: ${str.length}") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                } else {
                    Text(str, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                }
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

fun isImageFile(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
           lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
}
