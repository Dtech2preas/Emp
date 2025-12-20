package com.dtech.apkinspector.domain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.*
import java.security.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.security.auth.x500.X500Principal

class V1Signer {

    private val KEY_ALIAS = "DTechForgeKey"
    private val KEY_STORE = "AndroidKeyStore"

    fun sign(inputApk: File, outputApk: File) {
        val (privateKey, cert) = getOrGenerateKey()

        // 1. Process files and generate Manifest
        val manifest = Manifest()
        val manifestAttributes = manifest.mainAttributes
        manifestAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifestAttributes[Attributes.Name("Created-By")] = "D-Tech Modder"

        // We need to read input, calculate hashes, and write to output
        // Note: We need to write the output ZIP *first* with all files (excluding META-INF),
        // then append the META-INF files.
        // Actually, JarOutputStream handles Manifest if passed to constructor, but we want manual control.
        // We will collect all entries first.

        val tempOut = File(outputApk.parent, "temp_signed.apk")
        val jos = JarOutputStream(FileOutputStream(tempOut))
        jos.setLevel(9) // Compression level

        val buffer = ByteArray(8192)
        val shas = mutableMapOf<String, String>()
        val md = MessageDigest.getInstance("SHA-1")

        // Copy entries and digest
        FileInputStream(inputApk).use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.startsWith("META-INF/") && !entry.isDirectory) {
                        // Copy entry
                        val newEntry = JarEntry(name)
                        newEntry.method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.size = entry.size
                            newEntry.compressedSize = entry.compressedSize
                            newEntry.crc = entry.crc
                        }
                        jos.putNextEntry(newEntry)

                        // Read and digest
                        md.reset()
                        var len: Int
                        val baos = ByteArrayOutputStream() // We need to read to copy, and digest
                        // Wait, for large files we should stream.
                        // We can pipe to jos and update digest.
                        var bytesRead: Long = 0
                        while (zis.read(buffer).also { len = it } != -1) {
                            jos.write(buffer, 0, len)
                            md.update(buffer, 0, len)
                            bytesRead += len
                        }
                        jos.closeEntry()

                        val digest = Base64.getEncoder().encodeToString(md.digest())
                        shas[name] = digest

                        // Add to Manifest
                        val attr = Attributes()
                        attr[Attributes.Name("SHA1-Digest")] = digest
                        manifest.entries[name] = attr
                    }
                    entry = zis.nextEntry
                }
            }
        }

        // 2. Write Manifest to JAR
        val manEntry = JarEntry("META-INF/MANIFEST.MF")
        jos.putNextEntry(manEntry)
        manifest.write(jos)
        jos.closeEntry()

        // 3. Generate Signature File (CERT.SF)
        val sf = Manifest()
        val sfMain = sf.mainAttributes
        sfMain[Attributes.Name.SIGNATURE_VERSION] = "1.0"
        sfMain[Attributes.Name("Created-By")] = "D-Tech Modder"
        sfMain[Attributes.Name("SHA1-Digest-Manifest")] = calculateManifestDigest(manifest)
        sfMain[Attributes.Name("SHA1-Digest-Manifest-Main-Attributes")] = calculateManifestMainAttrsDigest(manifest)

        // Add entries to SF
        for ((name, _) in manifest.entries) {
            val attr = Attributes()
            attr[Attributes.Name("SHA1-Digest")] = calculateManifestEntryDigest(manifest, name)
            sf.entries[name] = attr
        }

        val sfEntry = JarEntry("META-INF/CERT.SF")
        jos.putNextEntry(sfEntry)
        sf.write(jos)
        jos.closeEntry()

        // 4. Generate Signature Block (CERT.RSA)
        // Sign the SF file
        val sfBytes = ByteArrayOutputStream().apply { sf.write(this) }.toByteArray()
        val signature = signData(sfBytes, privateKey)
        val rsaBytes = createPKCS7(signature, cert as X509Certificate)

        val rsaEntry = JarEntry("META-INF/CERT.RSA")
        jos.putNextEntry(rsaEntry)
        jos.write(rsaBytes)
        jos.closeEntry()

        jos.close()

        // Move temp to dest
        if (outputApk.exists()) outputApk.delete()
        tempOut.renameTo(outputApk)
    }

    private fun getOrGenerateKey(): Pair<PrivateKey, Certificate> {
        val ks = KeyStore.getInstance(KEY_STORE)
        ks.load(null)

        if (!ks.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEY_STORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=D-Tech Modder, O=DTech"))
                .setCertificateSerialNumber(java.math.BigInteger.ONE)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        return Pair(ks.getKey(KEY_ALIAS, null) as PrivateKey, ks.getCertificate(KEY_ALIAS))
    }

    private fun signData(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val s = Signature.getInstance("SHA1withRSA")
        s.initSign(privateKey)
        s.update(data)
        return s.sign()
    }

    private fun calculateManifestDigest(manifest: Manifest): String {
        val md = MessageDigest.getInstance("SHA-1")
        val baos = ByteArrayOutputStream()
        manifest.write(baos)
        return Base64.getEncoder().encodeToString(md.digest(baos.toByteArray()))
    }

    private fun calculateManifestMainAttrsDigest(manifest: Manifest): String {
        val md = MessageDigest.getInstance("SHA-1")
        // Manifest write() format is tricky. We need the main attributes exactly as written.
        // A robust way is to write the whole manifest, and parse the first section.
        // Or trust that standard Manifest.write puts main attributes first, followed by a newline.
        val baos = ByteArrayOutputStream()
        manifest.write(baos)
        val bytes = baos.toByteArray()
        // Find first empty line
        var idx = 0
        while (idx < bytes.size - 4) {
             if (bytes[idx] == 13.toByte() && bytes[idx+1] == 10.toByte() && bytes[idx+2] == 13.toByte() && bytes[idx+3] == 10.toByte()) {
                 return Base64.getEncoder().encodeToString(md.digest(bytes.copyOfRange(0, idx+2))) // Include the first CRLF?
             }
             idx++
        }
        return "" // Fallback
    }

    private fun calculateManifestEntryDigest(manifest: Manifest, entryName: String): String {
        // This is extremely fragile with java.util.jar.Manifest because we can't get exact bytes of an entry.
        // We have to iterate the written manifest bytes and find the entry.
        val md = MessageDigest.getInstance("SHA-1")
        val baos = ByteArrayOutputStream()
        manifest.write(baos)
        val bytes = baos.toByteArray()
        val str = bytes.toString(Charsets.UTF_8)

        // Find entry start: "Name: $entryName\r\n"
        val startMarker = "Name: $entryName\r\n"
        val startIdx = str.indexOf(startMarker)
        if (startIdx == -1) return ""

        // Find entry end: Next "\r\n\r\n"
        val endMarker = "\r\n\r\n"
        val endIdx = str.indexOf(endMarker, startIdx)
        if (endIdx == -1) return ""

        val sectionBytes = bytes.copyOfRange(startIdx, endIdx + 2) // +2 for first CRLF of the gap
        return Base64.getEncoder().encodeToString(md.digest(sectionBytes))
    }

    // --- ASN.1 Minimal Encoder ---

    private fun createPKCS7(signature: ByteArray, cert: X509Certificate): ByteArray {
        // Construct PKCS#7 SignedData structure
        // ContentInfo
        return Asn1.seq(
            Asn1.oid("1.2.840.113549.1.7.2"), // signedData
            Asn1.explicit(0, Asn1.seq( // SignedData
                Asn1.int(1), // version
                Asn1.set(Asn1.seq(Asn1.oid("1.3.14.3.2.26"), Asn1.nullVal())), // digestAlgorithms: SHA1
                Asn1.seq(Asn1.oid("1.2.840.113549.1.7.1")), // contentInfo: data (detached)
                Asn1.explicit(0, cert.encoded), // certificates (implicit [0])
                Asn1.set( // signerInfos
                    Asn1.seq(
                        Asn1.int(1), // version
                        Asn1.seq( // issuerAndSerialNumber
                            Asn1.raw(cert.issuerX500Principal.encoded),
                            Asn1.int(cert.serialNumber)
                        ),
                        Asn1.seq(Asn1.oid("1.3.14.3.2.26"), Asn1.nullVal()), // digestAlgorithm: SHA1
                        Asn1.seq(Asn1.oid("1.2.840.113549.1.1.1"), Asn1.nullVal()), // digestEncryptionAlgorithm: RSA
                        Asn1.octetString(signature) // encryptedDigest
                    )
                )
            ))
        )
    }

    object Asn1 {
        fun seq(vararg components: ByteArray): ByteArray = tag(0x30, components)
        fun set(vararg components: ByteArray): ByteArray = tag(0x31, components)

        fun explicit(tag: Int, data: ByteArray): ByteArray {
             // Context specific tag [tag]
             return tag(0xA0 or tag, arrayOf(data))
        }

        fun oid(oid: String): ByteArray {
            // Minimal OID encoder for known OIDs
            // 1.2.840.113549.1.7.2 -> 2A 86 48 86 F7 0D 01 07 02
            // 1.2.840.113549.1.7.1 -> 2A 86 48 86 F7 0D 01 07 01
            // 1.3.14.3.2.26 (SHA1) -> 2B 0E 03 02 1A
            // 1.2.840.113549.1.1.1 (RSA) -> 2A 86 48 86 F7 0D 01 01 01
            val bytes = when (oid) {
                "1.2.840.113549.1.7.2" -> byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)
                "1.2.840.113549.1.7.1" -> byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01)
                "1.3.14.3.2.26" -> byteArrayOf(0x2B, 0x0E, 0x03, 0x02, 0x1A)
                "1.2.840.113549.1.1.1" -> byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)
                else -> throw IllegalArgumentException("Unknown OID")
            }
            return tag(0x06, arrayOf(bytes))
        }

        fun int(value: Int): ByteArray {
            // Simplified integer encoder
            return tag(0x02, arrayOf(byteArrayOf(value.toByte()))) // Works for small ints
        }

        fun int(value: java.math.BigInteger): ByteArray {
            return tag(0x02, arrayOf(value.toByteArray()))
        }

        fun nullVal(): ByteArray = byteArrayOf(0x05, 0x00)

        fun octetString(data: ByteArray): ByteArray = tag(0x04, arrayOf(data))

        fun raw(data: ByteArray): ByteArray = data

        private fun tag(tag: Int, components: Array<ByteArray>): ByteArray {
            val baos = ByteArrayOutputStream()
            components.forEach { baos.write(it) }
            val data = baos.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(tag)
            writeLength(out, data.size)
            out.write(data)
            return out.toByteArray()
        }

        private fun writeLength(out: ByteArrayOutputStream, length: Int) {
            if (length < 128) {
                out.write(length)
            } else {
                val lenBytes = mutableListOf<Byte>()
                var l = length
                while (l > 0) {
                    lenBytes.add(0, (l and 0xFF).toByte())
                    l = l shr 8
                }
                out.write(0x80 or lenBytes.size)
                lenBytes.forEach { out.write(it.toInt()) }
            }
        }
    }
}
