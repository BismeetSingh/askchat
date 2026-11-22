package com.askchat.backup

import android.database.sqlite.SQLiteDatabase
import com.askchat.data.ParsedMessage
import java.io.File

/** A document shared in a chat. [relPath] is relative to the WhatsApp folder, e.g. "Media/WhatsApp Documents/x.pdf". */
data class BackupDoc(val timestamp: Long, val sender: String, val relPath: String, val name: String, val mime: String?)

/** A chat read from WhatsApp's msgstore.db: text messages and shared documents, each in time order. */
data class BackupChat(val key: String, val name: String, val messages: List<ParsedMessage>, val documents: List<BackupDoc>)

/**
 * Reads text messages out of a decrypted msgstore.db (WhatsApp's "new" schema, 2022+).
 *
 * Schema as used by WhatsApp-Chat-Exporter (github.com/KnugiHK/WhatsApp-Chat-Exporter):
 *   message(_id, chat_row_id, from_me, timestamp, text_data, sender_jid_row_id)
 *   chat(_id, jid_row_id, subject)      — subject = group name
 *   jid(_id, user, server, raw_string)  — server: s.whatsapp.net (person), g.us (group), lid
 *   jid_map(lid_row_id, jid_row_id)     — newer builds: maps privacy "LID" ids to phone jids
 *   message_media(message_row_id, file_path, mime_type[, media_name]) — shared files
 *
 * Names aren't stored here (they live in WhatsApp's private wa.db), so [nameForPhone] resolves
 * phone numbers via the phone's contacts.
 */
class MsgstoreReader(
    private val selfName: String,
    private val nameForPhone: (String) -> String?,
) {

    fun read(dbFile: File): List<BackupChat> {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use { return readFrom(it) }
    }

    private fun readFrom(db: SQLiteDatabase): List<BackupChat> {
        requireColumns(db, "message", "_id", "chat_row_id", "from_me", "timestamp", "text_data", "sender_jid_row_id")
        requireColumns(db, "chat", "_id", "jid_row_id", "subject")
        requireColumns(db, "jid", "_id", "user", "server")
        val hasJidMap = columns(db, "jid_map").containsAll(listOf("lid_row_id", "jid_row_id"))

        // jid row → (user, server), following LID → phone mapping when available.
        val jids = HashMap<Long, Pair<String, String>>()
        val sql = if (hasJidMap) {
            "SELECT j._id, COALESCE(p.user, j.user), COALESCE(p.server, j.server) FROM jid j " +
                "LEFT JOIN jid_map m ON m.lid_row_id = j._id LEFT JOIN jid p ON p._id = m.jid_row_id"
        } else {
            "SELECT _id, user, server FROM jid"
        }
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) jids[c.getLong(0)] = (c.getString(1).orEmpty()) to (c.getString(2).orEmpty())
        }

        class ChatInfo(val name: String, val isGroup: Boolean)
        val chats = HashMap<Long, ChatInfo>()
        db.rawQuery("SELECT _id, jid_row_id, subject FROM chat", null).use { c ->
            while (c.moveToNext()) {
                val (user, server) = jids[c.getLong(1)] ?: continue
                when (server) {
                    "g.us" -> chats[c.getLong(0)] = ChatInfo(c.getString(2)?.takeIf { it.isNotBlank() } ?: "Group $user", true)
                    "s.whatsapp.net", "lid" -> chats[c.getLong(0)] = ChatInfo(personName(user, server), false)
                    // status@broadcast, newsletters (channels), etc. are skipped.
                }
            }
        }

        fun senderOf(chat: ChatInfo, fromMe: Boolean, senderJidRow: Long?): String = when {
            fromMe -> selfName
            !chat.isGroup -> chat.name
            senderJidRow == null -> "Unknown"
            else -> jids[senderJidRow]?.let { (u, s) -> personName(u, s) } ?: "Unknown"
        }
        fun millis(raw: Long) = if (raw in 1..9_999_999_999L) raw * 1000 else raw  // seconds vs millis

        val byChat = LinkedHashMap<Long, MutableList<ParsedMessage>>()
        db.rawQuery(
            "SELECT chat_row_id, from_me, timestamp, text_data, sender_jid_row_id FROM message " +
                "WHERE text_data IS NOT NULL AND text_data <> '' ORDER BY chat_row_id, timestamp",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val chatId = c.getLong(0)
                val chat = chats[chatId] ?: continue
                val sender = senderOf(chat, c.getInt(1) == 1, if (c.isNull(4)) null else c.getLong(4))
                byChat.getOrPut(chatId) { mutableListOf() } += ParsedMessage(millis(c.getLong(2)), sender, c.getString(3))
            }
        }

        // Shared documents (anything that isn't a photo/video/audio/sticker).
        val docsByChat = HashMap<Long, MutableList<BackupDoc>>()
        val mediaCols = columns(db, "message_media")
        if (mediaCols.containsAll(listOf("message_row_id", "file_path", "mime_type"))) {
            val nameCol = if ("media_name" in mediaCols) "mm.media_name" else "NULL"
            db.rawQuery(
                "SELECT m.chat_row_id, m.from_me, m.timestamp, m.sender_jid_row_id, mm.file_path, mm.mime_type, $nameCol " +
                    "FROM message_media mm JOIN message m ON m._id = mm.message_row_id " +
                    "WHERE mm.file_path IS NOT NULL AND mm.file_path <> '' " +
                    "AND COALESCE(mm.mime_type, '') NOT LIKE 'image/%' AND COALESCE(mm.mime_type, '') NOT LIKE 'video/%' " +
                    "AND COALESCE(mm.mime_type, '') NOT LIKE 'audio/%' ORDER BY m.chat_row_id, m.timestamp",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val chat = chats[c.getLong(0)] ?: continue
                    val relPath = relativeMediaPath(c.getString(4)) ?: continue
                    val name = c.getString(6)?.takeIf { it.isNotBlank() } ?: relPath.substringAfterLast('/')
                    docsByChat.getOrPut(c.getLong(0)) { mutableListOf() } += BackupDoc(
                        timestamp = millis(c.getLong(2)),
                        sender = senderOf(chat, c.getInt(1) == 1, if (c.isNull(3)) null else c.getLong(3)),
                        relPath = relPath,
                        name = name,
                        mime = c.getString(5),
                    )
                }
            }
        }

        return (byChat.keys + docsByChat.keys).distinct().map { id ->
            BackupChat(id.toString(), chats.getValue(id).name, byChat[id].orEmpty(), docsByChat[id].orEmpty())
        }
    }

    /** "Media/WhatsApp Documents/x.pdf", or an absolute path containing "/WhatsApp/Media/" → relative. */
    private fun relativeMediaPath(path: String): String? {
        val p = path.replace('\\', '/')
        val i = p.indexOf("Media/")
        return if (i >= 0) p.substring(i) else null
    }

    private val nameCache = HashMap<String, String>()

    private fun personName(user: String, server: String): String = nameCache.getOrPut("$user@$server") {
        if (server == "s.whatsapp.net") nameForPhone("+$user") ?: formatPhone(user)
        else "Unknown (…${user.takeLast(4)})"  // LID with no phone mapping
    }

    private fun formatPhone(user: String) = "+$user"

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    private fun requireColumns(db: SQLiteDatabase, table: String, vararg cols: String) {
        val have = columns(db, table)
        val missing = cols.filterNot { it in have }
        if (missing.isNotEmpty()) {
            throw BackupException(
                "This WhatsApp version's backup format isn't supported yet " +
                    "(table '$table' missing ${missing.joinToString()})."
            )
        }
    }
}
