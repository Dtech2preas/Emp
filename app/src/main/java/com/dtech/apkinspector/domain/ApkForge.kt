package com.dtech.apkinspector.domain

import android.content.Context
import android.net.Uri
import java.io.*
import java.util.zip.*

class ApkForge(private val context: Context) {

    fun build(
        sourceApk: Uri,
        destFile: File,
        modifications: Map<String, ByteArray>
    ) {
        val tempDir = File(context.cacheDir, "forge_temp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            // 1. Extract
            unzip(sourceApk, tempDir)

            // 2. Apply Modifications
            modifications.forEach { (path, bytes) ->
                val f = File(tempDir, path)
                if (path.contains("/")) f.parentFile.mkdirs()
                f.writeBytes(bytes)
            }

            // 3. Zip (Simple Alignment handled by Store method)
            val unsignedApk = File(context.cacheDir, "unsigned.apk")
            if (unsignedApk.exists()) unsignedApk.delete()
            zip(tempDir, unsignedApk)

            // 4. Sign
            val signer = V1Signer()
            signer.sign(unsignedApk, destFile)

        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun unzip(uri: Uri, destDir: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val f = File(destDir, entry.name)
                    // Sanitize path (Zip Slip vulnerability check)
                    if (!f.canonicalPath.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("Zip Slip detected")
                    }

                    if (entry.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile.mkdirs()
                        FileOutputStream(f).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun zip(sourceDir: File, destFile: File) {
        val fos = FileOutputStream(destFile)
        val zos = ZipOutputStream(BufferedOutputStream(fos))
        zos.setLevel(Deflater.BEST_COMPRESSION)

        val files = sourceDir.walk().filter { it.isFile }.toList()

        files.forEach { file ->
            val relPath = file.relativeTo(sourceDir).path.replace("\\", "/")

            // Skip signature files if they exist from original
            if (relPath.startsWith("META-INF/")) {
                return@forEach
            }

            val entry = ZipEntry(relPath)

            // Ensure resources.arsc is STORED (uncompressed)
            if (relPath == "resources.arsc") {
                entry.method = ZipEntry.STORED
                entry.size = file.length()
                entry.compressedSize = file.length()
                val crc = CRC32()
                crc.update(file.readBytes())
                entry.crc = crc.value
            }

            zos.putNextEntry(entry)
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        zos.close()
    }
}
