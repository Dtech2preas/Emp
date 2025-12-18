package com.dtech.apkinspector.analyzer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

object DexAnalyzer {
    data class DexInfo(
        val classes: Int,
        val strings: Int,
        val methods: Int,
        val extractedStrings: List<String>
    )

    fun analyze(apkFile: File): DexInfo {
        var totalClasses = 0
        val allStrings = mutableListOf<String>()

        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".dex")) {
                        zip.getInputStream(entry).use { input ->
                            val bytes = input.readBytes()
                            val info = parseDexBasic(bytes)
                            totalClasses += 1 // We aren't parsing class_defs count yet, just file count + basic
                            allStrings.addAll(info.extractedStrings)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return DexInfo(totalClasses, allStrings.size, 0, allStrings)
    }

    private fun parseDexBasic(data: ByteArray): DexInfo {
        // Basic DEX Header Parser to extract Strings
        // https://source.android.com/devices/tech/dalvik/dex-format

        val strings = mutableListOf<String>()
        if (data.size < 112) return DexInfo(0,0,0, emptyList())

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Magic verification (dex\n035\0) - skipped for robustness

        buffer.position(56) // string_ids_size offset
        val stringIdsSize = buffer.int
        val stringIdsOff = buffer.int

        // Limit for performance / UI display
        val limit = minOf(stringIdsSize, 200)

        // Read String Offsets
        val stringOffsets = IntArray(limit)
        buffer.position(stringIdsOff)
        for (i in 0 until limit) {
            stringOffsets[i] = buffer.int
        }

        // Read Strings
        for (offset in stringOffsets) {
            if (offset >= data.size) continue
            buffer.position(offset)

            // String data: uleb128 len + MUTF-8
            // Simplified: read byte until 0
            val len = readUleb128(buffer) // skip length
            val sb = StringBuilder()
            var c: Byte
            while (buffer.hasRemaining()) {
                c = buffer.get()
                if (c.toInt() == 0) break
                sb.append(c.toInt().toChar()) // Simplified ASCII/UTF8 handling
            }
            val s = sb.toString()
            if (s.isNotEmpty() && isInterestingString(s)) {
                strings.add(s)
            }
        }

        return DexInfo(0, stringIdsSize, 0, strings)
    }

    // Read unsigned LEB128
    private fun readUleb128(buffer: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buffer.get().toInt()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    private fun isInterestingString(s: String): Boolean {
        // Filter out common noise
        return s.length > 4 && (
            s.startsWith("http") ||
            s.contains("://") ||
            s.contains("key") ||
            s.contains("secret") ||
            s.contains("password") ||
            s.contains(".com")
        )
    }
}
