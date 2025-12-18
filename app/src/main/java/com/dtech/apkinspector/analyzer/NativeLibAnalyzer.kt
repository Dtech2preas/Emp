package com.dtech.apkinspector.analyzer

import java.io.File
import java.util.zip.ZipFile

object NativeLibAnalyzer {
    data class LibInfo(val name: String, val arch: String, val size: Long)

    fun analyze(apkFile: File): List<LibInfo> {
        val libs = mutableListOf<LibInfo>()
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    val parts = entry.name.split("/")
                    // expected: lib/<arch>/<name>.so
                    if (parts.size >= 3) {
                        val arch = parts[1]
                        val name = parts.last()
                        libs.add(LibInfo(name, arch, entry.size))
                    }
                }
            }
        }
        return libs
    }
}
