package entkt.integrationtest

import entkt.integrationtest.ent.CalendarEvent
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Predicate
import entkt.query.isNotNull
import entkt.query.isNull
import entkt.runtime.query.AggregateFunction
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import java.sql.Connection
import java.time.LocalDate
import java.util.TimeZone
import javax.sql.DataSource
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DateFieldIntegrationTest : PostgresTestBase() {
    private val leapDay = LocalDate.of(2024, 2, 29)

    @Test
    fun `generated create read and update round-trip LocalDate and SQL null`() {
        val client = EntClient(resetAndDriver())
        val created = client.calendarEvents.create {
            name = "Leap day"
            startsOn = leapDay
            endsOn = leapDay.plusDays(1)
        }.saveAndLoad(testViewerContext).getOrThrow()

        val actualStartsOn: LocalDate = created.startsOn
        val actualEndsOn: LocalDate? = created.endsOn
        assertEquals(leapDay, actualStartsOn)
        assertEquals(leapDay.plusDays(1), actualEndsOn)
        assertEquals(created, client.calendarEvents.findById(testViewerContext, created.id).getOrThrow())

        val updated = client.calendarEvents.update(created.id) {
            startsOn = leapDay.plusYears(1)
            endsOn = null
        }.saveAndLoad(testViewerContext).getOrThrow()
        assertEquals(leapDay.plusYears(1), updated.startsOn)
        assertNull(updated.endsOn)
        assertEquals(updated, client.calendarEvents.findById(testViewerContext, created.id).getOrThrow())
    }

    @Test
    fun `fixed defaults distinguish omitted fields from explicit null and survive unrelated updates`() {
        val client = EntClient(resetAndDriver())
        val defaulted = client.calendarEvents.create {
            name = "Defaults"
            startsOn = leapDay
        }.saveAndLoad(testViewerContext).getOrThrow()
        assertEquals(LocalDate.of(2026, 1, 1), defaulted.dueOn)
        assertEquals(LocalDate.of(2026, 2, 1), defaulted.reviewOn)
        assertNull(defaulted.endsOn)

        val explicit = client.calendarEvents.create {
            name = "Explicit values"
            startsOn = leapDay.plusDays(1)
            dueOn = leapDay
            reviewOn = null
        }.saveAndLoad(testViewerContext).getOrThrow()
        assertEquals(leapDay, explicit.dueOn)
        assertNull(explicit.reviewOn)

        val renamed = client.calendarEvents.update(explicit.id) { name = "Renamed" }
            .saveAndLoad(testViewerContext).getOrThrow()
        assertEquals(leapDay, renamed.dueOn)
        assertNull(renamed.reviewOn, "an unrelated update must not reapply the nullable default")

        val cleared = client.calendarEvents.update(defaulted.id) { reviewOn = null }
            .saveAndLoad(testViewerContext).getOrThrow()
        assertNull(cleared.reviewOn)
        assertEquals(cleared, client.calendarEvents.findById(testViewerContext, defaulted.id).getOrThrow())
    }

    @Test
    fun `required dates reject omission and explicit null even when a default exists`() {
        val client = EntClient(resetAndDriver())
        val missing = client.calendarEvents.create { name = "Missing date" }.save(testViewerContext)
        val missingError = assertIs<EntValidationException>(assertIs<MutationResult.Failed>(missing).exception)
        assertEquals("startsOn", missingError.violations.single().field)

        val nullDefault = client.calendarEvents.create {
            name = "Null default"
            startsOn = leapDay
            dueOn = null
        }.save(testViewerContext)
        val nullError = assertIs<EntValidationException>(assertIs<MutationResult.Failed>(nullDefault).exception)
        assertEquals("dueOn", nullError.violations.single().field)
        assertEquals(0, client.calendarEvents.query().all(testViewerContext).getOrThrow().size)
    }

    @Test
    fun `date equality membership ranges ordering and index helpers use calendar values`() {
        val client = EntClient(resetAndDriver())
        val dates = listOf(leapDay.minusDays(1), leapDay, leapDay.plusDays(1))
        val rows = client.calendarEvents.createMany(testViewerContext,
            { name = "Last"; startsOn = dates[2]; reviewOn = null },
            { name = "First"; startsOn = dates[0]; reviewOn = dates[0] },
            { name = "Middle"; startsOn = dates[1]; reviewOn = dates[1] },
        ).getOrThrow()
        assertEquals(listOf(dates[2], dates[0], dates[1]), rows.map { it.startsOn })

        fun matching(vararg predicates: Predicate<CalendarEvent>): List<LocalDate> =
            client.calendarEvents.query {
                predicates.forEach { where(it) }
                orderBy(CalendarEvent.startsOn.asc())
            }.all(testViewerContext).getOrThrow().map { it.startsOn }

        assertEquals(dates, matching())
        assertEquals(listOf(leapDay), matching(CalendarEvent.startsOn eq leapDay))
        assertEquals(listOf(dates[0], dates[2]), matching(CalendarEvent.startsOn neq leapDay))
        assertEquals(listOf(dates[0], dates[2]), matching(CalendarEvent.startsOn `in` listOf(dates[2], dates[0])))
        assertEquals(listOf(leapDay), matching(CalendarEvent.startsOn notIn listOf(dates[0], dates[2])))
        assertEquals(listOf(dates[2]), matching(CalendarEvent.startsOn gt leapDay))
        assertEquals(listOf(dates[0]), matching(CalendarEvent.startsOn lt leapDay))
        assertEquals(listOf(leapDay), matching(CalendarEvent.startsOn gte leapDay, CalendarEvent.startsOn lte leapDay))
        assertEquals(listOf(dates[2]), matching(CalendarEvent.reviewOn.isNull()))
        assertEquals(dates.take(2), matching(CalendarEvent.reviewOn.isNotNull()))
        assertEquals(
            dates.reversed(),
            client.calendarEvents.query { orderBy(CalendarEvent.startsOn.desc()) }
                .all(testViewerContext).getOrThrow().map { it.startsOn },
        )

        val indexed = assertNotNull(client.calendarEvents.indexes.startsOn(leapDay).find(testViewerContext).getOrThrow())
        assertEquals(leapDay, indexed.startsOn)
        assertNull(client.calendarEvents.indexes.startsOn(leapDay.plusYears(1)).find(testViewerContext).getOrThrow())
        assertEquals(
            dates.take(2),
            client.calendarEvents.indexes.reviewOn { gte(dates[0]); lte(leapDay) }
                .query { orderBy(CalendarEvent.startsOn.asc()) }
                .all(testViewerContext).getOrThrow().map { it.startsOn },
        )
    }

    @Test
    fun `a unique date field is enforced by PostgreSQL`() {
        val client = EntClient(resetAndDriver())
        client.calendarEvents.create { name = "First"; startsOn = leapDay }.save(testViewerContext).getOrThrow()
        val duplicate = client.calendarEvents.create { name = "Duplicate"; startsOn = leapDay }.save(testViewerContext)
        assertIs<EntConstraintViolationException>(assertIs<MutationResult.Failed>(duplicate).exception)
        assertEquals(1, client.calendarEvents.query().all(testViewerContext).getOrThrow().size)
    }

    @Test
    fun `date aggregates retain LocalDate values and nullable group keys`() {
        val driver = resetAndDriver()
        val client = EntClient(driver)
        client.calendarEvents.createMany(testViewerContext,
            { name = "First"; startsOn = leapDay; reviewOn = leapDay },
            { name = "Second"; startsOn = leapDay.plusDays(1); reviewOn = leapDay },
            { name = "Third"; startsOn = leapDay.plusDays(2); reviewOn = null },
        ).getOrThrow()

        assertEquals(
            leapDay,
            driver.aggregate(CalendarEvent.TABLE, AggregateFunction.MIN, "starts_on", emptyList(), null).single().value,
        )
        assertEquals(
            leapDay.plusDays(2),
            driver.aggregate(CalendarEvent.TABLE, AggregateFunction.MAX, "starts_on", emptyList(), null).single().value,
        )
        val groups = driver.aggregate(CalendarEvent.TABLE, AggregateFunction.COUNT, null, emptyList(), "review_on")
        assertEquals(mapOf<Any?, Any?>(leapDay to 2L, null to 1L), groups.associate { it.key to it.value })
    }

    @Test
    @ResourceLock(Resources.TIME_ZONE)
    fun `dates survive different JVM and database timezones including historical and DST dates`() {
        resetAndDriver()
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val writer = clientInDatabaseTimeZone("Pacific/Kiritimati")
            val dates = listOf(
                LocalDate.of(0, 1, 1),
                LocalDate.of(1500, 3, 1),
                leapDay,
                LocalDate.of(2024, 3, 10),
                LocalDate.of(2024, 11, 3),
                LocalDate.of(10000, 1, 1),
            )
            val created = dates.map { date ->
                writer.calendarEvents.create { name = date.toString(); startsOn = date }
                    .saveAndLoad(testViewerContext).getOrThrow()
            }
            assertEquals(dates, created.map { it.startsOn })

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val reader = clientInDatabaseTimeZone("America/Los_Angeles")
            assertEquals(
                dates,
                reader.calendarEvents.query { orderBy(CalendarEvent.startsOn.asc()) }
                    .all(testViewerContext).getOrThrow().map { it.startsOn },
            )
            for (row in created) {
                assertEquals(row, reader.calendarEvents.findById(testViewerContext, row.id).getOrThrow())
                val updated = reader.calendarEvents.update(row.id) { endsOn = row.startsOn.plusDays(1) }
                    .saveAndLoad(testViewerContext).getOrThrow()
                assertEquals(row.startsOn.plusDays(1), updated.endsOn)
            }
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    private fun clientInDatabaseTimeZone(zone: String): EntClient {
        val zonedDataSource = object : DataSource by dataSource {
            override fun getConnection(): Connection {
                val connection = dataSource.connection
                try {
                    connection.prepareStatement("SELECT set_config('TimeZone', ?, false)").use { statement ->
                        statement.setString(1, zone)
                        statement.executeQuery().use { rows ->
                            check(rows.next())
                            assertEquals(zone, rows.getString(1))
                        }
                    }
                    return connection
                } catch (error: Exception) {
                    connection.close()
                    throw error
                }
            }
        }
        return EntClient(PostgresDriver(zonedDataSource))
    }
}
