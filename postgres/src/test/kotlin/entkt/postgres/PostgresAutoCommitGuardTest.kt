package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Root operations are only durable because they run in autocommit — a
 * pool configured with `autoCommit = false` would let each statement
 * execute in an implicit transaction that the pool's release path rolls
 * back *after* the caller saw the write succeed. The driver validates
 * the borrowed connection instead of assuming, so the misconfiguration
 * surfaces as a loud error, not silent data loss.
 *
 * [PostgresDriver.withTransaction] is deliberately exempt: it disables
 * autocommit itself and commits explicitly, so it works — and is the
 * documented escape hatch — on such a pool.
 */
class PostgresAutoCommitGuardTest {

    /** Simulates a pool configured with `autoCommit = false`. */
    private class NoAutoCommitDataSource(private val delegate: DataSource) : DataSource by delegate {
        override fun getConnection(): Connection =
            delegate.connection.also { it.autoCommit = false }
    }

    private val items = EntitySchema(
        table = "autocommit_guard_items",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
    )

    @BeforeTest
    fun resetTable() {
        SharedPostgres.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP TABLE IF EXISTS \"${items.table}\"") }
        }
    }

    @Test
    fun `root operations reject a connection with autocommit disabled`() {
        // Materialize the table with a healthy pool so registration on
        // the broken one stays metadata-only (no connection borrowed).
        PostgresDriver(SharedPostgres.dataSource, autoDdl = true).register(items)

        val broken = PostgresDriver(NoAutoCommitDataSource(SharedPostgres.dataSource))
        broken.register(items)

        val e = assertFailsWith<IllegalStateException> {
            broken.insert(items.table, mapOf("name" to "lost"))
        }
        assertTrue("autoCommit = true" in (e.message ?: ""), "actionable message; was: ${e.message}")

        // The guard fired before the statement ran — nothing to roll back.
        val good = PostgresDriver(SharedPostgres.dataSource).also { it.register(items) }
        assertTrue(good.query(items.table, emptyList(), emptyList(), null, null).isEmpty())
    }

    @Test
    fun `auto-DDL rejects a connection with autocommit disabled instead of registering rolled-back tables`() {
        val broken = PostgresDriver(NoAutoCommitDataSource(SharedPostgres.dataSource), autoDdl = true)

        assertFailsWith<IllegalStateException> { broken.register(items) }

        // The failed registration must stay retryable: nothing cached,
        // so a healthy driver can still materialize the table.
        PostgresDriver(SharedPostgres.dataSource, autoDdl = true).register(items)
    }

    @Test
    fun `withTransaction still works on an autocommit=false pool`() {
        val good = PostgresDriver(SharedPostgres.dataSource, autoDdl = true).also { it.register(items) }

        val broken = PostgresDriver(NoAutoCommitDataSource(SharedPostgres.dataSource))
        broken.register(items)
        broken.withTransaction { tx -> tx.insert(items.table, mapOf("name" to "committed")) }

        val rows = good.query(items.table, emptyList(), emptyList(), null, null)
        assertTrue(rows.any { it["name"] == "committed" }, "transactional write must be durable; rows: $rows")
    }
}
