package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import java.time.LocalDate

/** Date-only fixture with required, nullable, unique, and fixed-default fields. */
class CalendarEvent : EntSchema("calendar_events", clientName = "calendarEvents") {
    override fun id() = EntId.long()

    val name by string("name")
    val startsOn by date("starts_on").unique()
    val endsOn by date("ends_on").nullable()
    val dueOn by date("due_on").default(LocalDate.of(2026, 1, 1))
    val reviewOn by date("review_on").nullable().default(LocalDate.of(2026, 2, 1))

    val byReviewOn = index("idx_calendar_events_review_on", reviewOn)
}
