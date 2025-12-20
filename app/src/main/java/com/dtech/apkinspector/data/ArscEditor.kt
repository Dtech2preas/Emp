package com.dtech.apkinspector.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream

/**
 * Editor for resources.arsc.
 * Allows editing Global Strings (App Name) and Color Values.
 */
class ArscEditor(private val data: ByteArray) {

    private val RES_STRING_POOL_TYPE = 0x0001
    private val RES_TABLE_TYPE = 0x0002
    private val RES_TABLE_PACKAGE_TYPE = 0x0200
    private val RES_TABLE_TYPE_TYPE = 0x0201
    private val RES_TABLE_TYPE_SPEC_TYPE = 0x0202

    private val TYPE_INT_COLOR_ARGB8 = 0x1C
    private val TYPE_INT_COLOR_RGB8 = 0x1D
    private val TYPE_INT_COLOR_ARGB4 = 0x1E
    private val TYPE_INT_COLOR_RGB4 = 0x1F

    private var globalStrings = mutableListOf<String>()
    private var globalStringPoolFlags = 0
    private var originalHeader: ByteArray = ByteArray(12) // Table Header
    private val packageChunks = mutableListOf<ByteArray>()

    // Helpers to decode/encode keys
    private val packageKeyPools = mutableMapOf<Int, List<String>>() // PkgIndex -> Keys

    init {
        parse()
    }

    private fun parse() {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val type = buffer.getShort().toInt() and 0xFFFF
        val headerSize = buffer.getShort().toInt() and 0xFFFF
        val fileSize = buffer.getInt()
        val packageCount = buffer.getInt()

        originalHeader = data.copyOfRange(0, 12)

        while (buffer.hasRemaining()) {
            val chunkStart = buffer.position()
            if (buffer.remaining() < 8) break

            val chunkType = buffer.getShort().toInt() and 0xFFFF
            val chunkHeaderSize = buffer.getShort().toInt() and 0xFFFF
            val chunkTotalSize = buffer.getInt()

            if (chunkType == RES_STRING_POOL_TYPE && globalStrings.isEmpty()) {
                // First String Pool is Global
                parseStringPool(buffer, chunkStart, chunkHeaderSize, chunkTotalSize, true)
            } else if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                val chunkBytes = ByteArray(chunkTotalSize)
                buffer.position(chunkStart)
                buffer.get(chunkBytes)
                packageChunks.add(chunkBytes)

                // Parse Key Pool inside Package to help with lookups
                parsePackageKeys(packageChunks.size - 1, chunkBytes)
            } else {
                // Other chunks (shouldn't be many at top level)
                buffer.position(chunkStart + chunkTotalSize)
            }
        }
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkStart: Int, headerSize: Int, totalSize: Int, isGlobal: Boolean): List<String> {
        buffer.position(chunkStart + 8)
        val stringCount = buffer.getInt()
        val styleCount = buffer.getInt()
        val flags = buffer.getInt()
        val stringsStart = buffer.getInt()

        if (isGlobal) globalStringPoolFlags = flags // Preserve original flags

        val strings = mutableListOf<String>()
        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) offsets[i] = buffer.getInt()

        val absStringsStart = chunkStart + stringsStart
        val isUtf8 = (flags and 0x100) != 0

        for (i in 0 until stringCount) {
            buffer.position(absStringsStart + offsets[i])
            strings.add(readString(buffer, isUtf8))
        }

        if (isGlobal) globalStrings.addAll(strings)

        buffer.position(chunkStart + totalSize)
        return strings
    }

    private fun readString(buffer: ByteBuffer, isUtf8: Boolean): String {
        if (isUtf8) {
            // Simplified UTF-8 length decoding
            var len = buffer.get().toInt() and 0xFF
            if ((len and 0x80) != 0) buffer.get() // Skip second byte

            len = buffer.get().toInt() and 0xFF
            if ((len and 0x80) != 0) {
                 val next = buffer.get().toInt() and 0xFF
                 len = ((len and 0x7F) shl 8) or next
            }

            val bytes = ByteArray(len)
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        } else {
            var len = buffer.getShort().toInt() and 0xFFFF
            if ((len and 0x8000) != 0) buffer.getShort() // High char
            val bytes = ByteArray(len * 2)
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_16LE)
        }
    }

    private fun parsePackageKeys(pkgIndex: Int, chunk: ByteArray) {
        val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        // Package Chunk Header
        // type(2), header(2), size(4), id(4), name(256), typeStrings(4), lastPublicType(4), keyStrings(4), lastPublicKey(4)
        val keyStringsOffset = buffer.getInt(280) // 2+2+4+4+256 + 4+4 = 276. Offset of keyStrings
        if (keyStringsOffset == 0) return

        val chunkStart = 0 // Relative to chunk
        // Key Strings is a String Pool at offset
        buffer.position(keyStringsOffset)
        val spType = buffer.getShort().toInt() and 0xFFFF
        val spHeader = buffer.getShort().toInt() and 0xFFFF
        val spSize = buffer.getInt()

        val keys = parseStringPool(buffer, keyStringsOffset, spHeader, spSize, false)
        packageKeyPools[pkgIndex] = keys
    }

    // API

    fun getResourceString(resId: Int): String? {
        val value = resolveResourceValue(resId) ?: return null
        if (value.dataType == 0x03) { // TYPE_STRING
            if (value.data in globalStrings.indices) {
                return globalStrings[value.data]
            }
        }
        return null
    }

    fun updateResourceString(resId: Int, newValue: String) {
        val value = resolveResourceValue(resId) ?: return
        if (value.dataType == 0x03 && value.data in globalStrings.indices) {
            globalStrings[value.data] = newValue
        }
    }

    fun getColors(): Map<String, Int> {
        val colors = mutableMapOf<String, Int>()
        packageChunks.forEachIndexed { pkgIdx, chunk ->
            val keys = packageKeyPools[pkgIdx] ?: return@forEachIndexed
            val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)

            var pos = buffer.getShort(2).toInt() // header size of package
            while (pos < chunk.size) {
                buffer.position(pos)
                val type = buffer.getShort().toInt() and 0xFFFF
                val headerSize = buffer.getShort().toInt() and 0xFFFF
                val size = buffer.getInt()

                if (type == RES_TABLE_TYPE_TYPE) {
                    val entryCount = buffer.getInt(pos + 12)
                    val entriesStart = buffer.getInt(pos + 16)
                    val absEntriesStart = pos + entriesStart

                    for (i in 0 until entryCount) {
                        val offset = buffer.getInt(pos + 20 + i * 4)
                        if (offset != -1) {
                            val entryPos = absEntriesStart + offset
                            buffer.position(entryPos)
                            val entrySize = buffer.getShort().toInt()
                            val flags = buffer.getShort().toInt()
                            val keyIdx = buffer.getInt()

                            if ((flags and 0x01) == 0) {
                                val dataType = buffer.get(entryPos + 8 + 3).toInt()
                                val data = buffer.getInt(entryPos + 8 + 4)

                                if (dataType >= TYPE_INT_COLOR_ARGB8 && dataType <= TYPE_INT_COLOR_RGB4) {
                                    val name = if (keyIdx in keys.indices) keys[keyIdx] else "color_$keyIdx"
                                    colors[name] = data
                                }
                            }
                        }
                    }
                }
                pos += size
            }
        }
        return colors
    }

    fun updateColor(name: String, newColor: Int) {
        packageChunks.forEachIndexed { pkgIdx, chunk ->
            val keys = packageKeyPools[pkgIdx] ?: return@forEachIndexed
            val keyIdx = keys.indexOf(name)
            if (keyIdx == -1) return@forEachIndexed

            val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            var pos = buffer.getShort(2).toInt()
            while (pos < chunk.size) {
                buffer.position(pos)
                val type = buffer.getShort().toInt() and 0xFFFF
                val size = buffer.getInt(pos + 4)

                if (type == RES_TABLE_TYPE_TYPE) {
                    val entryCount = buffer.getInt(pos + 12)
                    val entriesStart = buffer.getInt(pos + 16)
                    val absEntriesStart = pos + entriesStart

                    for (i in 0 until entryCount) {
                        val offset = buffer.getInt(pos + 20 + i * 4)
                        if (offset != -1) {
                            val entryPos = absEntriesStart + offset
                            val entryKey = buffer.getInt(entryPos + 4)
                            if (entryKey == keyIdx) {
                                val flags = buffer.getShort(entryPos + 2).toInt()
                                if ((flags and 0x01) == 0) {
                                    val dataType = buffer.get(entryPos + 11).toInt()
                                    if (dataType >= TYPE_INT_COLOR_ARGB8 && dataType <= TYPE_INT_COLOR_RGB4) {
                                        buffer.putInt(entryPos + 12, newColor)
                                        buffer.put(entryPos + 11, TYPE_INT_COLOR_ARGB8.toByte())
                                    }
                                }
                            }
                        }
                    }
                }
                pos += size
            }
        }
    }

    data class ResValue(val dataType: Int, val data: Int)

    private fun resolveResourceValue(resId: Int): ResValue? {
        val pkgId = (resId shr 24) and 0xFF
        val typeId = (resId shr 16) and 0xFF
        val entryId = resId and 0xFFFF

        packageChunks.forEach { chunk ->
            val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            val id = buffer.getInt(4)
            if (id == pkgId || pkgId == 127) {
                 var pos = buffer.getShort(2).toInt()
                 while (pos < chunk.size) {
                     val type = buffer.getShort(pos).toInt() and 0xFFFF
                     val size = buffer.getInt(pos + 4)
                     if (type == RES_TABLE_TYPE_TYPE) {
                         val tId = buffer.get(pos + 1).toInt() and 0xFF
                         if (tId == typeId) {
                             val entryCount = buffer.getInt(pos + 12)
                             if (entryId < entryCount) {
                                 val offset = buffer.getInt(pos + 20 + entryId * 4)
                                 if (offset != -1) {
                                     val entryPos = pos + buffer.getInt(pos + 16) + offset
                                     val flags = buffer.getShort(entryPos + 2).toInt()
                                     if ((flags and 0x01) == 0) {
                                         val dt = buffer.get(entryPos + 11).toInt()
                                         val d = buffer.getInt(entryPos + 12)
                                         return ResValue(dt, d)
                                     }
                                 }
                             }
                         }
                     }
                     pos += size
                 }
            }
        }
        return null
    }

    fun rebuild(): ByteArray {
        val output = ByteArrayOutputStream()

        // Rebuild Global String Pool
        val stringBytes = globalStrings.map { it.toByteArray(Charsets.UTF_16LE) }
        val offsets = IntArray(globalStrings.size)
        var currentOffset = 0
        for (i in globalStrings.indices) {
            offsets[i] = currentOffset
            currentOffset += 2 + stringBytes[i].size + 2
        }
        val spPadding = if (currentOffset % 4 != 0) 4 - (currentOffset % 4) else 0
        val spSize = 28 + (globalStrings.size * 4) + currentOffset + spPadding

        val spHeader = ByteBuffer.allocate(28 + (globalStrings.size * 4)).order(ByteOrder.LITTLE_ENDIAN)
        spHeader.putShort(RES_STRING_POOL_TYPE.toShort())
        spHeader.putShort(28)
        spHeader.putInt(spSize)
        spHeader.putInt(globalStrings.size)
        spHeader.putInt(0)
        spHeader.putInt(0)
        spHeader.putInt(28 + (globalStrings.size * 4))
        spHeader.putInt(0)
        for (off in offsets) spHeader.putInt(off)

        val spData = ByteBuffer.allocate(currentOffset + spPadding).order(ByteOrder.LITTLE_ENDIAN)
        for (b in stringBytes) {
            spData.putShort((b.size / 2).toShort())
            spData.put(b)
            spData.putShort(0)
        }

        val spChunk = ByteArrayOutputStream()
        spChunk.write(spHeader.array())
        spChunk.write(spData.array())
        val spBytes = spChunk.toByteArray()

        // Total Size
        var totalSize = 12 + spBytes.size
        packageChunks.forEach { totalSize += it.size }

        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_TABLE_TYPE.toShort())
        header.putShort(12)
        header.putInt(totalSize)
        header.putInt(packageChunks.size)

        output.write(header.array())
        output.write(spBytes)
        packageChunks.forEach { output.write(it) }

        return output.toByteArray()
    }
}
