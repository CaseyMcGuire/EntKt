package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.IndexMetadata
import entkt.schema.FieldType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `CREATE INDEX IF NOT EXISTS` skips on a name match without comparing
 * the definition, so auto-DDL reconciles each declared index against
 * `pg_index` before trusting the name — the index-side analogue of
 * [PostgresForeignKeyGuardTest]'s constraint reconciliation. Absent →
 * create; present and equivalent → no-op (registration is replayed
 * across driver instances and test runs); present and different → fail
 * with what was wanted and what is there.
 */
class PostgresIndexGuardTest {

    private fun docsSchema(indexes: List<IndexMetadata>, uniqueStatus: Boolean = false) = EntitySchema(
        table = "index_guard_docs",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("status", FieldType.STRING, nullable = false, unique = uniqueStatus),
            ColumnMetadata("category", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
        indexes = indexes,
    )

    private val statusCategory = IndexMetadata(
        columns = listOf("status", "category"),
        unique = false,
        name = "idx_guard_status_category",
    )

    @BeforeTest
    fun resetTable() {
        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP TABLE IF EXISTS \"index_guard_docs\"") }
        }
    }

    private fun freshDriver() = PostgresDriver(SharedPostgres.dataSource, autoDdl = true)

    @Test
    fun `re-registering the same indexes is idempotent across driver instances`() {
        freshDriver().register(docsSchema(listOf(statusCategory), uniqueStatus = true))
        // A second driver replays the DDL from scratch — both the
        // declared composite index and the derived single-column
        // unique must reconcile as equivalent, not error.
        freshDriver().register(docsSchema(listOf(statusCategory), uniqueStatus = true))
    }

    @Test
    fun `partial index predicates survive the deparse round-trip`() {
        // pg_get_expr renders this back as ((status = 'active'::text));
        // normalizeWhere must bring the two forms together or every
        // restart would fail registration.
        val partial = statusCategory.copy(
            name = "idx_guard_active_status",
            columns = listOf("status"),
            where = "status = 'active'",
        )
        freshDriver().register(docsSchema(listOf(partial)))
        freshDriver().register(docsSchema(listOf(partial)))
    }

    @Test
    fun `flipping a same-named index to unique fails instead of silently keeping the old one`() {
        freshDriver().register(docsSchema(listOf(statusCategory)))

        val e = assertFailsWith<IllegalStateException> {
            freshDriver().register(docsSchema(listOf(statusCategory.copy(unique = true))))
        }
        assertTrue("schema wants" in (e.message ?: ""), "mismatch must show both sides; was: ${e.message}")
        assertTrue("database has" in (e.message ?: ""), "mismatch must show both sides; was: ${e.message}")
        assertTrue("idx_guard_status_category" in (e.message ?: ""), "names the index; was: ${e.message}")
    }

    @Test
    fun `changing a same-named index's columns fails instead of silently keeping the old one`() {
        freshDriver().register(docsSchema(listOf(statusCategory)))

        assertFailsWith<IllegalStateException> {
            freshDriver().register(docsSchema(listOf(statusCategory.copy(columns = listOf("category", "status")))))
        }
    }

    @Test
    fun `adding a predicate to a same-named index fails instead of silently keeping the total index`() {
        freshDriver().register(docsSchema(listOf(statusCategory)))

        assertFailsWith<IllegalStateException> {
            freshDriver().register(docsSchema(listOf(statusCategory.copy(where = "status = 'active'"))))
        }
    }
}
