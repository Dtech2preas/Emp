package com.dtech.apkinspector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtech.apkinspector.domain.StructuredParsers
import com.dtech.apkinspector.data.BinaryXmlParser

@Composable
fun EditorTabs(
    manifestBytes: ByteArray?,
    arscBytes: ByteArray?,
    onManifestChanged: (ByteArray) -> Unit,
    onArscChanged: (ByteArray) -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Identity", "Visuals", "Manifest")
    val parser = remember { StructuredParsers() }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            if (manifestBytes == null) {
                Text("Manifest not found", color = MaterialTheme.colorScheme.error)
            } else {
                when (selectedTabIndex) {
                    0 -> IdentityTab(parser, manifestBytes, arscBytes, onManifestChanged, onArscChanged)
                    1 -> VisualsTab(parser, arscBytes, onArscChanged)
                    2 -> ManifestPreviewTab(manifestBytes)
                }
            }
        }
    }
}

@Composable
fun IdentityTab(
    parser: StructuredParsers,
    manifestBytes: ByteArray,
    arscBytes: ByteArray?,
    onManifestChanged: (ByteArray) -> Unit,
    onArscChanged: (ByteArray) -> Unit
) {
    // We load initial values
    // We use a key to reload if bytes change externally (e.g. rebuild?)
    // Actually we only want to load once or when bytes change.

    val currentLabel = remember(manifestBytes, arscBytes) {
        parser.getAppLabel(manifestBytes, arscBytes)
    }
    val currentPkg = remember(manifestBytes) {
        parser.getPackageName(manifestBytes)
    }

    var labelInput by remember { mutableStateOf(currentLabel) }
    var pkgInput by remember { mutableStateOf(currentPkg) }

    LaunchedEffect(currentLabel) { labelInput = currentLabel }
    LaunchedEffect(currentPkg) { pkgInput = currentPkg }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("App Identity", style = MaterialTheme.typography.headlineSmall)

        // App Name
        OutlinedTextField(
            value = labelInput,
            onValueChange = { labelInput = it },
            label = { Text("App Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val result = parser.updateAppLabel(manifestBytes, arscBytes, labelInput)
                if (result.manifest != null) onManifestChanged(result.manifest)
                if (result.arsc != null && result.arsc.isNotEmpty()) onArscChanged(result.arsc)
            },
            enabled = labelInput != currentLabel
        ) {
            Text("Update Name")
        }

        HorizontalDivider()

        // Package Name
        OutlinedTextField(
            value = pkgInput,
            onValueChange = { pkgInput = it },
            label = { Text("Package Name (Install ID)") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Warning: Changing package name does not refactor code. The app is treated as a new installation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Button(
            onClick = {
                val newBytes = parser.updatePackageName(manifestBytes, pkgInput)
                if (newBytes != null) onManifestChanged(newBytes)
            },
            enabled = pkgInput != currentPkg
        ) {
            Text("Update Package ID")
        }
    }
}

@Composable
fun VisualsTab(
    parser: StructuredParsers,
    arscBytes: ByteArray?,
    onArscChanged: (ByteArray) -> Unit
) {
    if (arscBytes == null) {
        Text("No resources.arsc found.")
        return
    }

    val colors = remember(arscBytes) { parser.getColors(arscBytes) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Colors", style = MaterialTheme.typography.headlineSmall) }

        items(colors.toList()) { (name, colorInt) ->
            var hexInput by remember { mutableStateOf(String.format("#%08X", colorInt)) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Swatch
                Surface(
                    modifier = Modifier.size(40.dp),
                    color = androidx.compose.ui.graphics.Color(colorInt),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)
                ) {}

                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { hexInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Button(onClick = {
                    try {
                        val newColor = android.graphics.Color.parseColor(hexInput)
                        val newBytes = parser.updateColor(arscBytes, name, newColor)
                        onArscChanged(newBytes)
                    } catch(e: Exception) {
                        // Invalid hex
                    }
                }) {
                    Text("Set")
                }
            }
        }
    }
}

@Composable
fun ManifestPreviewTab(manifestBytes: ByteArray) {
    val parser = remember(manifestBytes) { BinaryXmlParser(manifestBytes) }
    val xmlText = remember(manifestBytes) { parser.decode() }

    Column {
        Text("AndroidManifest.xml Preview", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                item {
                    Text(xmlText, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}
