package com.askchat.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.DocumentsContract
import com.askchat.data.ChatDatabase
import com.askchat.data.ParsedMessage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

data class SyncResult(
    val chats: Int,
    val messages: Int,
    val documents: Int,
    val documentsWithText: Int,
    val documentsAccessible: Boolean,
    val backupModified: Long,
)

/**
 * Imports everything from WhatsApp's nightly local backup.
 *
 * The user grants access once to   Android/media/com.whatsapp/WhatsApp
 * which holds both  Databases/msgstore.db.crypt15  and  Media/… (shared documents).
 * (An older grant of just the Databases folder still works for messages, without documents.)
 *
 * Each sync decrypts the backup to a temp file in no-backup storage, reads it, and deletes the
 * plaintext copy immediately. Document text is extracted on-device and cached by path + size.
 */
class BackupSync(private val context: Context, private val db: ChatDatabase) {

    private val prefs = context.getSharedPreferences("backup_sync", Context.MODE_PRIVATE)
    val keyStore = BackupKeyStore(context)

    var folderUri: Uri?
        get() = prefs.getString(PREF_FOLDER, null)?.let(Uri::parse)
        set(v) = prefs.edit().putString(PREF_FOLDER, v?.toString()).apply()

    val lastSyncedBackupModified: Long get() = prefs.getLong(PREF_LAST_MODIFIED, 0)

    /** True when the granted folder is the WhatsApp folder (so Media/ is reachable). */
    val documentsAccessible: Boolean
        get() = folderUri?.let { DocumentsContract.getTreeDocumentId(it).trimEnd('/').endsWith("/WhatsApp") } == true

    data class BackupFile(val uri: Uri, val size: Long, val modified: Long)

    fun findBackup(): BackupFile? {
        val tree = folderUri ?: return null
        val root = DocumentsContract.getTreeDocumentId(tree).trimEnd('/')
        return listOf("$root/Databases/$BACKUP_NAME", "$root/$BACKUP_NAME").firstNotNullOfOrNull { docId ->
            stat(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
        }
    }

    /** Size + last-modified of a document, or null if it doesn't exist / isn't accessible. */
    private fun stat(uri: Uri): BackupFile? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) BackupFile(uri, c.getLong(0), c.getLong(1)) else null }
    }.getOrNull()

    private fun mediaUri(relPath: String): Uri? {
        val tree = folderUri ?: return null
        if (!documentsAccessible) return null
        val root = DocumentsContract.getTreeDocumentId(tree).trimEnd('/')
        return DocumentsContract.buildDocumentUriUsingTree(tree, "$root/$relPath")
    }

    fun hasNewerBackup(): Boolean {
        val backup = findBackup() ?: return false
        return keyStore.hasKey && backup.modified > lastSyncedBackupModified
    }

    fun sync(selfName: String, progress: (String) -> Unit = {}): SyncResult {
        val rootKey = keyStore.load() ?: throw BackupException("Enter your 64-digit backup key first.")
        if (folderUri == null) throw BackupException("Choose the WhatsApp folder first.")
        val backup = findBackup()
            ?: throw BackupException("No $BACKUP_NAME found. Run a backup in WhatsApp → Settings → Chats → Chat backup.")

        val tmp = java.io.File(context.noBackupFilesDir, "msgstore-decrypted.db")
        try {
            progress("Decrypting backup…")
            Crypt15Decryptor.decrypt(
                rootKey,
                open = { context.contentResolver.openInputStream(backup.uri) ?: throw BackupException("Can't open backup file.") },
                length = backup.size,
                out = tmp,
            )
            progress("Reading chats…")
            val chats = MsgstoreReader(selfName, ::contactName).read(tmp)
            tmp.delete()

            // The same person can appear as two chats (phone jid + LID) → merge by display name.
            val merged = chats.groupBy { it.name }
            val totalDocs = chats.sumOf { it.documents.size }
            var docsDone = 0
            var docsWithText = 0
            var messages = 0
            if (documentsAccessible && totalDocs > 0) PDFBoxResourceLoader.init(context)

            db.inTransaction {
                for ((name, parts) in merged) {
                    val msgs = parts.flatMap { it.messages }.sortedBy { it.timestamp }
                    val docs = parts.flatMap { it.documents }.sortedBy { it.timestamp }
                    messages += msgs.size
                    db.replaceChatStreaming(name, mergeByTime(msgs, docs) { doc ->
                        docsDone++
                        if (docsDone % 10 == 0) progress("Reading documents… $docsDone / $totalDocs")
                        documentEntries(doc).also { if (it.size > 1) docsWithText++ }
                    })
                }
            }

            prefs.edit().putLong(PREF_LAST_MODIFIED, backup.modified).apply()
            return SyncResult(merged.size, messages, totalDocs, docsWithText, documentsAccessible, backup.modified)
        } finally {
            tmp.delete()
            java.io.File(tmp.path + "-journal").delete()
        }
    }

    /** Messages and document entries interleaved by time; document text is extracted lazily. */
    private fun mergeByTime(
        msgs: List<ParsedMessage>,
        docs: List<BackupDoc>,
        expand: (BackupDoc) -> List<ParsedMessage>,
    ): Sequence<ParsedMessage> = sequence {
        var i = 0; var j = 0
        while (i < msgs.size || j < docs.size) {
            if (j >= docs.size || (i < msgs.size && msgs[i].timestamp <= docs[j].timestamp)) yield(msgs[i++])
            else yieldAll(expand(docs[j++]))
        }
    }

    /** "📄 Shared document: x.pdf" marker (level 1) + extracted-text chunks (level 2). */
    private fun documentEntries(doc: BackupDoc): List<ParsedMessage> {
        val marker = ParsedMessage(doc.timestamp, doc.sender, "📄 Shared document: ${doc.name}")
        val text = documentText(doc) ?: return listOf(marker)
        return listOf(marker) + DocumentText.chunks(text).mapIndexed { i, chunk ->
            val part = if (i == 0) "" else " (cont.)"
            ParsedMessage(doc.timestamp, doc.sender, "[Document: ${doc.name}$part] $chunk", kind = 1)
        }
    }

    private fun documentText(doc: BackupDoc): String? {
        if (DocumentText.kind(doc.name, doc.mime) == null) return null
        val uri = mediaUri(doc.relPath) ?: return null
        val file = stat(uri) ?: return null   // file deleted from phone, or not downloaded
        db.cachedDocText(doc.relPath, file.size)?.let { return it.ifEmpty { null } }
        val text = DocumentText.extract(doc.name, doc.mime, file.size) {
            context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("can't open")
        }
        db.cacheDocText(doc.relPath, file.size, text)
        return text
    }

    private val contactCache = HashMap<String, String?>()

    private fun contactName(phone: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return contactCache.getOrPut(phone) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            runCatching {
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
    }

    companion object {
        const val BACKUP_NAME = "msgstore.db.crypt15"
        private const val PREF_FOLDER = "folder_uri"
        private const val PREF_LAST_MODIFIED = "last_modified"

        /** Pre-navigates the folder picker to WhatsApp's folder (holds Databases/ and Media/). */
        val INITIAL_FOLDER: Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/media/com.whatsapp/WhatsApp",
        )
    }
}
