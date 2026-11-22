package com.askchat

import com.askchat.rag.Schemas
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemasTest {

    @Test
    fun parsesPlan() {
        val p = Schemas.parsePlan(
            """{"contacts":["Rahul"],"direction":"me","startDate":"2026-11-13","endDate":"2026-11-22","keywords":["password","pwd"]}"""
        )
        assertEquals(listOf("Rahul"), p.contacts)
        assertEquals("me", p.direction)
        assertEquals("2026-11-13", p.startDate)
        assertEquals(listOf("password", "pwd"), p.keywords)
    }

    @Test
    fun parsesAnswer() {
        val a = Schemas.parseAnswer("""{"answer":"Hunter2@2026","citedMessageIds":[42, 43]}""")
        assertEquals("Hunter2@2026", a.answer)
        assertEquals(listOf(42L, 43L), a.citedMessageIds)
    }

    @Test
    fun schemasRequireEveryProperty() {
        // Strict structured outputs (OpenAI) need every property listed in "required".
        for (s in listOf(Schemas.PLAN, Schemas.ANSWER)) {
            @Suppress("UNCHECKED_CAST")
            assertEquals((s["properties"] as Map<String, Any>).keys.toList(), s["required"])
            assertEquals(false, s["additionalProperties"])
        }
    }
}
