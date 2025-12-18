package com.dtech.apkinspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun HexViewer(bytes: ByteArray) {
    val scrollState = rememberScrollState()
    val hScrollState = rememberScrollState()

    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .horizontalScroll(hScrollState)
                .background(Color(0xFF1E1E1E)) // Dark bg for hex
                .padding(8.dp)
        ) {
            Text(
                text = formatHexDump(bytes),
                color = Color(0xFF00FF00), // Hacker green
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatHexDump(bytes: ByteArray): String {
    val sb = StringBuilder()
    for (i in bytes.indices step 16) {
        // Offset
        sb.append("%08X  ".format(i))

        // Hex
        for (j in 0 until 16) {
            if (i + j < bytes.size) {
                sb.append("%02X ".format(bytes[i + j]))
            } else {
                sb.append("   ")
            }
            if (j == 7) sb.append(" ")
        }

        sb.append(" |")

        // ASCII
        for (j in 0 until 16) {
            if (i + j < bytes.size) {
                val b = bytes[i + j].toInt()
                val c = if (b in 32..126) b.toChar() else '.'
                sb.append(c)
            }
        }
        sb.append("|\n")
    }
    return sb.toString()
}
