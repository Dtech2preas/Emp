package com.dtech.apkinspector.domain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.*
import java.security.*
import java.security.cert.X509Certificate
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

class V1Signer {

    fun sign(inputApk: File, outputApk: File) {
        val keystore = KeyStore.getInstance("AndroidKeyStore")
        keystore.load(null)
        val alias = "DTechDebugKey"

        if (!keystore.containsAlias(alias)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            kpg.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=D-Tech Modder, O=D-Tech"))
                .build())
            kpg.generateKeyPair()
        }

        val privateKey = keystore.getKey(alias, null) as PrivateKey
        val cert = keystore.getCertificate(alias) as X509Certificate

        // 1. Calculate Hashes
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name("Created-By")] = "D-Tech Modder"

        val buffer = ByteArray(4096)
        val md = MessageDigest.getInstance("SHA-256")

        // Read input and hash entries
        // Note: In a real V1 signer, we must hash the *output* bytes of the entries.
        // Since we copy them 1:1, hashing input is fine.
        val inputEntries = mutableListOf<Pair<ZipEntry, ByteArray>>() // Buffer small files? No, memory.
        // We will pass through.

        // To build Manifest correctly, we need to iterate input.
        ZipInputStream(FileInputStream(inputApk)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.name.startsWith("META-INF/") && !entry.isDirectory) {
                    val baos = ByteArrayOutputStream()
                    var len: Int
                    while (zis.read(buffer).also { len = it } > 0) {
                        baos.write(buffer, 0, len)
                        md.update(buffer, 0, len)
                    }
                    val digest = Base64.encodeToString(md.digest(), Base64.NO_WRAP)

                    val attrs = Attributes()
                    attrs[Attributes.Name("SHA-256-Digest")] = digest
                    manifest.entries[entry.name] = attrs
                }
                entry = zis.nextEntry
            }
        }

        // 2. Write Output
        val fos = FileOutputStream(outputApk)
        val zos = ZipOutputStream(BufferedOutputStream(fos))
        zos.setLevel(9)

        // Copy entries
        ZipInputStream(FileInputStream(inputApk)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.name.startsWith("META-INF/") && !entry.isDirectory) {
                    val newEntry = ZipEntry(entry.name)
                    // newEntry.method = entry.method // ZipOutputStream handles method?
                    // Safe to just write data
                    zos.putNextEntry(newEntry)
                    zis.copyTo(zos)
                    zos.closeEntry()
                }
                entry = zis.nextEntry
            }
        }

        // 3. Write Manifest
        val manEntry = ZipEntry("META-INF/MANIFEST.MF")
        zos.putNextEntry(manEntry)
        manifest.write(zos)
        zos.closeEntry()

        // 4. Write SF
        val sf = Manifest()
        sf.mainAttributes[Attributes.Name.SIGNATURE_VERSION] = "1.0"
        sf.mainAttributes[Attributes.Name("Created-By")] = "D-Tech Modder"
        sf.mainAttributes[Attributes.Name("SHA-256-Digest-Manifest")] = hashManifest(manifest)
        // Optimization: Only main digest is needed

        val sfEntry = ZipEntry("META-INF/CERT.SF")
        zos.putNextEntry(sfEntry)
        sf.write(zos)
        zos.closeEntry()

        // 5. Write RSA
        val sfBytes = ByteArrayOutputStream()
        sf.write(sfBytes)
        val signature = signData(sfBytes.toByteArray(), privateKey)

        val rsaBytes = buildPKCS7(cert, signature)
        val rsaEntry = ZipEntry("META-INF/CERT.RSA")
        zos.putNextEntry(rsaEntry)
        zos.write(rsaBytes)
        zos.closeEntry()

        zos.close()
    }

    private fun hashManifest(manifest: Manifest): String {
        val baos = ByteArrayOutputStream()
        manifest.write(baos)
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.encodeToString(md.digest(baos.toByteArray()), Base64.NO_WRAP)
    }

    private fun signData(data: ByteArray, key: PrivateKey): ByteArray {
        val s = Signature.getInstance("SHA256withRSA")
        s.initSign(key)
        s.update(data)
        return s.sign()
    }

    // Minimal ASN.1 Builder
    private fun buildPKCS7(cert: X509Certificate, signature: ByteArray): ByteArray {
        val certBytes = cert.encoded

        // We construct a SignedData structure
        // ContentInfo -> SignedData

        val signerInfo = sequence(
            integer(1), // version
            sequence( // issuerAndSerialNumber
                // We need to parse cert to find issuer and serial?
                // Or we can just copy them from the cert bytes if we knew where they are.
                // Standard helper: Just use the cert subject as issuer (self-signed) and serial.
                // Hard to extract without parser.
                // Fallback: Dummy Issuer/Serial? No, must match cert.
                // X509Certificate exposes getIssuerX500Principal().encoded and getSerialNumber()
                encoded(cert.issuerX500Principal.encoded),
                integer(cert.serialNumber)
            ),
            sequence(oid("2.16.840.1.101.3.4.2.1")), // digestAlgorithm: SHA-256
            // authenticatedAttributes (implicit tag [0]) - Optional, skipping
            sequence(oid("1.2.840.113549.1.1.1")), // encryptionAlgorithm: RSA
            octetString(signature)
        )

        val signedData = sequence(
            integer(1), // version
            set(sequence(oid("2.16.840.1.101.3.4.2.1"))), // digestAlgorithms
            sequence(oid("1.2.840.113549.1.7.1")), // contentInfo: data (empty)
            implicit(0, sequence(encoded(certBytes))), // certificates
            // crls [1]
            set(signerInfo) // signerInfos
        )

        val contentInfo = sequence(
            oid("1.2.840.113549.1.7.2"), // signedData
            explicit(0, signedData)
        )

        return contentInfo
    }

    // ASN.1 Helpers
    private fun sequence(vararg elements: ByteArray): ByteArray = tag(0x30, join(elements.toList()))
    private fun set(vararg elements: ByteArray): ByteArray = tag(0x31, join(elements.toList()))
    private fun integer(v: Int): ByteArray = integer(v.toBigInteger())
    private fun integer(v: java.math.BigInteger): ByteArray = tag(0x02, v.toByteArray())
    private fun oid(id: String): ByteArray {
        val parts = id.split(".").map { it.toInt() }
        val bos = ByteArrayOutputStream()
        bos.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            val temp = mutableListOf<Byte>()
            temp.add((v and 0x7F).toByte())
            v = v ushr 7
            while (v > 0) {
                temp.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
            temp.reverse()
            bos.write(temp.toByteArray())
        }
        return tag(0x06, bos.toByteArray())
    }
    private fun octetString(bytes: ByteArray): ByteArray = tag(0x04, bytes)
    private fun explicit(tag: Int, content: ByteArray): ByteArray = tag(0xA0 or tag, content)
    private fun implicit(tag: Int, content: ByteArray): ByteArray = tag(0xA0 or tag, content) // Context specific
    private fun encoded(bytes: ByteArray): ByteArray = bytes // Raw

    private fun tag(tag: Int, content: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(tag)
        if (content.size < 128) {
            bos.write(content.size)
        } else {
            // Long form length
            var size = content.size
            val lenBytes = mutableListOf<Byte>()
            while (size > 0) {
                lenBytes.add((size and 0xFF).toByte())
                size = size shr 8
            }
            bos.write(0x80 or lenBytes.size)
            lenBytes.reversed().forEach { bos.write(it.toInt()) }
        }
        bos.write(content)
        return bos.toByteArray()
    }

    private fun join(arrays: List<ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        arrays.forEach { bos.write(it) }
        return bos.toByteArray()
    }
}
