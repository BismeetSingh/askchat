package com.askchat.backup

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pulls plain text out of shared documents, entirely on-device.
 *  - PDF via pdfbox-android (call PDFBoxResourceLoader.init(context) once before use)
 *  - .docx / .xlsx / .pptx: zipped XML, text runs pulled out directly
 *  - text/csv/json: read as-is
 * Returns null for unsupported or unreadable (e.g. password-protected) files.
 */
object DocumentText {

    const val MAX_CHARS = 40_000          // per document; keeps index + memory bounded
    private const val MAX_PDF_PAGES = 60
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024

    fun kind(name: String, mime: String?): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime.orEmpty().lowercase()
        return when {
            ext == "pdf" || m == "application/pdf" -> "pdf"
            ext == "docx" || m.contains("wordprocessingml") -> "docx"
            ext == "xlsx" || m.contains("spreadsheetml") -> "xlsx"
            ext == "pptx" || m.contains("presentationml") -> "pptx"
            ext in setOf("txt", "csv", "md", "json", "log") || m.startsWith("text/") -> "text"
            else -> null
        }
    }

    fun extract(name: String, mime: String?, size: Long, open: () -> InputStream): String? {
        if (size > MAX_FILE_BYTES) return null
        val kind = kind(name, mime) ?: return null
        val text = runCatching {
            open().use { input ->
                when (kind) {
                    "pdf" -> pdf(input)
                    "docx" -> ooxml(input, { it == "word/document.xml" }, "w:t", "w:p")
                    "xlsx" -> ooxml(input, { it == "xl/sharedStrings.xml" || it.startsWith("xl/worksheets/sheet") }, "t", "si")
                    "pptx" -> ooxml(input, { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }, "a:t", "a:p")
                    else -> input.bufferedReader().use { r ->
                        val buf = CharArray(MAX_CHARS)
                        var n = 0
                        while (n < buf.size) {
                            val k = r.read(buf, n, buf.size - n)
                            if (k < 0) break
                            n += k
                        }
                        String(buf, 0, n)
                    }
                }
            }
        }.getOrNull()
        return text?.let(::clean)?.takeIf { it.length >= 20 }?.take(MAX_CHARS)
    }

    private fun pdf(input: InputStream): String? {
        PDDocument.load(input, MemoryUsageSetting.setupMixed(16L * 1024 * 1024)).use { doc ->
            if (doc.isEncrypted) return null
            val stripper = PDFTextStripper()
            stripper.setEndPage(MAX_PDF_PAGES)
            return stripper.getText(doc)
        }
    }

    /** Office Open XML: concatenate <textTag> contents, newline at each </paraTag>. */
    internal fun ooxml(input: InputStream, wanted: (String) -> Boolean, textTag: String, paraTag: String): String {
        val out = StringBuilder()
        ZipInputStream(input).use { zip ->
            while (out.length < MAX_CHARS) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !wanted(entry.name)) continue
                val xml = zip.readBytes().toString(Charsets.UTF_8)
                out.append(xmlText(xml, textTag, paraTag)).append('\n')
            }
        }
        return out.toString()
    }

    internal fun xmlText(xml: String, textTag: String, paraTag: String): String {
        val t = Regex.escape(textTag)
        val p = Regex.escape(paraTag)
        val tokens = Regex("<$t(?:\\s[^>]*)?>(.*?)</$t>|</$p>", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        for (m in tokens.findAll(xml)) {
            if (m.value.startsWith("</")) sb.append('\n') else sb.append(unescape(m.groupValues[1]))
        }
        return sb.toString()
    }

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun clean(s: String) = s
        .replace('\u0000', ' ')
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()

    /** Splits text into ~[size]-char chunks on line/word boundaries. */
    fun chunks(text: String, size: Int = 1200): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + size)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                val sp = text.lastIndexOf(' ', end)
                val cut = if (nl > start + size / 2) nl else if (sp > start + size / 2) sp else end
                end = cut
            }
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
        }
        return result
    }
}
