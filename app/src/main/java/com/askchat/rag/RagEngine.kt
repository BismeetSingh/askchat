package com.askchat.rag

import com.askchat.data.ChatDatabase
import com.askchat.data.ChatSummary
import com.askchat.data.MessageFilter
import com.askchat.data.MessageRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class RagResult(
    val answer: String,
    val cited: List<MessageRow>,
    val plan: SearchPlan,
    /** How many messages were sent to Claude — shown in the UI for transparency. */
    val messagesSent: Int,
)

/**
 * Retrieval-augmented answering over the local message store.
 *
 * Retrieval is hybrid and fully local:
 *  - structured filters (chat, sender, date range) from Claude's [SearchPlan]
 *  - FTS keyword hits plus a few surrounding messages for context
 *  - when the filtered window is small, every message in it — this is what catches a password
 *    sent with no surrounding keyword ("Hunter2@2026"), which keyword/semantic search would miss.
 */
class RagEngine(private val db: ChatDatabase) {

    fun ask(question: String, llm: LlmService, selfName: String, zone: ZoneId = ZoneId.systemDefault()): RagResult {
        val now = ZonedDateTime.now(zone)
        val chats = db.chats()

        val plan = llm.plan(question, now, chats.map { it.name }, selfName)
        val candidates = retrieve(question, plan, chats, selfName, zone)

        if (candidates.isEmpty()) {
            return RagResult(
                "I couldn't find any messages matching that. Check that the right chat is imported, or try a wider time range.",
                emptyList(), plan, 0,
            )
        }

        val answer = llm.answer(question, now, selfName, format(candidates, zone))
        val byId = candidates.associateBy { it.id }
        return RagResult(answer.answer, answer.citedMessageIds.mapNotNull { byId[it] }, plan, candidates.size)
    }

    internal fun retrieve(
        question: String,
        plan: SearchPlan,
        chats: List<ChatSummary>,
        selfName: String,
        zone: ZoneId,
    ): List<MessageRow> {
        val base = buildFilter(plan, chats, selfName, zone)
        val result = LinkedHashMap<Long, MessageRow>()

        // 1. Small enough window (e.g. one chat, one week)? Send all of it — highest recall.
        //    Document chunks are excluded here — they come in only via keyword hits below.
        val messagesOnly = base.copy(includeDocChunks = false)
        val windowIsNarrow = base.chatIds != null || base.senderLike != null || base.fromTs != null
        if (windowIsNarrow && db.count(messagesOnly) <= FULL_WINDOW_LIMIT) {
            db.window(messagesOnly, FULL_WINDOW_LIMIT).forEach { result[it.id] = it }
        }

        // 2. Keyword hits within the filters, with surrounding context.
        val fts = ftsQuery(plan.keywords)
        if (fts != null) {
            for (hit in db.search(fts, base, KEYWORD_HITS)) {
                db.context(hit, CONTEXT_RADIUS).forEach { result.putIfAbsent(it.id, it) }
            }
        }

        // 3. Window too big and no keyword hits: most recent messages in the window.
        if (result.isEmpty() && windowIsNarrow) {
            db.window(messagesOnly, RECENT_FALLBACK).forEach { result[it.id] = it }
        }

        // 4. Filters matched nothing: drop the direction/date constraints and retry keywords,
        //    then the raw question words. Better a loose hit than nothing.
        if (result.isEmpty()) {
            val loose = MessageFilter(chatIds = base.chatIds, senderLike = base.senderLike)
            val q = fts ?: ftsQuery(question.split(Regex("\\s+")).filter { it.length > 3 })
            if (q != null) {
                for (hit in db.search(q, loose, KEYWORD_HITS)) {
                    db.context(hit, CONTEXT_RADIUS).forEach { result.putIfAbsent(it.id, it) }
                }
            }
        }

        return result.values.sortedWith(compareBy({ it.chatId }, { it.ts }, { it.id })).take(MAX_CONTEXT_MESSAGES)
    }

    private fun buildFilter(plan: SearchPlan, chats: List<ChatSummary>, selfName: String, zone: ZoneId): MessageFilter {
        // Contacts → chats whose name matches (either direction, case-insensitive).
        val matchedChatIds = plan.contacts.flatMap { contact ->
            val c = contact.trim().lowercase()
            if (c.isEmpty()) emptyList()
            else chats.filter {
                val n = it.name.lowercase()
                n.contains(c) || c.contains(n) || n.split(' ').any { w -> w.length > 2 && w == c.split(' ').first() }
            }.map { it.id }
        }.distinct()

        // A contact that isn't a chat name may be a member of a group chat → match by sender instead.
        val senderLike = if (plan.contacts.isNotEmpty() && matchedChatIds.isEmpty() && plan.direction != "me") {
            plan.contacts.first().trim().takeIf { it.isNotEmpty() }
        } else null

        val self = selfName.takeIf { it.isNotBlank() }
        return MessageFilter(
            chatIds = matchedChatIds.takeIf { it.isNotEmpty() },
            senderEquals = if (plan.direction == "me") self else null,
            senderNotEquals = if (plan.direction == "them" && senderLike == null) self else null,
            senderLike = senderLike,
            fromTs = parseDate(plan.startDate)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            toTs = parseDate(plan.endDate)?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        )
    }

    private fun parseDate(s: String): LocalDate? =
        s.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    private fun format(rows: List<MessageRow>, zone: ZoneId): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm")
        return rows.joinToString("\n") { m ->
            val t = Instant.ofEpochMilli(m.ts).atZone(zone).format(fmt)
            "#${m.id} | $t | ${m.chatName} | ${m.sender}: ${m.text.replace("\n", " ⏎ ")}"
        }
    }

    companion object {
        private const val FULL_WINDOW_LIMIT = 400
        private const val KEYWORD_HITS = 40
        private const val CONTEXT_RADIUS = 3
        private const val RECENT_FALLBACK = 150
        private const val MAX_CONTEXT_MESSAGES = 600

        /** Builds an FTS4 query: single words become prefix terms, phrases are quoted; OR-joined. */
        internal fun ftsQuery(keywords: List<String>): String? {
            val terms = keywords.mapNotNull { kw ->
                val tokens = kw.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
                when {
                    tokens.isEmpty() -> null
                    tokens.size == 1 -> "${tokens[0]}*"
                    else -> "\"${tokens.joinToString(" ")}\""
                }
            }.distinct()
            return terms.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
        }
    }
}
