package com.askchat.backup

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * Decrypts a WhatsApp `msgstore.db.crypt15` backup made with a 64-digit end-to-end backup key.
 *
 * File layout (per wa-crypt-tools, github.com/ElDavoo/wa-crypt-tools):
 *   varint headerSize | BackupPrefix protobuf (IV at field 3 → field 1, 16 bytes)
 *   | AES-256-GCM ciphertext | 16-byte GCM tag | [16-byte MD5 of everything before it]
 * The MD5 trailer is absent in "multi-file" backups. Plaintext is a zlib-compressed SQLite DB.
 *
 * Streams end to end (BouncyCastle GCM + Inflater) so multi-hundred-MB backups don't need RAM.
 * The GCM tag is checked at the end; on mismatch the partial output is deleted.
 */
object Crypt15Decryptor {

    /** Parses the 64 hex digits WhatsApp shows (spaces/dashes allowed). */
    fun parseKey(input: String): ByteArray {
        val hex = input.filter { !it.isWhitespace() && it != '-' }.lowercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw BackupException("The key must be exactly 64 characters (0-9, a-f). Got ${hex.length}.")
        }
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** wa-crypt-tools `encryptionloop`: HKDF-SHA256 with a zero salt, info = "backup encryption". */
    internal fun deriveKey(rootKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(rootKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update("backup encryption".toByteArray())
        mac.update(1)
        return mac.doFinal()
    }

    /**
     * @param open opens a fresh stream over the backup file (called twice: checksum pass + decrypt pass)
     * @param length total file size in bytes
     */
    fun decrypt(rootKey: ByteArray, open: () -> InputStream, length: Long, out: File) {
        if (length < 64) throw BackupException("Backup file is too small to be valid.")

        val header = open().use { readHeader(it) }

        // Single-file backups end with MD5(everything before it); multi-file ones don't.
        val hasChecksum = open().use { input ->
            val md5 = MessageDigest.getInstance("MD5")
            copyRange(input, length - 16) { buf, n -> md5.update(buf, 0, n) }
            val trailer = input.readNBytesCompat(16)
            md5.digest().contentEquals(trailer)
        }
        // Bytes fed to GCM = ciphertext followed by its tag (BouncyCastle expects the tag last).
        val cipherEnd = if (hasChecksum) length - 16 else length

        val gcm = GCMBlockCipher.newInstance(AESEngine.newInstance())
        gcm.init(false, AEADParameters(KeyParameter(deriveKey(rootKey)), 128, header.iv))
        val inflater = Inflater()
        var sawData = false

        try {
            out.outputStream().buffered().use { sink ->
                val inflateBuf = ByteArray(64 * 1024)
                fun inflate(data: ByteArray, n: Int) {
                    if (n <= 0) return
                    if (!sawData) {
                        sawData = true
                        if (data[0] != 0x78.toByte()) throw BackupException(WRONG_KEY)
                    }
                    inflater.setInput(data, 0, n)
                    while (!inflater.needsInput() && !inflater.finished()) {
                        val k = inflater.inflate(inflateBuf)
                        if (k == 0 && inflater.needsDictionary()) throw BackupException("Unexpected compressed data.")
                        sink.write(inflateBuf, 0, k)
                    }
                }

                open().use { input ->
                    skipFully(input, header.size)
                    val plain = ByteArray(gcm.getUpdateOutputSize(CHUNK) + 32)
                    copyRange(input, cipherEnd - header.size) { buf, n ->
                        inflate(plain, gcm.processBytes(buf, 0, n, plain, 0))
                    }
                    val tail = ByteArray(gcm.getOutputSize(0) + 16)
                    val n = try {
                        gcm.doFinal(tail, 0)
                    } catch (_: InvalidCipherTextException) {
                        throw BackupException(WRONG_KEY)
                    }
                    inflate(tail, n)
                }
            }
            if (!inflater.finished()) throw BackupException("Backup looks truncated (incomplete data).")
            out.inputStream().use { s ->
                val magic = s.readNBytesCompat(15)
                if (String(magic, Charsets.US_ASCII) != "SQLite format 3") throw BackupException("Decrypted data isn't a SQLite database.")
            }
        } catch (e: Exception) {
            out.delete()
            throw when (e) {
                is BackupException -> e
                is java.util.zip.DataFormatException -> BackupException(WRONG_KEY)
                else -> BackupException("Couldn't decrypt backup: ${e.message}")
            }
        } finally {
            inflater.end()
        }
    }

    internal class Header(val size: Long, val iv: ByteArray)

    internal fun readHeader(input: InputStream): Header {
        val bytes = input.readNBytesCompat(4096)
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (shift < 64) {
                if (pos >= bytes.size) throw BackupException(NOT_CRYPT15)
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw BackupException(NOT_CRYPT15)
        }
        val protoSize = varint().toInt()
        val protoStart = pos
        if (protoSize <= 0 || protoStart + protoSize > bytes.size) throw BackupException(NOT_CRYPT15)
        val c15 = findField(bytes, protoStart, protoStart + protoSize, 3)
            ?: throw BackupException("This backup isn't end-to-end encrypted with a 64-digit key (no crypt15 IV found).")
        val iv = findField(bytes, c15.first, c15.second, 1)
            ?.let { (s, e) -> bytes.copyOfRange(s, e) }
            ?.takeIf { it.size == 16 }
            ?: throw BackupException(NOT_CRYPT15)
        return Header((protoStart + protoSize).toLong(), iv)
    }

    /** Finds a length-delimited protobuf field; returns its [start, end) range. */
    private fun findField(b: ByteArray, start: Int, end: Int, field: Int): Pair<Int, Int>? {
        var p = start
        fun varint(): Long {
            var r = 0L; var s = 0
            while (p < end && s < 64) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7
            }
            throw BackupException(NOT_CRYPT15)
        }
        while (p < end) {
            val tag = varint()
            val num = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> varint()
                1 -> p += 8
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || p + len > end) throw BackupException(NOT_CRYPT15)
                    if (num == field) return p to p + len
                    p += len
                }
                5 -> p += 4
                else -> throw BackupException(NOT_CRYPT15)
            }
        }
        return null
    }

    private fun copyRange(input: InputStream, count: Long, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(CHUNK)
        var left = count
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw BackupException("Backup file ended early.")
            sink(buf, n)
            left -= n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        copyRange(input, count) { _, _ -> }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private const val CHUNK = 256 * 1024
    private const val WRONG_KEY = "Wrong key — couldn't decrypt the backup. Double-check the 64-digit key."
    private const val NOT_CRYPT15 = "This doesn't look like a WhatsApp .crypt15 backup."
}
