package com.dtech.apkinspector.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable?,
    val sourceDir: String
)

class InstalledAppRepository(private val context: Context) {

    suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // Query intent for Android 11+ compatibility might be needed in Manifest,
        // but typically GET_META_DATA is enough for basic listing if QUERY_ALL_PACKAGES is granted or not needed for debug.
        // For this task, we assume we can list them.
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        packages.filter { appInfo ->
            // Filter out system apps if desired, or keep them.
            // User likely wants to edit downloaded apps primarily, but maybe system too.
            // Let's include everything but maybe sort user apps first.
            true
        }.map { appInfo ->
            InstalledApp(
                name = pm.getApplicationLabel(appInfo).toString(),
                packageName = appInfo.packageName,
                icon = pm.getApplicationIcon(appInfo),
                sourceDir = appInfo.publicSourceDir
            )
        }.sortedBy { it.name }
    }

    suspend fun extractApk(app: InstalledApp): File? = withContext(Dispatchers.IO) {
        try {
            val srcFile = File(app.sourceDir)
            if (!srcFile.exists() || !srcFile.canRead()) return@withContext null

            val destDir = File(context.cacheDir, "extracted_apks")
            if (!destDir.exists()) destDir.mkdirs()

            val destFile = File(destDir, "${app.packageName}.apk")
            srcFile.copyTo(destFile, overwrite = true)
            return@withContext destFile
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
