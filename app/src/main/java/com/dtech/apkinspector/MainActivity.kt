package com.dtech.apkinspector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dtech.apkinspector.ui.HomeScreen
import com.dtech.apkinspector.ui.DashboardScreen
import com.dtech.apkinspector.ui.FileExplorerScreen
import com.dtech.apkinspector.ui.theme.DTechTheme

class MainActivity : ComponentActivity() {

    // Simple state holder for the selected APK Uri
    // In a real app, use ViewModel.
    var currentApkUri by mutableStateOf<Uri?>(null)
    var currentAppPackage by mutableStateOf<String?>(null)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            currentApkUri = it
            currentAppPackage = null
            // Trigger navigation manually or via state observation
        }
    }

    fun pickFile() {
        filePickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DTechTheme {
                val navController = rememberNavController()

                // Effect to navigate when file is picked
                LaunchedEffect(currentApkUri) {
                    if (currentApkUri != null) {
                        navController.navigate("dashboard")
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onPickFile = { pickFile() },
                                onAppSelected = { pkg ->
                                    currentAppPackage = pkg
                                    currentApkUri = null // Reset file URI to indicate package mode
                                    navController.navigate("dashboard")
                                }
                            )
                        }
                        composable("dashboard") {
                            DashboardScreen(
                                apkUri = currentApkUri,
                                appPackage = currentAppPackage,
                                onNavigateToExplorer = { navController.navigate("explorer") },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("explorer") {
                            FileExplorerScreen(
                                apkUri = currentApkUri,
                                appPackage = currentAppPackage,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
