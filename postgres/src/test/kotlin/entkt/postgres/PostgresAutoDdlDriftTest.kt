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
 * `CREATE TABLE IF NOT EXISTS` is a silent no-op on a pre-existing
 * table whatever its shape, so auto-DDL reconciles the table *body*
 * (columns, types, nullability, defaults, primary key) against the
 * live catalog before trusting the name — the table-side analogue of
 * [PostgresIndexGuardTest] / [PostgresForeignKeyGuardTest]. Absent →
 * create; present and equivalent → no-op; present and different → fail
 * naming the drift, before anything is cached (a failed batch stays
 * retryable).
 */
class PostgresAutoDdlDriftTest {

    private val table = "auto_ddl_drift_docs"

    private fun docsSchema(indexes: List<IndexMetadata> = emptyList()) = EntitySchema(
        table = table,
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("title", FieldType.STRING, nullable = false),
            ColumnMetadata("note", FieldType.STRING, nullable = true),
        ),
        edges = emptyMap(),
        indexes = indexes,
    )

    @BeforeTest
    fun resetTable() {
        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP TABLE IF EXISTS \"$table\"") }
        }
    }

    private fun freshDriver() = PostgresDriver(SharedPostgres.dataSource, autoDdl = true)

    private fun createRaw(ddl: String) {
        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(ddl) }
        }
    }

    @Test
    fun `an equivalent pre-existing table registers as a no-op`() {
        createRaw("""CREATE TABLE "$table" (id bigserial PRIMARY KEY, title text NOT NULL, note text)""")

        freshDriver().register(docsSchema())

        // Registration succeeded and the table is usable.
        val driver = freshDriver()
        driver.register(docsSchema())
        driver.insert(table, mapOf("title" to "t"))
    }

    @Test
    fun `a pre-existing table missing a declared column fails registration`() {
        createRaw("""CREATE TABLE "$table" (id bigserial PRIMARY KEY, title text NOT NULL)""")

        val ex = assertFailsWith<IllegalStateException> { freshDriver().register(docsSchema()) }
        assertTrue("note" in ex.message!!, ex.message)
        assertTrue("migration" in ex.message!!, ex.message)
    }

    @Test
    fun `a pre-existing table with a wrong column type fails registration`() {
        createRaw("""CREATE TABLE "$table" (id bigserial PRIMARY KEY, title integer NOT NULL, note text)""")

        val ex = assertFailsWith<IllegalStateException> { freshDriver().register(docsSchema()) }
        assertTrue("title" in ex.message!!, ex.message)
    }

    @Test
    fun `a pre-existing table with drifted nullability fails registration`() {
        // title is declared NOT NULL but the live column is nullable —
        // the declared constraint would be silently unenforced.
        createRaw("""CREATE TABLE "$table" (id bigserial PRIMARY KEY, title text, note text)""")

        val ex = assertFailsWith<IllegalStateException> { freshDriver().register(docsSchema()) }
        assertTrue("title" in ex.message!!, ex.message)
    }

    @Test
    fun `a pre-existing table with an extra column fails registration`() {
        createRaw(
            """CREATE TABLE "$table" (id bigserial PRIMARY KEY, title text NOT NULL, note text, extra text)""",
        )

        val ex = assertFailsWith<IllegalStateException> { freshDriver().register(docsSchema()) }
        assertTrue("extra" in ex.message!!, ex.message)
    }

    @Test
    fun `a pre-existing table with a different primary key fails registration`() {
        createRaw("""CREATE TABLE "$table" (id bigserial, title text NOT NULL PRIMARY KEY, note text)""")

        assertFailsWith<IllegalStateException> { freshDriver().register(docsSchema()) }
    }

    @Test
    fun `a failed registration caches nothing and stays retryable`() {
        createRaw("""CREATE TABLE "$table" (id bigserial PRIMARY KEY, title text NOT NULL)""")
        val driver = freshDriver()
        assertFailsWith<IllegalStateException> { driver.register(docsSchema()) }

        // Same driver instance: after the drifted table is dropped, the
        // very same registration succeeds — nothing was cached by the
        // failed attempt.
        resetTable()
        driver.register(docsSchema())
        driver.insert(table, mapOf("title" to "t"))
    }

    @Test
    fun `a matching body with a missing index still registers and creates the index`() {
        // Index reconciliation belongs to ensureIndex (absent → create),
        // not the body guard — a compatible table lacking a declared
        // index must not be reported as drift.
        createRaw("""CREATE TABLE "$table" (id bigserial PRIMARY KEY, title text NOT NULL, note text)""")
        val idx = IndexMetadata(columns = listOf("title"), unique = false, name = "idx_drift_docs_title")

        freshDriver().register(docsSchema(indexes = listOf(idx)))

        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT 1 FROM pg_indexes WHERE tablename = '$table' AND indexname = 'idx_drift_docs_title'",
                ).use { rs -> assertTrue(rs.next(), "declared index should have been created") }
            }
        }
    }
}
