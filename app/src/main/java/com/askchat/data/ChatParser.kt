package com.askchat.data

import java.time.LocalDateTime
import java.time.ZoneId

/** [kind]: 0 = chat message, 1 = chunk of text extracted from a shared document. */
data class ParsedMessage(val timestamp: Long, val sender: String, val text: String, val kind: Int = 0)

data class ParseResult(val messages: List<ParsedMessage>, val senders: Set<String>)

/**
 * Parses WhatsApp "Export chat" text files. Handles the common formats:
 *
 *   Android: 22/11/2026, 4:29 pm - Rahul: hello
 *            11/22/26, 16:29 - Rahul: hello
 *   iOS:     [22/11/26, 4:29:10 PM] Rahul: hello
 *
 * Lines that don't start with a timestamp are continuations of the previous message.
 * System lines ("Messages and calls are end-to-end encrypted…") are dropped.
 * Day/month order is inferred from the data (any first field > 12 ⇒ D/M/Y).
 */
object ChatParser {

    private const val DATE = """(\d{1,4})[/.\-](\d{1,2})[/.\-](\d{1,4}),?\s+(\d{1,2})[:.](\d{2})(?:[:.](\d{2}))?\s*([aApP]\.?\s?[mM]\.?)?"""
    private val ANDROID = Regex("""^$DATE\s+-\s+(.*)$""")
    private val IOS = Regex("""^\[$DATE]\s+(.*)$""")

    private const val MAX_SENDER_LEN = 80

    private class RawHeader(
        val a: Int, val b: Int, val c: Int,
        val hour: Int, val minute: Int, val second: Int,
        val ampm: String?,
        val rest: String,
    )

    private class RawMessage(val header: RawHeader, val sender: String?, val text: StringBuilder)

    fun parse(content: String, zone: ZoneId = ZoneId.systemDefault()): ParseResult {
        val raws = ArrayList<RawMessage>()
        for (rawLine in content.lineSequence()) {
            val line = normalize(rawLine)
            val header = matchHeader(line)
            if (header != null) {
                val sep = header.rest.indexOf(": ")
                if (sep in 1..MAX_SENDER_LEN) {
                    raws += RawMessage(
                        header,
                        header.rest.substring(0, sep).trim(),
                        StringBuilder(header.rest.substring(sep + 2)),
                    )
                } else {
                    // System message: keep a placeholder so continuation lines don't attach to the wrong message.
                    raws += RawMessage(header, null, StringBuilder())
                }
            } else if (raws.isNotEmpty() && raws.last().sender != null) {
                raws.last().text.append('\n').append(line)
            }
        }

        val order = detectOrder(raws.map { it.header })
        val messages = ArrayList<ParsedMessage>(raws.size)
        val senders = LinkedHashSet<String>()
        for (raw in raws) {
            val sender = raw.sender ?: continue
            val ts = toEpochMillis(raw.header, order, zone) ?: continue
            val text = raw.text.toString().trim()
            if (text.isEmpty()) continue
            messages += ParsedMessage(ts, sender, text)
            senders += sender
        }
        return ParseResult(messages, senders)
    }

    private fun normalize(line: String): String =
        line.replace(' ', ' ')   // narrow no-break space before am/pm
            .replace(' ', ' ')
            .replace("‎", "")      // LRM marks added by iOS
            .replace("‏", "")
            .replace("﻿", "")
            .trimEnd('\r')

    private fun matchHeader(line: String): RawHeader? {
        val m = ANDROID.matchEntire(line) ?: IOS.matchEntire(line) ?: return null
        val g = m.groupValues
        return RawHeader(
            a = g[1].toInt(), b = g[2].toInt(), c = g[3].toInt(),
            hour = g[4].toInt(), minute = g[5].toInt(),
            second = g[6].ifEmpty { "0" }.toInt(),
            ampm = g[7].ifEmpty { null },
            rest = g[8],
        )
    }

    private enum class Order { DMY, MDY, YMD }

    private fun detectOrder(headers: List<RawHeader>): Order {
        if (headers.any { it.a > 31 }) return Order.YMD
        if (headers.any { it.a > 12 }) return Order.DMY
        if (headers.any { it.b > 12 }) return Order.MDY
        val country = java.util.Locale.getDefault().country
        return if (country == "US") Order.MDY else Order.DMY
    }

    private fun toEpochMillis(h: RawHeader, order: Order, zone: ZoneId): Long? {
        val (day, month, yearRaw) = when (order) {
            Order.DMY -> Triple(h.a, h.b, h.c)
            Order.MDY -> Triple(h.b, h.a, h.c)
            Order.YMD -> Triple(h.c, h.b, h.a)
        }
        val year = if (yearRaw < 100) 2000 + yearRaw else yearRaw
        var hour = h.hour
        h.ampm?.lowercase()?.let { ap ->
            val pm = ap.startsWith("p")
            if (pm && hour < 12) hour += 12
            if (!pm && hour == 12) hour = 0
        }
        return try {
            LocalDateTime.of(year, month, day, hour, h.minute, h.second)
                .atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** "WhatsApp Chat with Rahul.txt" / "WhatsApp Chat - Rahul.zip" → "Rahul" */
    fun chatNameFromFileName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val m = Regex("""WhatsApp Chat (?:with|-)\s*(.+?)(?:\s*\(\d+\))?(?:\.txt|\.zip)?$""", RegexOption.IGNORE_CASE)
            .find(fileName.trim())
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
