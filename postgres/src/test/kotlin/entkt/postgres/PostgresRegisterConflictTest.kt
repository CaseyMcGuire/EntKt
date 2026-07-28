package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `register()` used to return early on any table name it had already
 * seen, without comparing metadata. A second schema claiming a
 * registered table was silently dropped: the driver kept lowering
 * queries against the first schema's columns, and the newcomer skipped
 * the native-storage / typed-JSON / JSON-codec preflight entirely.
 *
 * Now a genuine conflict fails loudly and a replay of the same schema
 * stays a no-op.
 *
 * No Testcontainers: `autoDdl = false` never touches the DataSource.
 */
class PostgresRegisterConflictTest {

    private fun schema(vararg columns: ColumnMetadata) = EntitySchema(
        table = "widgets",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = columns.toList(),
        edges = emptyMap(),
    )

    private val idColumn = ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true)

    private fun driver() = PostgresDriver(dataSource = NoDdlDataSource, autoDdl = false)

    @Test
    fun `re-registering the same schema is a no-op`() {
        // The generated client registers EntClient.SCHEMAS in its init
        // block, so building a second client over one driver replays the
        // identical list. That has to stay silent.
        val driver = driver()
        val first = schema(idColumn, ColumnMetadata("name", FieldType.STRING, nullable = false))
        driver.register(first)
        driver.register(first)
    }

    @Test
    fun `re-registering an equal-but-distinct instance is a no-op`() {
        // EntitySchema is a data class, so the check is structural —
        // two schema sets built from the same source compare equal.
        val driver = driver()
        driver.register(schema(idColumn, ColumnMetadata("name", FieldType.STRING, nullable = false)))
        driver.register(schema(idColumn, ColumnMetadata("name", FieldType.STRING, nullable = false)))
    }

    @Test
    fun `registering a different schema under a claimed table fails`() {
        val driver = driver()
        driver.register(schema(idColumn, ColumnMetadata("name", FieldType.STRING, nullable = false)))

        val ex = assertFailsWith<IllegalStateException> {
            driver.register(schema(idColumn, ColumnMetadata("label", FieldType.INT, nullable = true)))
        }
        assertTrue(
            ex.message!!.contains("widgets") && ex.message!!.contains("different metadata"),
            "message should name the table and the conflict; got: ${ex.message}",
        )
    }

    @Test
    fun `a failed conflicting registration leaves the original in place`() {
        // The loser of a conflict must not corrupt the registry — the
        // first schema is what every subsequent query lowers against.
        val driver = driver()
        val original = schema(idColumn, ColumnMetadata("name", FieldType.STRING, nullable = false))
        driver.register(original)
        assertFailsWith<IllegalStateException> {
            driver.register(schema(idColumn, ColumnMetadata("label", FieldType.INT, nullable = true)))
        }

        // Re-registering the original is still a no-op and the conflicting
        // schema still loses — neither would hold if the failed attempt
        // had swapped the registry entry.
        driver.register(original)
        assertFailsWith<IllegalStateException> {
            driver.register(schema(idColumn, ColumnMetadata("label", FieldType.INT, nullable = true)))
        }
    }

    private object NoDdlDataSource : javax.sql.DataSource {
        override fun getConnection() = error("test fixture: autoDdl = false must not open a connection")
        override fun getConnection(username: String?, password: String?) = getConnection()
        override fun getLogWriter() = error("not used")
        override fun setLogWriter(out: java.io.PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout() = 0
        override fun getParentLogger() = error("not used")
        override fun <T : Any?> unwrap(iface: Class<T>?) = error("not used")
        override fun isWrapperFor(iface: Class<*>?) = false
    }
}
