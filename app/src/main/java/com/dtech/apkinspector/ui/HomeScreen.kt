package com.dtech.apkinspector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onPickFile: () -> Unit,
    onAppSelected: (java.io.File) -> Unit
) {
    var showAppDialog by remember { mutableStateOf(false) }

    if (showAppDialog) {
        InstalledAppsDialog(
            onDismiss = { showAppDialog = false },
            onAppSelected = { file ->
                showAppDialog = false
                onAppSelected(file)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "D-TECH APK Inspector",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open APK File")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { showAppDialog = true },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Icon(Icons.Default.Android, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Installed App")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Offline • Secure • No Tracker",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
