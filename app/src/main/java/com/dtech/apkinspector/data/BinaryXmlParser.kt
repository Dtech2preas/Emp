package com.dtech.apkinspector.data

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryXmlParser {

    fun decode(data: ByteArray): String {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val sb = StringBuilder()

        try {
            // Check header
            val type = buffer.getShort().toInt() and 0xFFFF
            if (type != 0x0003) { // RES_XML_TYPE
                return "Invalid XML Header"
            }
            val headerSize = buffer.getShort().toInt() and 0xFFFF
            val chunkSize = buffer.getInt()

            // Move to next chunk
            // Expected: String Pool

            // We need a list of strings to map indices to names
            val stringPool = mutableListOf<String>()

            while (buffer.hasRemaining()) {
                val chunkStart = buffer.position()
                val chunkType = buffer.getShort().toInt() and 0xFFFF
                val chunkHeaderSize = buffer.getShort().toInt() and 0xFFFF
                val chunkTotalSize = buffer.getInt()

                if (chunkType == 0x0001) { // RES_STRING_POOL_TYPE
                    parseStringPool(buffer, chunkStart, chunkHeaderSize, stringPool)
                } else if (chunkType == 0x0180) { // RES_XML_RESOURCE_MAP_TYPE
                    // Skip resource map
                } else if (chunkType == 0x0100) { // RES_XML_START_NAMESPACE_TYPE
                    // Namespace start (prefix, uri)
                } else if (chunkType == 0x0102) { // RES_XML_START_ELEMENT_TYPE
                    parseStartElement(buffer, chunkStart, chunkHeaderSize, stringPool, sb)
                } else if (chunkType == 0x0103) { // RES_XML_END_ELEMENT_TYPE
                    parseEndElement(buffer, chunkStart, stringPool, sb)
                } else if (chunkType == 0x0104) { // RES_XML_CDATA_TYPE
                    // CDATA
                }

                // Ensure we advance to next chunk
                buffer.position(chunkStart + chunkTotalSize)
            }

            return sb.toString()
        } catch (e: Exception) {
            return "Error decoding XML: ${e.message}\n${sb.toString()}"
        }
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkStart: Int, headerSize: Int, pool: MutableList<String>) {
        val stringCount = buffer.getInt()
        val styleCount = buffer.getInt()
        val flags = buffer.getInt()
        val stringsStart = buffer.getInt() + chunkStart
        val stylesStart = buffer.getInt() + chunkStart

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buffer.getInt()
        }

        // Read strings
        for (i in 0 until stringCount) {
            val stringOffset = stringsStart + offsets[i]
            buffer.position(stringOffset)

            // Standard AXML string:
            // 2 bytes length (or more if flag is set), then utf-16/utf-8 bytes, then null
            // Simplified reading for now (assuming standard UTF-16 w/o huge strings)
            val len = buffer.getShort().toInt() and 0xFFFF
            val charBytes = ByteArray(len * 2)
            buffer.get(charBytes)
            val str = String(charBytes, Charsets.UTF_16LE)
            pool.add(str)
        }
    }

    private fun parseStartElement(buffer: ByteBuffer, chunkStart: Int, headerSize: Int, pool: List<String>, sb: StringBuilder) {
        val nsIdx = buffer.getInt()
        val nameIdx = buffer.getInt()
        val attrStart = buffer.getShort().toInt() and 0xFFFF // usually 20
        val attrSize = buffer.getShort().toInt() and 0xFFFF // usually 20
        val attrCount = buffer.getShort().toInt() and 0xFFFF
        val idIndex = buffer.getShort().toInt() and 0xFFFF
        val classIndex = buffer.getShort().toInt() and 0xFFFF
        val styleIndex = buffer.getShort().toInt() and 0xFFFF

        val tagName = if (nameIdx >= 0 && nameIdx < pool.size) pool[nameIdx] else "unknown"

        sb.append("<$tagName")

        // Attributes
        for (i in 0 until attrCount) {
            // Each attribute is 20 bytes (usually)
            // ns(4), name(4), valString(4), type(4), data(4)
            val attrNsIdx = buffer.getInt()
            val attrNameIdx = buffer.getInt()
            val attrValIdx = buffer.getInt()
            val attrType = buffer.getInt() shr 24 // high byte is type
            val attrData = buffer.getInt()

            val attrName = if (attrNameIdx >= 0 && attrNameIdx < pool.size) pool[attrNameIdx] else "attr$attrNameIdx"
            val attrValue = if (attrValIdx >= 0 && attrValIdx < pool.size) pool[attrValIdx] else attrData.toString()

            sb.append(" $attrName=\"$attrValue\"")
        }
        sb.append(">\n")
    }

    private fun parseEndElement(buffer: ByteBuffer, chunkStart: Int, pool: List<String>, sb: StringBuilder) {
        val nsIdx = buffer.getInt()
        val nameIdx = buffer.getInt()
        val tagName = if (nameIdx >= 0 && nameIdx < pool.size) pool[nameIdx] else "unknown"
        sb.append("</$tagName>\n")
    }
}
