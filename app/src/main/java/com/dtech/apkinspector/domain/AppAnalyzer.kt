package com.dtech.apkinspector.domain

import com.dtech.apkinspector.analyzer.*
import com.dtech.apkinspector.data.BinaryXmlParser
import com.dtech.apkinspector.data.ApkRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AnalysisResult(
    val appName: String,
    val packageName: String,
    val permissions: List<String>,
    val libraries: List<NativeLibAnalyzer.LibInfo>,
    val riskReport: RiskEngine.RiskReport,
    val dexInfo: DexAnalyzer.DexInfo,
    val manifestContent: String
)

class AppAnalyzer {

    suspend fun analyzeApk(apkFile: File): AnalysisResult = withContext(Dispatchers.Default) {
        val repo = ApkRepository(android.content.ContextWrapper(null)) // Hacky? No, we need context for Repo only for Uri.
        // Actually, Repo uses Context for contentResolver. But here we have a File.
        // We can use standard ZipFile which is what Repo.getFileContent does, but logic is scattered.
        // Let's just use ZipFile directly or helper here.

        // 1. Get Manifest
        val manifestBytes = getFileContent(apkFile, "AndroidManifest.xml")
        val manifestXml = if (manifestBytes != null) BinaryXmlParser(manifestBytes).decode() else "Manifest missing"

        // 2. Parse Manifest for Permissions/Package (Simple Regex on the decoded XML for now)
        val permissions = extractPermissions(manifestXml)
        val packageName = extractPackageName(manifestXml)
        val isDebuggable = manifestXml.contains("android:debuggable=\"true\"")

        // 3. Analyze Dex
        val dexInfo = DexAnalyzer.analyze(apkFile)

        // 4. Analyze Libs
        val libs = NativeLibAnalyzer.analyze(apkFile)

        // 5. Risk Score
        val riskReport = RiskEngine.calculateRisk(permissions, isDebuggable, libs)

        AnalysisResult(
            appName = packageName, // Placeholder
            packageName = packageName,
            permissions = permissions,
            libraries = libs,
            riskReport = riskReport,
            dexInfo = dexInfo,
            manifestContent = manifestXml
        )
    }

    private fun getFileContent(file: File, path: String): ByteArray? {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry(path) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (e: Exception) { null }
    }

    private fun extractPermissions(xml: String): List<String> {
        val perms = mutableListOf<String>()
        val regex = "android.permission.[A-Z_0-9]+".toRegex()
        regex.findAll(xml).forEach {
            perms.add(it.value)
        }
        return perms.distinct()
    }

    private fun extractPackageName(xml: String): String {
        // Look for package="com.example"
        // The BinaryXmlParser output format: <manifest ... package="com.example" ...>
        val match = "package=\"([^\"]+)\"".toRegex().find(xml)
        return match?.groupValues?.get(1) ?: "Unknown"
    }
}
