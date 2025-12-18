package com.dtech.apkinspector.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dtech.apkinspector.analyzer.RiskEngine
import com.dtech.apkinspector.data.ApkRepository
import com.dtech.apkinspector.domain.AnalysisResult
import com.dtech.apkinspector.domain.AppAnalyzer
import com.dtech.apkinspector.export.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    apkUri: Uri?,
    appPackage: String?,
    onNavigateToExplorer: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(true) }

    // PDF Export
    val pdfExporter = remember { PdfExporter(context) }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val outputStream = context.contentResolver.openOutputStream(it)
                    if (outputStream != null && result != null) {
                        val reportLines = mutableListOf<String>()
                        reportLines.add("Package: ${result!!.packageName}")
                        reportLines.add("Risk Level: ${result!!.riskReport.level}")
                        reportLines.add("Score: ${result!!.riskReport.score}")
                        reportLines.add("--------------------------------")
                        reportLines.add("Permissions:")
                        reportLines.addAll(result!!.permissions)
                        reportLines.add("--------------------------------")
                        reportLines.add("Suspicious Strings/URLs:")
                        reportLines.addAll(result!!.dexInfo.extractedStrings)

                        pdfExporter.exportReport(outputStream, result!!.packageName, reportLines)

                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Saved Report", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(apkUri, appPackage) {
        launch(Dispatchers.IO) {
            val repo = ApkRepository(context)
            val file = if (apkUri != null) repo.loadApkFromUri(apkUri) else null

            if (file != null) {
                val analyzer = AppAnalyzer()
                result = analyzer.analyzeApk(file)
            }
            isAnalyzing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(result?.packageName ?: "Analyzing...") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (result != null) {
                        IconButton(onClick = {
                            saveLauncher.launch("Analysis_${result?.packageName}.pdf")
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Export PDF")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isAnalyzing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Decompiling & Analyzing...", modifier = Modifier.padding(top = 48.dp))
            }
        } else if (result == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Failed to load APK")
            }
        } else {
            val res = result!!
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Risk Card
                RiskCard(res.riskReport)

                Spacer(modifier = Modifier.height(16.dp))

                // Stats
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatCard("Permissions", res.permissions.size.toString())
                    StatCard("Libraries", res.libraries.size.toString())
                    StatCard("Strings", res.dexInfo.strings.toString())
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onNavigateToExplorer,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Browse File System")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Details
                Text("Risk Factors", style = MaterialTheme.typography.titleMedium)
                res.riskReport.factors.forEach {
                    Text("• $it", color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Suspicious Strings / URLs", style = MaterialTheme.typography.titleMedium)
                if (res.dexInfo.extractedStrings.isEmpty()) {
                    Text("None found", style = MaterialTheme.typography.bodySmall)
                } else {
                    res.dexInfo.extractedStrings.take(10).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    if (res.dexInfo.extractedStrings.size > 10) {
                        Text("...and ${res.dexInfo.extractedStrings.size - 10} more", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun RiskCard(report: RiskEngine.RiskReport) {
    val color = when(report.level) {
        RiskEngine.RiskLevel.HIGH -> Color.Red
        RiskEngine.RiskLevel.MEDIUM -> Color(0xFFFFA500) // Orange
        RiskEngine.RiskLevel.LOW -> Color.Green
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (report.level == RiskEngine.RiskLevel.HIGH) Icons.Default.Warning else Icons.Default.Shield,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Risk Level: ${report.level}", style = MaterialTheme.typography.titleLarge, color = color)
                Text("Score: ${report.score}/100", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String) {
    Card(modifier = Modifier.width(100.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
