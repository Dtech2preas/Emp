package com.dtech.apkinspector.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses and Edits Android Binary XML (AXML).
 * Supports reading the String Pool, traversing attributes, and rebuilding the file with modified strings.
 */
class BinaryXmlParser(private val data: ByteArray) {

    private val CHUNK_STRING_POOL = 0x0001
    private val CHUNK_XML_START_ELEMENT = 0x0102

    // State
    private var strings = mutableListOf<String>()
    private val otherChunks = mutableListOf<ByteArray>()
    private var originalHeader: ByteArray = ByteArray(0)

    data class Attribute(val name: String, val value: String?, val type: Int, val data: Int, val nameIdx: Int, val valueIdx: Int)

    init {
        parse()
    }

    private fun parse() {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Read AXML Header
        if (buffer.remaining() < 8) return
        val type = buffer.getShort()
        val headerSize = buffer.getShort()
        val fileSize = buffer.getInt()

        originalHeader = data.copyOfRange(0, 8)

        while (buffer.hasRemaining()) {
            val chunkStart = buffer.position()
            if (buffer.remaining() < 8) break

            val chunkType = buffer.getShort().toInt() and 0xFFFF
            val chunkHeaderSize = buffer.getShort().toInt() and 0xFFFF
            val chunkTotalSize = buffer.getInt()

            if (chunkType == CHUNK_STRING_POOL) {
                parseStringPool(buffer, chunkStart, chunkHeaderSize, chunkTotalSize)
            } else {
                val chunkBytes = ByteArray(chunkTotalSize)
                buffer.position(chunkStart)
                buffer.get(chunkBytes)
                otherChunks.add(chunkBytes)
            }
        }
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkStart: Int, headerSize: Int, totalSize: Int) {
        buffer.position(chunkStart)
        // Skip header part (standard string pool header is 28 bytes)
        buffer.position(chunkStart + 8)
        val stringCount = buffer.getInt()
        val styleCount = buffer.getInt()
        val flags = buffer.getInt()
        val stringsStart = buffer.getInt()
        val stylesStart = buffer.getInt()

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buffer.getInt()
        }

        val absStringsStart = chunkStart + stringsStart
        val isUtf8 = (flags and 0x100) != 0

        for (i in 0 until stringCount) {
            buffer.position(absStringsStart + offsets[i])

            if (isUtf8) {
                // Decode UTF-8 Length
                // 1. Character length
                var charLen = buffer.get().toInt() and 0xFF
                if ((charLen and 0x80) != 0) {
                    buffer.get() // Skip 2nd byte
                }

                // 2. Byte length
                var byteLen = buffer.get().toInt() and 0xFF
                if ((byteLen and 0x80) != 0) {
                    val next = buffer.get().toInt() and 0xFF
                    byteLen = ((byteLen and 0x7F) shl 8) or next
                }

                val charBytes = ByteArray(byteLen)
                buffer.get(charBytes)
                val str = String(charBytes, Charsets.UTF_8)
                strings.add(str)
                // Note: UTF-8 strings are null-terminated (1 byte 0x00), but we don't need to read it explicitly
                // as offsets guide the position.
            } else {
                // Decode UTF-16 Length
                // Standard AXML string: length (2 bytes in chars), chars (len*2 bytes), null (2 bytes)
                var len = buffer.getShort().toInt() and 0xFFFF
                // Handle large UTF-16 strings
                if ((len and 0x8000) != 0) {
                     val low = buffer.getShort().toInt() and 0xFFFF
                     len = ((len and 0x7FFF) shl 16) or low
                }

                val charBytes = ByteArray(len * 2)
                buffer.get(charBytes)
                val str = String(charBytes, Charsets.UTF_16LE)
                strings.add(str)
            }
        }
        buffer.position(chunkStart + totalSize)
    }

    fun getStrings(): List<String> = strings

    fun setString(index: Int, newValue: String) {
        if (index in strings.indices) {
            strings[index] = newValue
        }
    }

    fun traverse(onStartElement: (name: String, attrs: List<Attribute>) -> Unit) {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 8) return
        buffer.position(8) // Skip file header

        while (buffer.hasRemaining()) {
            val chunkStart = buffer.position()
            if (buffer.remaining() < 8) break

            val chunkType = buffer.getShort().toInt() and 0xFFFF
            val chunkHeaderSize = buffer.getShort().toInt() and 0xFFFF
            val size = buffer.getInt()

            if (chunkType == CHUNK_XML_START_ELEMENT) {
                // Parse attributes
                buffer.position(chunkStart + 8)
                val nsIdx = buffer.getInt()
                val nameIdx = buffer.getInt()
                val attrStart = buffer.getShort().toInt() and 0xFFFF
                val attrSize = buffer.getShort().toInt() and 0xFFFF
                val attrCount = buffer.getShort().toInt() and 0xFFFF

                val tagName = if (nameIdx >= 0 && nameIdx < strings.size) strings[nameIdx] else ""
                val attributes = mutableListOf<Attribute>()

                var attrOffset = chunkStart + attrStart
                for (i in 0 until attrCount) {
                    buffer.position(attrOffset)
                    val aNs = buffer.getInt()
                    val aName = buffer.getInt()
                    val aVal = buffer.getInt()
                    val aTypedValueHeader = buffer.getInt()
                    val aData = buffer.getInt()

                    val aType = (aTypedValueHeader shr 24) and 0xFF

                    val attrName = if (aName >= 0 && aName < strings.size) strings[aName] else ""
                    val attrValue = if (aVal >= 0 && aVal < strings.size) strings[aVal] else null

                    attributes.add(Attribute(attrName, attrValue, aType, aData, aName, aVal))
                    attrOffset += attrSize
                }
                onStartElement(tagName, attributes)
            }
            buffer.position(chunkStart + size)
        }
    }

    fun rebuild(): ByteArray {
        val output = ByteArrayOutputStream()

        // Rebuild String Pool
        // We always rebuild as UTF-16LE for simplicity and max compatibility with our writer logic
        val stringBytes = strings.map { it.toByteArray(Charsets.UTF_16LE) }
        val offsets = IntArray(strings.size)
        var currentOffset = 0
        for (i in strings.indices) {
            offsets[i] = currentOffset
            // Length (2 bytes) + Data + Null (2 bytes)
            // Note: We are using simple small-string encoding (len < 32768)
            currentOffset += 2 + stringBytes[i].size + 2
        }

        // Align strings block
        val stringsBlockPadding = if (currentOffset % 4 != 0) 4 - (currentOffset % 4) else 0
        val stringsBlockSize = currentOffset

        val headerSize = 28
        val offsetsSize = strings.size * 4
        val totalSize = headerSize + offsetsSize + stringsBlockSize + stringsBlockPadding

        val spHeader = ByteBuffer.allocate(headerSize + offsetsSize).order(ByteOrder.LITTLE_ENDIAN)
        spHeader.putShort(CHUNK_STRING_POOL.toShort())
        spHeader.putShort(28)
        spHeader.putInt(totalSize)
        spHeader.putInt(strings.size)
        spHeader.putInt(0) // styles
        spHeader.putInt(0) // flags (0 = UTF-16)
        spHeader.putInt(28 + offsetsSize) // strings start
        spHeader.putInt(0) // styles start

        for (off in offsets) spHeader.putInt(off)

        // Buffer for chunks (Header + Strings + OtherChunks)
        val chunksBuffer = ByteArrayOutputStream()
        chunksBuffer.write(spHeader.array())

        // Write strings data
        val strDataBuffer = ByteBuffer.allocate(stringsBlockSize + stringsBlockPadding).order(ByteOrder.LITTLE_ENDIAN)
        for (b in stringBytes) {
            // Write length (char count = byte count / 2 for UTF-16)
            strDataBuffer.putShort((b.size / 2).toShort())
            strDataBuffer.put(b)
            strDataBuffer.putShort(0)
        }
        chunksBuffer.write(strDataBuffer.array())

        // Write other chunks
        for (c in otherChunks) chunksBuffer.write(c)

        val allChunks = chunksBuffer.toByteArray()

        // Update File Size in Main Header
        val fileHeader = ByteBuffer.wrap(originalHeader.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        fileHeader.putInt(4, 8 + allChunks.size)

        val finalOutput = ByteArrayOutputStream()
        finalOutput.write(fileHeader.array())
        finalOutput.write(allChunks)
        return finalOutput.toByteArray()
    }

    fun decode(): String {
        val sb = StringBuilder()
        traverse { name, attrs ->
            sb.append("<$name")
            attrs.forEach { a ->
                sb.append(" ${a.name}=\"${a.value ?: a.data}\"")
            }
            sb.append(">\n")
        }
        return sb.toString()
    }
}
