package com.dtech.apkinspector.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryXmlParserTest {

    @Test
    fun `test UTF-8 String Pool Parsing`() {
        // Construct a minimal AXML with a UTF-8 String Pool containing "Test"
        val stringPoolSize = 28 + 4 + 8 // Header(28) + Offsets(4) + Data(aligned to 8)
        val fileSize = 8 + stringPoolSize

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)

        // AXML Header
        buffer.putShort(0x0003) // RES_XML_TYPE
        buffer.putShort(8)      // Header Size
        buffer.putInt(fileSize) // Total Size

        // String Pool Chunk
        buffer.putShort(0x0001) // RES_STRING_POOL_TYPE
        buffer.putShort(28)     // Header Size
        buffer.putInt(stringPoolSize) // Chunk Size
        buffer.putInt(1)        // String Count
        buffer.putInt(0)        // Style Count
        buffer.putInt(0x100)    // Flags (UTF-8 = 0x100)
        buffer.putInt(28 + 4)   // Strings Start (Header + Offsets)
        buffer.putInt(0)        // Styles Start

        // Offsets
        buffer.putInt(0)        // Offset for string 0

        // Strings Data
        // "Test" in UTF-8 AXML:
        // CharLen(1), ByteLen(1), 'T', 'e', 's', 't', Null(1)
        buffer.put(4.toByte()) // Char Length
        buffer.put(4.toByte()) // Byte Length
        buffer.put("Test".toByteArray(Charsets.UTF_8))
        buffer.put(0.toByte()) // Null terminator

        // Padding (1 byte to reach 8 bytes aligned from start of strings?)
        // Strings started at 28+4 = 32.
        // We wrote 1+1+4+1 = 7 bytes. Pos is 39.
        // Need to pad to 40.
        buffer.put(0.toByte())

        val data = buffer.array()

        // Parse
        // If the parser doesn't support UTF-8, it will interpret the length bytes (04 04) as a short length (0x0404 = 1028).
        // Then it will try to read 1028*2 bytes.
        // It will fail with BufferUnderflowException or similar, or return garbage.

        try {
            val parser = BinaryXmlParser(data)
            val strings = parser.getStrings()
            assertEquals(1, strings.size)
            assertEquals("Test", strings[0])
        } catch (e: Exception) {
            // If it crashes, the test fails (which confirms the bug or the fix works if it passes)
            throw e
        }
    }
}
