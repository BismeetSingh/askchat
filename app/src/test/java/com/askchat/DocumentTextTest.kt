package com.askchat

import com.askchat.backup.DocumentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun docxParagraphsAndEntities() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>Wifi password:</w:t></w:r><w:r><w:t xml:space="preserve"> Hunter2&amp;Co</w:t></w:r></w:p><w:p><w:r><w:t>Second line</w:t></w:r></w:p></w:body></w:document>"""
        val bytes = zip("word/document.xml" to xml, "word/styles.xml" to "<w:t>ignored</w:t>")
        val text = DocumentText.extract("notes.docx", null, bytes.size.toLong()) { bytes.inputStream() }
        assertEquals("Wifi password: Hunter2&Co\nSecond line", text)
    }

    @Test
    fun xlsxSharedStrings() {
        val xml = """<sst><si><t>Account</t></si><si><t>GST 27ABCDE1234F1Z5</t></si></sst>"""
        val bytes = zip("xl/sharedStrings.xml" to xml)
        val text = DocumentText.extract("accounts.xlsx", null, bytes.size.toLong()) { bytes.inputStream() }!!
        assertTrue(text.contains("GST 27ABCDE1234F1Z5"))
    }

    @Test
    fun unsupportedReturnsNull() {
        assertEquals(null, DocumentText.kind("song.mp3", "audio/mpeg"))
        assertEquals("pdf", DocumentText.kind("x.PDF", null))
    }

    @Test
    fun chunksSplitOnBoundariesAndKeepAllText() {
        val text = (1..400).joinToString(" ") { "word$it" }
        val chunks = DocumentText.chunks(text, size = 200)
        assertTrue(chunks.all { it.length <= 200 })
        assertEquals(text.split(" "), chunks.joinToString(" ").split(" "))
    }
}
