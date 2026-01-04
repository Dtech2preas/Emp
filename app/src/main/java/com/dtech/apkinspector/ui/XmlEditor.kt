package com.dtech.apkinspector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dtech.apkinspector.data.BinaryXmlParser

@Composable
fun XmlEditor(initialContent: ByteArray, onSave: (ByteArray) -> Unit) {
    val parser = remember(initialContent) { BinaryXmlParser(initialContent) }
    var strings by remember { mutableStateOf(parser.getStrings().toList()) }
    var searchQuery by remember { mutableStateOf("") }

    // Mode: "Structure" (View) vs "Strings" (Edit)
    // Structure editing is too complex for this simplified parser, so we focus on String Editing
    // which allows renaming packages, permissions, app names, etc.
    var mode by remember { mutableStateOf("View Structure") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val modes = listOf("View Structure", "Edit Strings")
            modes.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(m) }
                )
            }
            if (mode == "Edit Strings") {
                Button(onClick = {
                    strings.forEachIndexed { i, s -> parser.setString(i, s) }
                    onSave(parser.rebuild())
                }) {
                    Text("Save")
                }
            }
        }

        if (mode == "View Structure") {
             val decoded = remember(strings) {
                 // Re-decode with potentially updated strings
                 parser.decode()
             }
             Column(modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(decoded, fontFamily = FontFamily.Monospace)
             }
        } else {
            // String Editor
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Strings") },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                itemsIndexed(strings) { index, str ->
                    if (searchQuery.isEmpty() || str.contains(searchQuery, true)) {
                        OutlinedTextField(
                            value = str,
                            onValueChange = { newValue ->
                                val newList = strings.toMutableList()
                                newList[index] = newValue
                                strings = newList
                                // Update parser immediately so View Structure reflects it?
                                // Ideally we wait for save, but for preview let's keep it sync in memory
                                parser.setString(index, newValue)
                            },
                            label = { Text("Index $index") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
