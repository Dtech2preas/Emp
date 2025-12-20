package com.dtech.apkinspector.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses and Edits Android Binary XML (AXML).
 * Supports structural editing (removing permissions) and string modification.
 */
class BinaryXmlParser(private val data: ByteArray) {

    private val CHUNK_STRING_POOL = 0x0001
    private val CHUNK_XML_START_ELEMENT = 0x0102
    private val CHUNK_XML_END_ELEMENT = 0x0103

    // State
    private var strings = mutableListOf<String>()
    // We store chunks to allow reordering/removal
    private val chunks = mutableListOf<Chunk>()
    private var originalHeader: ByteArray = ByteArray(0)

    abstract class Chunk(val type: Int, val headerSize: Int, val totalSize: Int) {
        abstract fun toBytes(): ByteArray
    }

    class GenericChunk(type: Int, headerSize: Int, totalSize: Int, val rawData: ByteArray) : Chunk(type, headerSize, totalSize) {
        override fun toBytes() = rawData
    }

    class StringPoolChunk(type: Int, headerSize: Int, totalSize: Int, val rawData: ByteArray, val strings: MutableList<String>) : Chunk(type, headerSize, totalSize) {
        override fun toBytes(): ByteArray {
            // Rebuild String Pool from mutable strings list
            // This is complex, so we delegate to the Parser's rebuild logic or implement here.
            // For simplicity, we will let the Parser.rebuild() handle the String Pool reconstruction centrally
            // and this chunk acts as a placeholder if we were preserving layout.
            // But since we modify strings, we always rebuild the pool.
            return ByteArray(0) // Should be handled by rebuild()
        }
    }

    class StartTagChunk(
        type: Int, headerSize: Int, totalSize: Int,
        val rawData: ByteArray,
        val nameIdx: Int,
        val attrs: List<Attribute>
    ) : Chunk(type, headerSize, totalSize) {
        override fun toBytes() = rawData
    }

    class EndTagChunk(
        type: Int, headerSize: Int, totalSize: Int,
        val rawData: ByteArray,
        val nameIdx: Int
    ) : Chunk(type, headerSize, totalSize) {
        override fun toBytes() = rawData
    }

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

            val chunkBytes = ByteArray(chunkTotalSize)
            buffer.position(chunkStart)
            buffer.get(chunkBytes)

            // Analyze specific chunks
            val chunkBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

            if (chunkType == CHUNK_STRING_POOL) {
                parseStringPool(chunkBuffer, 0, chunkHeaderSize, chunkTotalSize)
                chunks.add(StringPoolChunk(chunkType, chunkHeaderSize, chunkTotalSize, chunkBytes, strings))
            } else if (chunkType == CHUNK_XML_START_ELEMENT) {
                chunkBuffer.position(8) // Skip header
                val nsIdx = chunkBuffer.getInt()
                val nameIdx = chunkBuffer.getInt()
                val attrStart = chunkBuffer.getShort().toInt() and 0xFFFF
                val attrSize = chunkBuffer.getShort().toInt() and 0xFFFF
                val attrCount = chunkBuffer.getShort().toInt() and 0xFFFF

                val attributes = mutableListOf<Attribute>()
                var attrOffset = attrStart // relative to chunk start? No, relative to chunk start
                // Actually attrStart is usually 20 bytes (header size).

                for (i in 0 until attrCount) {
                     chunkBuffer.position(attrOffset)
                     val aNs = chunkBuffer.getInt()
                     val aName = chunkBuffer.getInt()
                     val aVal = chunkBuffer.getInt()
                     val aTypedValueHeader = chunkBuffer.getInt()
                     val aData = chunkBuffer.getInt()

                     val aType = (aTypedValueHeader shr 24) and 0xFF

                     val attrName = if (aName >= 0 && aName < strings.size) strings[aName] else ""
                     val attrValue = if (aVal >= 0 && aVal < strings.size) strings[aVal] else null

                     attributes.add(Attribute(attrName, attrValue, aType, aData, aName, aVal))
                     attrOffset += attrSize
                }
                chunks.add(StartTagChunk(chunkType, chunkHeaderSize, chunkTotalSize, chunkBytes, nameIdx, attributes))
            } else if (chunkType == CHUNK_XML_END_ELEMENT) {
                chunkBuffer.position(8)
                val nsIdx = chunkBuffer.getInt()
                val nameIdx = chunkBuffer.getInt()
                chunks.add(EndTagChunk(chunkType, chunkHeaderSize, chunkTotalSize, chunkBytes, nameIdx))
            } else {
                chunks.add(GenericChunk(chunkType, chunkHeaderSize, chunkTotalSize, chunkBytes))
            }

            buffer.position(chunkStart + chunkTotalSize)
        }
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkStart: Int, headerSize: Int, totalSize: Int) {
        buffer.position(chunkStart + 8) // Skip chunk header
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
        for (i in 0 until stringCount) {
            buffer.position(absStringsStart + offsets[i])
            val len = buffer.getShort().toInt() and 0xFFFF
            val charBytes = ByteArray(len * 2)
            buffer.get(charBytes)
            val str = String(charBytes, Charsets.UTF_16LE)
            strings.add(str)
        }
    }

    // --- API ---

    fun getStrings(): List<String> = strings

    fun setString(index: Int, newValue: String) {
        if (index in strings.indices) {
            strings[index] = newValue
        }
    }

    fun getPermissions(): List<String> {
        val perms = mutableListOf<String>()
        chunks.forEach { chunk ->
            if (chunk is StartTagChunk) {
                val tagName = if (chunk.nameIdx in strings.indices) strings[chunk.nameIdx] else ""
                if (tagName == "uses-permission") {
                    // Find android:name attribute.
                    // Attributes store nameIdx.
                    // To be safe, we check all attributes and look for one with "name"
                    val nameAttr = chunk.attrs.find {
                        val aName = if (it.nameIdx in strings.indices) strings[it.nameIdx] else ""
                        aName == "name"
                    }
                    if (nameAttr != null) {
                         val permValue = nameAttr.value
                         if (permValue != null) perms.add(permValue)
                    }
                }
            }
        }
        return perms
    }

    fun removePermission(permission: String) {
        val iterator = chunks.iterator()
        while (iterator.hasNext()) {
            val chunk = iterator.next()
            if (chunk is StartTagChunk) {
                val tagName = if (chunk.nameIdx in strings.indices) strings[chunk.nameIdx] else ""
                if (tagName == "uses-permission") {
                    val nameAttr = chunk.attrs.find {
                        val aName = if (it.nameIdx in strings.indices) strings[it.nameIdx] else ""
                        aName == "name"
                    }
                    if (nameAttr?.value == permission) {
                        iterator.remove()
                        // Also remove the next EndTagChunk for this permission
                        // A uses-permission tag is usually immediately followed by EndTag
                        // We need to find the matching end tag.
                        // Since uses-permission is empty, the next chunk should be EndTag
                        // BUT careful if there are others.
                        // We will remove the *next* chunk if it is an EndTag with same name.
                        // However, iterator.next() advances.
                        // Let's iterate manually or handle it.
                        // Simplification: We just removed the start tag. We need to remove the end tag.
                        // Since we are inside iterator, we can't easily peek/remove next without care.
                        // We'll mark for removal or do a second pass?
                        // Actually, let's just break and handle removal in a safe way (e.g. collecting indices).
                    }
                }
            }
        }

        // Proper removal implementation
        val toRemove = mutableListOf<Chunk>()
        var foundStart = false
        var targetNameIdx = -1

        for (chunk in chunks) {
            if (chunk is StartTagChunk) {
                val tagName = if (chunk.nameIdx in strings.indices) strings[chunk.nameIdx] else ""
                if (tagName == "uses-permission") {
                     val nameAttr = chunk.attrs.find {
                        val aName = if (it.nameIdx in strings.indices) strings[it.nameIdx] else ""
                        aName == "name"
                    }
                    if (nameAttr?.value == permission) {
                        toRemove.add(chunk)
                        foundStart = true
                        targetNameIdx = chunk.nameIdx
                    }
                }
            } else if (chunk is EndTagChunk && foundStart) {
                if (chunk.nameIdx == targetNameIdx) {
                    toRemove.add(chunk)
                    foundStart = false // Reset
                }
            }
        }

        chunks.removeAll(toRemove)
    }

    fun traverse(onStartElement: (name: String, attrs: List<Attribute>) -> Unit) {
         chunks.forEach { chunk ->
             if (chunk is StartTagChunk) {
                 val tagName = if (chunk.nameIdx in strings.indices) strings[chunk.nameIdx] else ""
                 onStartElement(tagName, chunk.attrs)
             }
         }
    }

    fun rebuild(): ByteArray {
        val output = ByteArrayOutputStream()

        // 1. Rebuild String Pool
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
        val spTotalSize = headerSize + offsetsSize + stringsBlockSize + stringsBlockPadding

        val spHeader = ByteBuffer.allocate(headerSize + offsetsSize).order(ByteOrder.LITTLE_ENDIAN)
        spHeader.putShort(CHUNK_STRING_POOL.toShort())
        spHeader.putShort(28)
        spHeader.putInt(spTotalSize)
        spHeader.putInt(strings.size)
        spHeader.putInt(0) // styles
        spHeader.putInt(0) // flags (UTF-16)
        spHeader.putInt(28 + offsetsSize) // strings start
        spHeader.putInt(0) // styles start

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

        // 2. Write Other Chunks
        for (chunk in chunks) {
            if (chunk is StringPoolChunk) continue // We just wrote the new String Pool

            // Note: StartTagChunk's rawData contains attributes which point to String Pool indices.
            // If we modified the String Pool order, we would need to update ALL indices in ALL chunks.
            // BUT: We only MODIFY existing strings in place (via setString). We do NOT add/remove strings,
            // or if we do, we must update indices.
            // In this implementation, we assume we only MODIFY strings (same index) or REMOVE chunks.
            // Removing chunks does not invalidate indices.
            // So we can reuse rawData for chunks.

            chunksBuffer.write(chunk.toBytes())
        }

        val allChunks = chunksBuffer.toByteArray()

        // 3. Main Header
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
