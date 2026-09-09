package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains

private class CalendarEvent : EntSchema("calendar_events", clientName = "calendarEvents") {
    override fun id() = EntId.long()
    val startsOn by date("starts_on").default(LocalDate.of(2024, 2, 29))
    val closedOn by date("closed_on").nullable()
    val byStartsOn = index("idx_events_starts_on", startsOn)
    val byClosedOn = index("idx_events_closed_on", closedOn)
}

class DateCodegenTest {
    private val files: Map<String, String> by lazy {
        val schema = CalendarEvent()
        schema.finalize(mapOf(CalendarEvent::class to schema))
        EntGenerator("com.example.ent", viewer = true)
            .generate(listOf(SchemaInput(schema)))
            .associate { it.name to it.toString().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun `date properties and column references use LocalDate`() {
        val entity = files.getValue("CalendarEvent")
        assertContains(entity, "val startsOn: LocalDate")
        assertContains(entity, "val closedOn: LocalDate? = null")
        assertContains(entity, "ComparableColumn<CalendarEvent, LocalDate>(\"starts_on\")")
        assertContains(entity, "NullableComparableColumn<CalendarEvent, LocalDate>(\"closed_on\")")
        assertContains(entity, "FieldType.DATE")
        assertContains(entity, "row[\"starts_on\"] as LocalDate")
        assertContains(entity, "row[\"closed_on\"] as LocalDate?")
    }

    @Test
    fun `fixed date defaults generate a typed constructor`() {
        val converter = files.getValue("CalendarEventCreateConverter")
        assertContains(converter, "LocalDate.of(2_024, 2, 29)")
        assertContains(files.getValue("CalendarEventCreateDraft"), "var startsOn: LocalDate? = null")
        assertContains(files.getValue("CalendarEventUpdateDraft"), "var closedOn: LocalDate? = null")
    }

    @Test
    fun `date indexes expose equality and range helpers`() {
        val indexes = files.getValue("CalendarEventIndexes")
        assertContains(indexes, "startsOn(startsOn: LocalDate)")
        assertContains(indexes, "closedOn(closedOn: LocalDate)")
        assertContains(indexes, "startsOn(block: IndexRangeBuilder<CalendarEvent, LocalDate>")
        assertContains(indexes, "closedOn(block: IndexRangeBuilder<CalendarEvent, LocalDate>")
    }

    @Test
    fun `viewer metadata exposes dates as filterable and orderable`() {
        val viewer = files.getValue("CalendarEventViewerEntity")
        assertContains(
            viewer,
            """EntViewerColumn(name = "starts_on", type = FieldType.DATE, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true, entType = "LocalDate")""",
        )
    }
}
