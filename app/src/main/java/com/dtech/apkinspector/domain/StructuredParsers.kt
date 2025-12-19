package com.dtech.apkinspector.domain

import com.dtech.apkinspector.data.BinaryXmlParser
import com.dtech.apkinspector.data.ArscEditor

class StructuredParsers {

    data class EditResult(
        val manifest: ByteArray? = null,
        val arsc: ByteArray? = null
    )

    fun getAppLabel(manifestBytes: ByteArray, arscBytes: ByteArray?): String {
        val parser = BinaryXmlParser(manifestBytes)
        var label: String = "Unknown"

        parser.traverse { name, attrs ->
            if (name == "application") {
                val labelAttr = attrs.find { it.name == "label" }
                if (labelAttr != null) {
                    if (labelAttr.value != null) {
                        label = labelAttr.value
                    } else if (labelAttr.type == 0x01 && arscBytes != null) { // TYPE_REFERENCE
                        try {
                            val arsc = ArscEditor(arscBytes)
                            val resolved = arsc.getResourceString(labelAttr.data)
                            if (resolved != null) label = resolved
                        } catch (e: Exception) {
                            // Ignore arsc errors
                        }
                    }
                }
            }
        }
        return label
    }

    fun updateAppLabel(manifestBytes: ByteArray, arscBytes: ByteArray?, newLabel: String): EditResult {
        val parser = BinaryXmlParser(manifestBytes)
        var manifestModified = false
        var arscModified = false
        var newManifestBytes: ByteArray? = null
        var newArscBytes: ByteArray? = null

        var targetStringIndex = -1
        var targetResId = -1

        parser.traverse { name, attrs ->
             if (name == "application") {
                 val labelAttr = attrs.find { it.name == "label" }
                 if (labelAttr != null) {
                     if (labelAttr.value != null) {
                         targetStringIndex = labelAttr.valueIdx
                     } else if (labelAttr.type == 0x01) {
                         targetResId = labelAttr.data
                     }
                 }
             }
        }

        if (targetStringIndex != -1) {
            parser.setString(targetStringIndex, newLabel)
            newManifestBytes = parser.rebuild()
            manifestModified = true
        } else if (targetResId != -1 && arscBytes != null) {
            val arsc = ArscEditor(arscBytes)
            arsc.updateResourceString(targetResId, newLabel)
            newArscBytes = arsc.rebuild()
            arscModified = true
        }

        return EditResult(
            manifest = if (manifestModified) newManifestBytes else null,
            arsc = if (arscModified) newArscBytes else null
        )
    }

    fun getPackageName(manifestBytes: ByteArray): String {
        val parser = BinaryXmlParser(manifestBytes)
        var pkg = ""
        parser.traverse { name, attrs ->
            if (name == "manifest") {
                val p = attrs.find { it.name == "package" }
                if (p?.value != null) {
                    pkg = p.value
                }
            }
        }
        return pkg
    }

    fun updatePackageName(manifestBytes: ByteArray, newPackageName: String): ByteArray? {
        val parser = BinaryXmlParser(manifestBytes)
        var targetIndex = -1

        parser.traverse { name, attrs ->
            if (name == "manifest") {
                val p = attrs.find { it.name == "package" }
                if (p?.value != null) {
                    targetIndex = p.valueIdx
                }
            }
        }

        if (targetIndex != -1) {
            parser.setString(targetIndex, newPackageName)
            return parser.rebuild()
        }
        return null
    }

    fun getColors(arscBytes: ByteArray): Map<String, Int> {
        val arsc = ArscEditor(arscBytes)
        return arsc.getColors()
    }

    fun updateColor(arscBytes: ByteArray, name: String, newColor: Int): ByteArray {
        val arsc = ArscEditor(arscBytes)
        arsc.updateColor(name, newColor)
        return arsc.rebuild()
    }
}
