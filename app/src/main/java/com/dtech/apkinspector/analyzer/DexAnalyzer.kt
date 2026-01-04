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
                            val info = analyzeDexBytes(bytes)
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

    fun analyzeDexBytes(data: ByteArray): DexInfo {
        // Basic DEX Header Parser to extract Strings and Classes
        // https://source.android.com/devices/tech/dalvik/dex-format

        val strings = mutableListOf<String>()
        val classNames = mutableListOf<String>()

        if (data.size < 112) return DexInfo(0,0,0, emptyList())

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        buffer.position(56) // string_ids_size offset
        val stringIdsSize = buffer.int
        val stringIdsOff = buffer.int

        buffer.position(64) // type_ids_size
        val typeIdsSize = buffer.int
        val typeIdsOff = buffer.int

        buffer.position(96) // class_defs_size
        val classDefsSize = buffer.int
        val classDefsOff = buffer.int

        // Helper to get string by index
        fun getString(idx: Int): String {
            if (idx < 0 || idx >= stringIdsSize) return ""
            val offPos = stringIdsOff + (idx * 4)
            if (offPos >= data.size) return ""
            val strOff = buffer.getInt(offPos)
            if (strOff >= data.size) return ""

            // Read string at offset
            val savedPos = buffer.position()
            buffer.position(strOff)
            readUleb128(buffer) // skip len
            val sb = StringBuilder()
            while(buffer.hasRemaining()) {
                val b = buffer.get()
                if (b.toInt() == 0) break
                sb.append(b.toInt().toChar())
            }
            buffer.position(savedPos)
            return sb.toString()
        }

        // Helper to get Type Descriptor by type index
        fun getTypeDesc(typeIdx: Int): String {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return ""
            val offPos = typeIdsOff + (typeIdx * 4)
             if (offPos >= data.size) return ""
            val strIdx = buffer.getInt(offPos)
            return getString(strIdx)
        }

        // Parse Class Defs
        // Limit to 500 for UI performance
        val limit = minOf(classDefsSize, 500)
        for (i in 0 until limit) {
             val defOff = classDefsOff + (i * 32)
             if (defOff + 32 > data.size) break

             val classIdx = buffer.getInt(defOff)
             val className = getTypeDesc(classIdx)
             if (className.isNotEmpty()) {
                 classNames.add(className)
             }
        }

        // If no classes found (stripped?), just dump interesting strings
        if (classNames.isEmpty()) {
            val strLimit = minOf(stringIdsSize, 500)
            for (i in 0 until strLimit) {
                val s = getString(i)
                if (isInterestingString(s)) strings.add(s)
            }
        } else {
             strings.addAll(classNames)
        }

        return DexInfo(classDefsSize, stringIdsSize, 0, strings)
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

    // Write unsigned LEB128 (Simplified for our known constraints)
    private fun writeUleb128(buffer: ByteBuffer, value: Int) {
        var v = value
        while (v ushr 7 != 0) {
            buffer.put(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        buffer.put((v and 0x7F).toByte())
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

    /**
     * Simple DEX String Replacement.
     * Searches for exact string matches in the String Pool and replaces them.
     * Limitations: New string length must be <= original length to avoid shifting offsets (which breaks the DEX).
     * If shorter, we pad with nulls.
     */
    fun replaceStrings(data: ByteArray, replacements: Map<String, String>): ByteArray {
        val newData = data.copyOf()
        val buffer = ByteBuffer.wrap(newData).order(ByteOrder.LITTLE_ENDIAN)

        if (newData.size < 112) return newData

        // Read String IDs Offset
        buffer.position(56)
        val stringIdsSize = buffer.int
        val stringIdsOff = buffer.int

        for (i in 0 until stringIdsSize) {
             val offPos = stringIdsOff + (i * 4)
             if (offPos >= newData.size) break
             val strOff = buffer.getInt(offPos)
             if (strOff >= newData.size) continue

             // Read String
             val savedPos = buffer.position()
             buffer.position(strOff)
             val len = readUleb128(buffer) // Read length
             val startOfChars = buffer.position()

             // Read current string value
             val sb = StringBuilder()
             var currentPos = startOfChars
             while(currentPos < newData.size) {
                 val b = newData[currentPos]
                 if (b.toInt() == 0) break
                 sb.append(b.toInt().toChar())
                 currentPos++
             }
             val currentStr = sb.toString()

             // Check if we have a replacement
             if (replacements.containsKey(currentStr)) {
                 val newStr = replacements[currentStr]!!
                 // Constraint: Must fit
                 if (newStr.length <= currentStr.length) {
                     // Determine byte width of new length vs old length in ULEB128
                     // In practice, since we only allow shortening or equal length,
                     // and max length is constrained by old length,
                     // if we write the new length, we MUST make sure it doesn't take MORE bytes than old length.
                     // But if it takes FEWER bytes, we can't shrink the ULEB field without shifting everything.
                     // So we only update length if ULEB size is identical.
                     // Or, simpler: We only update the CHARS and pad with nulls.
                     // Leaving the old length means "Hi\0\0" is technically a 5-char string "Hi\u0000\u0000".
                     // This is safer for offsets. Android usually treats null as terminator for logic, but internally string is length-based.

                     // HOWEVER, if we want clean editing, we should try to update length IF space permits.
                     // ULEB128: 0-127 = 1 byte. 128-16383 = 2 bytes.
                     // If oldLen < 128 and newLen < 128, both use 1 byte. We can update length safely.

                     val oldLenSize = if(len < 128) 1 else if(len < 16384) 2 else 3 // Simplified check
                     val newLenSize = if(newStr.length < 128) 1 else if(newStr.length < 16384) 2 else 3

                     if (oldLenSize == newLenSize) {
                         // We can update the length in place!
                         buffer.position(strOff)
                         // Write new length
                         writeUleb128(buffer, newStr.length)

                         // Write chars
                         for (k in newStr.indices) {
                             newData[buffer.position() + k] = newStr[k].code.toByte()
                         }
                         // Pad remaining with nulls
                         for (k in newStr.length until currentStr.length) {
                             newData[buffer.position() + k] = 0
                         }
                     } else {
                         // Fallback: Just overwrite chars, keep old length (string will have trailing nulls)
                         for (k in newStr.indices) {
                             newData[startOfChars + k] = newStr[k].code.toByte()
                         }
                         for (k in newStr.length until currentStr.length) {
                             newData[startOfChars + k] = 0
                         }
                     }
                 }
             }
             buffer.position(savedPos)
        }
        return newData
    }
}
