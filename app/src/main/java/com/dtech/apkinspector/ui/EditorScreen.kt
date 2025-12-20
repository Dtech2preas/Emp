package com.dtech.apkinspector.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtech.apkinspector.EditorState
import java.util.Locale

@Composable
fun EditorScreen(
    editorState: EditorState,
    onUpdateAppName: (String) -> Unit,
    onTogglePermission: (String, Boolean) -> Unit,
    onUpdateColor: (String, Int) -> Unit,
    onReplaceIcon: (String, ByteArray) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("IDENTITY", "VISUALS")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> IdentityTab(editorState, onUpdateAppName, onTogglePermission)
            1 -> VisualsTab(editorState, onUpdateColor, onReplaceIcon)
        }
    }
}

@Composable
fun IdentityTab(
    state: EditorState,
    onUpdateAppName: (String) -> Unit,
    onTogglePermission: (String, Boolean) -> Unit
) {
    var appName by remember(state.appName) { mutableStateOf(state.appName) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("App Identity", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = appName,
                onValueChange = {
                    appName = it
                    onUpdateAppName(it)
                },
                label = { Text("App Label") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Permissions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(state.permissions) { perm ->
            val isRemoved = state.removedPermissions.contains(perm)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = perm.replace("android.permission.", ""),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = !isRemoved,
                    onCheckedChange = { enabled -> onTogglePermission(perm, enabled) }
                )
            }
            Divider()
        }
    }
}

@Composable
fun VisualsTab(
    state: EditorState,
    onUpdateColor: (String, Int) -> Unit,
    onReplaceIcon: (String, ByteArray) -> Unit
) {
    val context = LocalContext.current
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && state.iconPath != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                onReplaceIcon(state.iconPath, bytes)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Icons", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Detected Icon Path:")
                    Text(state.iconPath ?: "Not found", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { iconPicker.launch("image/png") },
                        enabled = state.iconPath != null
                    ) {
                        Text("REPLACE ICON (PNG)")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Colors", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }

        items(state.colors.toList()) { (name, colorInt) ->
            var showDialog by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDialog = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(colorInt))
                        .border(1.dp, Color.Gray)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text("#${Integer.toHexString(colorInt).uppercase(Locale.getDefault())}")
                }
            }

            if (showDialog) {
                ColorEditDialog(
                    name = name,
                    initialColor = colorInt,
                    onDismiss = { showDialog = false },
                    onConfirm = { newColor ->
                        onUpdateColor(name, newColor)
                        showDialog = false
                    }
                )
            }
            Divider()
        }
    }
}

@Composable
fun ColorEditDialog(
    name: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hexText by remember { mutableStateOf(Integer.toHexString(initialColor).uppercase()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Color: $name") },
        text = {
            Column {
                OutlinedTextField(
                    value = hexText,
                    onValueChange = {
                        hexText = it
                        // Validate
                        try {
                            // Helper to parse hex
                            val parsed = java.lang.Long.parseLong(it, 16)
                            error = null
                        } catch (e: Exception) {
                            error = "Invalid Hex"
                        }
                    },
                    label = { Text("Hex Code (ARGB)") },
                    isError = error != null
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Preview
                try {
                     val c = java.lang.Long.parseLong(hexText, 16).toInt()
                     Box(
                         modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(Color(c))
                            .border(1.dp, Color.Gray)
                     )
                } catch (e: Exception) {}
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val c = java.lang.Long.parseLong(hexText, 16).toInt()
                        onConfirm(c)
                    } catch (e: Exception) {}
                },
                enabled = error == null
            ) {
                Text("SAVE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}
