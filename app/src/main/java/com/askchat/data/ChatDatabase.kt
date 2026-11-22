package com.askchat.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatSummary(val id: Long, val name: String, val messageCount: Int, val firstTs: Long, val lastTs: Long)

data class MessageRow(val id: Long, val chatId: Long, val chatName: String, val ts: Long, val sender: String, val text: String)

/** Filters applied to every retrieval query. Null = no constraint. */
data class MessageFilter(
    val chatIds: List<Long>? = null,
    val senderEquals: String? = null,
    val senderNotEquals: String? = null,
    val senderLike: String? = null,
    val fromTs: Long? = null,
    val toTs: Long? = null,
    /** Document text chunks (kind=1) are bulky; include them only for keyword search. */
    val includeDocChunks: Boolean = true,
)

/**
 * Local message store. Plain SQLite + FTS4 full-text index; nothing here ever leaves the device.
 */
class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "chats.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, imported_at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, " +
                "ts INTEGER NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, kind INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_messages_chat_ts ON messages(chat_id, ts)")
        db.execSQL("CREATE INDEX idx_messages_ts ON messages(ts)")
        db.execSQL("CREATE VIRTUAL TABLE messages_fts USING fts4(text, tokenize=unicode61)")
        createDocCache(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
            createDocCache(db)
        }
    }

    /** Extracted document text, keyed by file path + size, so re-syncs don't re-parse every PDF. */
    private fun createDocCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS doc_text (path TEXT NOT NULL, size INTEGER NOT NULL, text TEXT, PRIMARY KEY(path, size))")
    }

    /** Returns cached text: null = not cached; "" = cached as unreadable. */
    fun cachedDocText(path: String, size: Long): String? =
        readableDatabase.rawQuery("SELECT COALESCE(text, '') FROM doc_text WHERE path = ? AND size = ?", arrayOf(path, size.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun cacheDocText(path: String, size: Long, text: String?) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO doc_text(path, size, text) VALUES(?, ?, ?)", arrayOf(path, size, text))
    }

    /** Runs [block] in one transaction; use with [replaceChatStreaming] for large syncs. */
    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** Replaces a chat from an already time-ordered sequence; items are produced lazily (low memory). */
    fun replaceChatStreaming(name: String, orderedMessages: Sequence<ParsedMessage>): Long =
        insertChat(writableDatabase, name, orderedMessages)

    /** Imports a chat, replacing any previous import with the same name (exports contain full history). */
    fun replaceChat(name: String, messages: List<ParsedMessage>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = replaceChatInternal(db, name, messages)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /** Replaces many chats in one transaction (backup sync). */
    fun replaceChats(chats: Map<String, List<ParsedMessage>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((name, messages) in chats) replaceChatInternal(db, name, messages)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun replaceChatInternal(db: SQLiteDatabase, name: String, messages: List<ParsedMessage>): Long =
        insertChat(db, name, messages.sortedBy { it.timestamp }.asSequence())

    private fun insertChat(db: SQLiteDatabase, name: String, ordered: Sequence<ParsedMessage>): Long {
        run {
            existingChatId(db, name)?.let { deleteChatInternal(db, it) }
            val chatId = db.compileStatement("INSERT INTO chats(name, imported_at) VALUES(?, ?)").run {
                bindString(1, name); bindLong(2, System.currentTimeMillis()); executeInsert()
            }
            val insMsg = db.compileStatement("INSERT INTO messages(chat_id, ts, sender, text, kind) VALUES(?, ?, ?, ?, ?)")
            val insFts = db.compileStatement("INSERT INTO messages_fts(docid, text) VALUES(?, ?)")
            for (m in ordered) {
                insMsg.clearBindings()
                insMsg.bindLong(1, chatId); insMsg.bindLong(2, m.timestamp)
                insMsg.bindString(3, m.sender); insMsg.bindString(4, m.text); insMsg.bindLong(5, m.kind.toLong())
                val id = insMsg.executeInsert()
                insFts.clearBindings()
                insFts.bindLong(1, id); insFts.bindString(2, m.text)
                insFts.executeInsert()
            }
            return chatId
        }
    }

    fun deleteChat(chatId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteChatInternal(db, chatId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun existingChatId(db: SQLiteDatabase, name: String): Long? =
        db.rawQuery("SELECT id FROM chats WHERE name = ?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun deleteChatInternal(db: SQLiteDatabase, chatId: Long) {
        db.execSQL("DELETE FROM messages_fts WHERE docid IN (SELECT id FROM messages WHERE chat_id = ?)", arrayOf(chatId))
        db.execSQL("DELETE FROM messages WHERE chat_id = ?", arrayOf(chatId))
        db.execSQL("DELETE FROM chats WHERE id = ?", arrayOf(chatId))
    }

    fun chats(): List<ChatSummary> = readableDatabase.rawQuery(
        "SELECT c.id, c.name, COUNT(m.id), COALESCE(MIN(m.ts), 0), COALESCE(MAX(m.ts), 0) " +
            "FROM chats c LEFT JOIN messages m ON m.chat_id = c.id GROUP BY c.id ORDER BY MAX(m.ts) DESC",
        null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(ChatSummary(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))) }
    }

    /** Sender names in a given chat, most active first. */
    fun sendersInChat(chatId: Long): List<String> = readableDatabase.rawQuery(
        "SELECT sender FROM messages WHERE chat_id = ? GROUP BY sender ORDER BY COUNT(*) DESC",
        arrayOf(chatId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Senders across all chats with the number of chats they appear in — "you" appear in every chat. */
    fun sendersByChatCount(): List<Pair<String, Int>> = readableDatabase.rawQuery(
        "SELECT sender, COUNT(DISTINCT chat_id) AS n FROM messages GROUP BY sender ORDER BY n DESC, COUNT(*) DESC LIMIT 50",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

    fun count(filter: MessageFilter): Int {
        val (where, args) = whereClause(filter)
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM messages m WHERE $where", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Full-text search restricted by [filter]. */
    fun search(ftsQuery: String, filter: MessageFilter, limit: Int): List<MessageRow> {
        val (where, args) = whereClause(filter)
        return readableDatabase.rawQuery(
            "SELECT $COLS FROM messages m JOIN chats c ON c.id = m.chat_id " +
                "JOIN messages_fts ON messages_fts.docid = m.id " +
                "WHERE messages_fts MATCH ? AND $where ORDER BY m.ts DESC LIMIT $limit",
            arrayOf(ftsQuery) + args,
        ).use(::readRows)
    }

    /** All messages matching [filter], newest first. */
    fun window(filter: MessageFilter, limit: Int): List<MessageRow> {
        val (where, args) = whereClause(filter)
        return readableDatabase.rawQuery(
            "SELECT $COLS FROM messages m JOIN chats c ON c.id = m.chat_id WHERE $where ORDER BY m.ts DESC LIMIT $limit",
            args,
        ).use(::readRows)
    }

    /** Messages surrounding [row] in the same chat (ids are sequential within an import). */
    fun context(row: MessageRow, radius: Int): List<MessageRow> = readableDatabase.rawQuery(
        "SELECT $COLS FROM messages m JOIN chats c ON c.id = m.chat_id " +
            "WHERE m.chat_id = ? AND m.id BETWEEN ? AND ? ORDER BY m.id",
        arrayOf(row.chatId.toString(), (row.id - radius).toString(), (row.id + radius).toString()),
    ).use(::readRows)

    private fun whereClause(f: MessageFilter): Pair<String, Array<String>> {
        val parts = mutableListOf("1=1")
        val args = mutableListOf<String>()
        f.chatIds?.let { ids ->
            parts += if (ids.isEmpty()) "0=1" else "m.chat_id IN (${ids.joinToString(",")})"
        }
        f.senderEquals?.let { parts += "m.sender = ?"; args += it }
        f.senderNotEquals?.let { parts += "m.sender <> ?"; args += it }
        f.senderLike?.let { parts += "m.sender LIKE ?"; args += "%$it%" }
        f.fromTs?.let { parts += "m.ts >= ?"; args += it.toString() }
        f.toTs?.let { parts += "m.ts < ?"; args += it.toString() }
        if (!f.includeDocChunks) parts += "m.kind = 0"
        return parts.joinToString(" AND ") to args.toTypedArray()
    }

    private fun readRows(c: Cursor): List<MessageRow> = buildList {
        while (c.moveToNext()) {
            add(MessageRow(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5)))
        }
    }

    companion object {
        private const val COLS = "m.id, m.chat_id, c.name, m.ts, m.sender, m.text"
    }
}
