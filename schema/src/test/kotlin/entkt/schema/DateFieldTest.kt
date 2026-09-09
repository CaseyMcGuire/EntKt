package entkt.schema

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DateFieldTest {
    private class CalendarFields(scope: EntMixin.Scope) : EntMixin(scope) {
        val closedOn by date("closed_on").nullable()
        val byClosedOn = index("idx_closed_on", closedOn)
    }

    private class Event : EntSchema("events", clientName = "events") {
        override fun id() = EntId.long()
        val startsOn by date("starts_on")
            .default(LocalDate.of(2024, 2, 29)).unique().immutable().sensitive().comment("Start date")
        val calendar = include(::CalendarFields)
    }

    @Test
    fun `date fields preserve fixed defaults and common modifiers`() {
        val event = Event()
        event.finalize(mapOf(Event::class to event))
        val fields = event.fields().associateBy { it.name }

        val startsOn = fields.getValue("starts_on")
        assertEquals(FieldType.DATE, startsOn.type)
        assertEquals("startsOn", startsOn.declarationName)
        assertEquals(LocalDate.of(2024, 2, 29), startsOn.default)
        assertEquals("Start date", startsOn.comment)
        assertFalse(startsOn.nullable)
        assertTrue(startsOn.unique)
        assertTrue(startsOn.immutable)
        assertTrue(startsOn.sensitive)
        assertNull(startsOn.updateDefault)

        val closedOn = fields.getValue("closed_on")
        assertEquals(FieldType.DATE, closedOn.type)
        assertTrue(closedOn.nullable)
        assertNull(closedOn.default)
        assertNull(closedOn.updateDefault)
        assertEquals(listOf("closed_on"), event.indexes().single().fields)
    }

    @Test
    fun `date modifiers cannot change a finalized schema`() {
        val event = Event()
        event.finalize(mapOf(Event::class to event))

        assertFailsWith<IllegalStateException> { event.startsOn.default(LocalDate.of(2026, 1, 1)) }
        assertFailsWith<IllegalStateException> { event.calendar.closedOn.unique() }
    }
}
