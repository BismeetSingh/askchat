package com.askchat

import com.askchat.data.ChatParser
import com.askchat.rag.RagEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ChatParserTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun androidTwelveHourWithNarrowNbsp() {
        val txt = """
            22/11/2026, 4:29 pm - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.
            15/11/2026, 9:05 pm - Bismeet: wifi password is
            Hunter2@2026
            15/11/2026, 9:06 pm - Rahul: thanks!
        """.trimIndent()
        val r = ChatParser.parse(txt, utc)
        assertEquals(2, r.messages.size)
        assertEquals("Bismeet", r.messages[0].sender)
        assertEquals("wifi password is\nHunter2@2026", r.messages[0].text)
        assertEquals(ts(2026, 11, 15, 21, 5), r.messages[0].timestamp)
        assertEquals(setOf("Bismeet", "Rahul"), r.senders)
    }

    @Test
    fun androidUsMonthFirst24h() {
        val txt = "11/22/26, 16:29 - Rahul: hi\n11/23/26, 08:01 - Me: yo"
        val r = ChatParser.parse(txt, utc)
        assertEquals(ts(2026, 11, 22, 16, 29), r.messages[0].timestamp)
    }

    @Test
    fun iosBracketFormatWithLrm() {
        val txt = "[22/11/26, 4:29:10 PM] Rahul: hello\n‎[22/11/26, 4:30:00 PM] Rahul: ‎image omitted"
        val r = ChatParser.parse(txt, utc)
        assertEquals(2, r.messages.size)
        assertEquals(ts(2026, 11, 22, 16, 29, 10), r.messages[0].timestamp)
    }

    @Test
    fun midnightAndNoon() {
        val txt = "01/02/2026, 12:15 am - A: x\n13/02/2026, 12:15 pm - A: y"
        val r = ChatParser.parse(txt, utc)
        assertEquals(ts(2026, 2, 1, 0, 15), r.messages[0].timestamp)
        assertEquals(ts(2026, 2, 13, 12, 15), r.messages[1].timestamp)
    }

    @Test
    fun chatNameFromFile() {
        assertEquals("Rahul", ChatParser.chatNameFromFileName("WhatsApp Chat with Rahul.txt"))
        assertEquals("Rahul Sharma", ChatParser.chatNameFromFileName("WhatsApp Chat - Rahul Sharma.zip"))
        assertEquals("Family", ChatParser.chatNameFromFileName("WhatsApp Chat with Family (1).txt"))
        assertNull(ChatParser.chatNameFromFileName("notes.txt"))
    }

    @Test
    fun ftsQueryBuilding() {
        assertEquals("password* OR pwd* OR \"wifi pass\"", RagEngine.ftsQuery(listOf("Password", "pwd", "wifi pass", "!!")))
        assertNull(RagEngine.ftsQuery(emptyList()))
    }
}
