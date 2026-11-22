package com.askchat

import com.askchat.backup.BackupException
import com.askchat.backup.Crypt15Decryptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Builds synthetic .crypt15 files exactly as wa-crypt-tools' Database15.encrypt does, using the
 * JVM's own AES-GCM, and checks the streaming BouncyCastle decryptor recovers the plaintext.
 */
class Crypt15DecryptorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val rootKey = ByteArray(32) { it.toByte() }
    private val rootHex = rootKey.joinToString("") { "%02x".format(it) }

    @Test
    fun keyDerivationMatchesWaCryptTools() {
        // Reference computed with wa-crypt-tools' encryptionloop(root, "backup encryption", 32).
        val expected = "4e54c0777a7214dfdf0af5b6e153acfa2fbdac603fea6fd1630b824e5c29eef5"
        assertEquals(expected, Crypt15Decryptor.deriveKey(rootKey).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun parseKeyAcceptsSpacedHex() {
        val spaced = rootHex.chunked(4).joinToString(" ").uppercase()
        assertArrayEquals(rootKey, Crypt15Decryptor.parseKey(spaced))
    }

    @Test(expected = BackupException::class)
    fun parseKeyRejectsShortKey() {
        Crypt15Decryptor.parseKey("abcd")
    }

    @Test
    fun decryptsSingleFileBackupWithChecksum() = roundTrip(withChecksum = true)

    @Test
    fun decryptsMultiFileBackupWithoutChecksum() = roundTrip(withChecksum = false)

    @Test
    fun wrongKeyFailsAndLeavesNoOutput() {
        val (file, _) = buildBackup(withChecksum = true)
        val out = tmp.newFile("out.db")
        val wrong = ByteArray(32) { 7 }
        try {
            Crypt15Decryptor.decrypt(wrong, { file.inputStream() }, file.length(), out)
            throw AssertionError("expected failure")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("Wrong key"))
        }
        assertFalse(out.exists())
    }

    private fun roundTrip(withChecksum: Boolean) {
        val (file, plain) = buildBackup(withChecksum)
        val out = tmp.newFile("out-$withChecksum.db")
        Crypt15Decryptor.decrypt(rootKey, { file.inputStream() }, file.length(), out)
        assertArrayEquals(plain, out.readBytes())
    }

    private fun buildBackup(withChecksum: Boolean): Pair<java.io.File, ByteArray> {
        // Fake SQLite DB, large enough to span many 256 KB chunks.
        val plain = "SQLite format 3\u0000".toByteArray() + Random(42).nextBytes(700_000) + ByteArray(300_000)
        val compressed = ByteArrayOutputStream().also { bos ->
            val d = Deflater(); d.setInput(plain); d.finish()
            val buf = ByteArray(8192)
            while (!d.finished()) bos.write(buf, 0, d.deflate(buf))
        }.toByteArray()

        val iv = Random(1).nextBytes(16)
        // BackupPrefix { key_type=1 (varint), e2ee_key_data (3) { encryption_iv (1) = iv }, backup_metadata (4) { junk } }
        val c15 = byteArrayOf(0x0A, 16) + iv
        val meta = byteArrayOf(0x0A, 3) + "2.2".toByteArray()
        val prefix = byteArrayOf(0x08, 0x01) + byteArrayOf(0x1A, c15.size.toByte()) + c15 + byteArrayOf(0x22, meta.size.toByte()) + meta
        val header = byteArrayOf(prefix.size.toByte()) + prefix

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Crypt15Decryptor.deriveKey(rootKey), "AES"), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(compressed) // ciphertext || 16-byte tag

        var bytes = header + body
        if (withChecksum) bytes += MessageDigest.getInstance("MD5").digest(bytes)
        val f = tmp.newFile("msgstore-$withChecksum.db.crypt15")
        f.writeBytes(bytes)
        return f to plain
    }
}
