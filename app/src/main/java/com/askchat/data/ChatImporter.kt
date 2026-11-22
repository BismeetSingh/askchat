package com.askchat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream

data class ImportResult(val chatName: String, val messageCount: Int, val senders: Set<String>)

/** Reads a WhatsApp export (.txt, or .zip containing one) from a content Uri and stores it. */
class ChatImporter(private val context: Context, private val db: ChatDatabase) {

    fun import(uri: Uri, subjectHint: String? = null): ImportResult {
        val fileName = displayName(uri)
        val content = readText(uri, fileName)
        val parsed = ChatParser.parse(content)
        require(parsed.messages.isNotEmpty()) {
            "No messages found in ${fileName ?: "file"}. Is this a WhatsApp \"Export chat\" file?"
        }
        val name = ChatParser.chatNameFromFileName(subjectHint)
            ?: ChatParser.chatNameFromFileName(fileName)
            ?: parsed.senders.take(3).joinToString(", ")
        db.replaceChat(name, parsed.messages)
        return ImportResult(name, parsed.messages.size, parsed.senders)
    }

    private fun readText(uri: Uri, fileName: String?): String {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val isZip = mime.contains("zip") || fileName?.endsWith(".zip", ignoreCase = true) == true
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open $uri" }
            if (!isZip) return input.bufferedReader(Charsets.UTF_8).readText()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) {
                        return zip.bufferedReader(Charsets.UTF_8).readText()
                    }
                }
            }
        }
        error("No .txt chat file inside the zip")
    }

    fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
