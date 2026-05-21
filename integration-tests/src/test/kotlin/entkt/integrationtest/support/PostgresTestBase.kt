package entkt.integrationtest.support

import entkt.integrationtest.ent.EntClient
import entkt.postgres.PostgresDriver
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Base class for Postgres-backed integration tests. Provisions one
 * Testcontainers `postgres:16-alpine` container per concrete test
 * class, registers `EntClient.SCHEMAS` via `autoDdl`, and truncates
 * every managed table between tests.
 *
 * Subclasses build their own `EntClient` via [newDriver] or
 * [resetAndDriver] — `EntClient`'s constructor block (privacy /
 * validation policies, interceptors, hooks, …) is test-specific so
 * the base doesn't try to abstract it.
 */
@Testcontainers
abstract class PostgresTestBase {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    protected val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    /**
     * Register schemas (idempotent, autoDdl creates tables on first
     * call) and return a non-DDL driver bound to the same data source.
     * Callers truncate before each test via [resetAndDriver].
     */
    protected fun newDriver(): PostgresDriver {
        PostgresDriver(dataSource, autoDdl = true).also { ddl ->
            EntClient.SCHEMAS.forEach(ddl::register)
        }
        return PostgresDriver(dataSource).also { driver ->
            EntClient.SCHEMAS.forEach(driver::register)
        }
    }

    /** Truncate every managed table, then return a fresh driver. */
    protected fun resetAndDriver(): PostgresDriver {
        val driver = newDriver()
        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE")
            }
        }
        return driver
    }
}
