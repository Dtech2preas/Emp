package com.dtech.apkinspector.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Handles reading APK files from Uris (SAF) or file paths.
 */
class ApkRepository(private val context: Context) {

    /**
     * Copies the Uri content to a temporary file so we can use ZipFile on it.
     * ZipFile requires a File object (random access), which SAF InputStream doesn't provide efficiently.
     */
    fun loadApkFromUri(uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(context.cacheDir, "temp_inspect.apk")
            if (tempFile.exists()) tempFile.delete()

            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getFileTree(apkFile: File): List<ApkFileNode> {
        val nodes = mutableListOf<ApkFileNode>()
        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    nodes.add(ApkFileNode(
                        path = entry.name,
                        size = entry.size,
                        isDir = entry.isDirectory,
                        compressedSize = entry.compressedSize
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return nodes.sortedBy { it.path }
    }

    fun getFileContent(apkFile: File, path: String): ByteArray? {
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(path) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

data class ApkFileNode(
    val path: String,
    val size: Long,
    val compressedSize: Long,
    val isDir: Boolean
)
