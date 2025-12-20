package com.dtech.apkinspector.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtech.apkinspector.EditorState
import com.dtech.apkinspector.UiState
import java.io.File

@Composable
fun DashboardScreen(
    uiState: UiState,
    editorState: EditorState,
    logs: List<String>,
    onLoadApk: (Uri) -> Unit,
    onRebuild: (Uri) -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onLoadApk(uri)
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null) onRebuild(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status / Metadata
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("PROJECT STATUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                when (uiState) {
                    is UiState.Idle -> Text("Waiting for APK...", fontSize = 18.sp)
                    is UiState.Loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(uiState.message)
                        }
                    }
                    is UiState.Error -> Text(uiState.message, color = MaterialTheme.colorScheme.error)
                    is UiState.Success -> {
                        Text("APK LOADED: ${uiState.file.name}", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("App Name: ${editorState.appName}")
                        Text("Package: ${editorState.packageName}")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive")) }) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN APK")
            }

            if (uiState is UiState.Success) {
                Button(
                    onClick = { saveLauncher.launch("modded_${uiState.file.name}") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Icon(Icons.Default.Build, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("REBUILD & SIGN")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Terminal Log
        Text("TERMINAL LOG", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                reverseLayout = true // Show latest at bottom? Usually terminal logs scroll down.
                // If reverseLayout is true, the first item is at the bottom.
                // Our list appends to end. So we want standard layout but auto-scroll.
                // Or just show reverse list.
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = log,
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
