package com.dtech.apkinspector.data

/**
 * A lightweight helper to decode Binary XML.
 * Note: A full encoder is complex. We will stick to the existing "Edit Strings" capability for deep edits,
 * but this file provides the parsing logic used elsewhere.
 * For full AXML editing, we would typically use a library like AXMLPrinter2 + Encoder, but for this single-file
 * offline constraint (which we are now moving past, but still want code simplicity), we keep the custom parser
 * but expose the "Strings" for editing which is 90% of use cases.
 */
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryXmlParser(private val data: ByteArray) {

    private val CHUNK_STRING_POOL = 0x0001
    private val CHUNK_XML_START_ELEMENT = 0x0102

    var isValid: Boolean = false
        private set

    private var strings = mutableListOf<String>()
    private val otherChunks = mutableListOf<ByteArray>()
    private var originalHeader: ByteArray = ByteArray(0)

    data class Attribute(val name: String, val value: String?, val type: Int, val data: Int, val nameIdx: Int, val valueIdx: Int)

    init {
        try {
            parse()
            isValid = true
        } catch (e: Exception) {
            e.printStackTrace()
            strings.clear()
            otherChunks.clear()
            isValid = false
        }
    }

    private fun parse() {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
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

            if (chunkTotalSize < 8 || chunkTotalSize > buffer.remaining() + 8) break

            if (chunkType == CHUNK_STRING_POOL) {
                parseStringPool(buffer, chunkStart, chunkHeaderSize, chunkTotalSize)
            } else {
                val chunkBytes = ByteArray(chunkTotalSize)
                buffer.position(chunkStart)
                buffer.get(chunkBytes)
                otherChunks.add(chunkBytes)
            }
            buffer.position(chunkStart + chunkTotalSize)
        }
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkStart: Int, headerSize: Int, totalSize: Int) {
        buffer.position(chunkStart + 8)
        val stringCount = buffer.getInt()
        val styleCount = buffer.getInt()
        val flags = buffer.getInt()
        val stringsStart = buffer.getInt()
        val stylesStart = buffer.getInt()

        val absStringsStart = chunkStart + stringsStart
        if (stringCount < 0 || stringCount > 1000000) return

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) offsets[i] = buffer.getInt()

        val isUtf8 = (flags and 0x100) != 0

        for (i in 0 until stringCount) {
            val offset = offsets[i]
            val stringPos = absStringsStart + offset
            if (stringPos >= buffer.limit()) { strings.add(""); continue }
            buffer.position(stringPos)

            if (isUtf8) {
                var charLen = buffer.get().toInt() and 0xFF
                if ((charLen and 0x80) != 0) buffer.get()
                var byteLen = buffer.get().toInt() and 0xFF
                if ((byteLen and 0x80) != 0) {
                    val next = buffer.get().toInt() and 0xFF
                    byteLen = ((byteLen and 0x7F) shl 8) or next
                }
                if (buffer.position() + byteLen > buffer.limit()) { strings.add(""); continue }
                val charBytes = ByteArray(byteLen)
                buffer.get(charBytes)
                strings.add(String(charBytes, Charsets.UTF_8))
            } else {
                var len = buffer.getShort().toInt() and 0xFFFF
                if ((len and 0x8000) != 0) {
                    val low = buffer.getShort().toInt() and 0xFFFF
                    len = ((len and 0x7FFF) shl 16) or low
                }
                val byteLen = len * 2
                if (buffer.position() + byteLen > buffer.limit()) { strings.add(""); continue }
                val charBytes = ByteArray(byteLen)
                buffer.get(charBytes)
                strings.add(String(charBytes, Charsets.UTF_16LE))
            }
        }
    }

    fun getStrings(): List<String> = strings

    fun setString(index: Int, newValue: String) {
        if (!isValid) return
        if (index in strings.indices) {
            strings[index] = newValue
        }
    }

    fun traverse(onStartElement: (name: String, attrs: List<Attribute>) -> Unit) {
        if (!isValid) return
        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(8)
            while (buffer.hasRemaining()) {
                val chunkStart = buffer.position()
                if (buffer.remaining() < 8) break
                val chunkType = buffer.getShort().toInt() and 0xFFFF
                val headerSize = buffer.getShort().toInt() and 0xFFFF
                val size = buffer.getInt()
                if (size < 8 || size > buffer.remaining() + 8) break

                if (chunkType == CHUNK_XML_START_ELEMENT) {
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun rebuild(): ByteArray {
        if (!isValid) return data
        try {
            val output = ByteArrayOutputStream()
            // Rebuild String Pool
            val stringBytes = strings.map { it.toByteArray(Charsets.UTF_16LE) }
            val offsets = IntArray(strings.size)
            var currentOffset = 0
            for (i in strings.indices) {
                offsets[i] = currentOffset
                currentOffset += 2 + stringBytes[i].size + 2
            }
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
            spHeader.putInt(0)
            spHeader.putInt(0) // styles
            spHeader.putInt(0x0000) // flags = 0 for UTF-16
            spHeader.putInt(28 + offsetsSize)
            spHeader.putInt(0)
            for (off in offsets) spHeader.putInt(off)

            val chunksBuffer = ByteArrayOutputStream()
            chunksBuffer.write(spHeader.array())
            val strDataBuffer = ByteBuffer.allocate(stringsBlockSize + stringsBlockPadding).order(ByteOrder.LITTLE_ENDIAN)
            for (b in stringBytes) {
                strDataBuffer.putShort((b.size / 2).toShort())
                strDataBuffer.put(b)
                strDataBuffer.putShort(0)
            }
            chunksBuffer.write(strDataBuffer.array())
            for (c in otherChunks) chunksBuffer.write(c)
            val allChunks = chunksBuffer.toByteArray()
            val fileHeader = ByteBuffer.wrap(originalHeader.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
            fileHeader.putInt(4, 8 + allChunks.size)
            val finalOutput = ByteArrayOutputStream()
            finalOutput.write(fileHeader.array())
            finalOutput.write(allChunks)
            return finalOutput.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            return data
        }
    }

    fun decode(): String {
        if (!isValid) return "Error: XML Parser failed"
        val sb = StringBuilder()
        traverse { name, attrs ->
            sb.append("<$name")
            attrs.forEach { a ->
                if (a.value != null) {
                    sb.append(" ${a.name}=\"${a.value}\"")
                } else {
                    sb.append(" ${a.name}=\"@0x${Integer.toHexString(a.data)}\"")
                }
            }
            sb.append(">\n")
        }
        return sb.toString()
    }
}
