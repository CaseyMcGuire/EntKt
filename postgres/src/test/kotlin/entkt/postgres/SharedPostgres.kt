package entkt.postgres

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/**
 * One Postgres container shared by the registration/transaction tests in
 * this package.
 *
 * Each `@Testcontainers` class in this module otherwise starts and stops
 * its own container. That's fine at a handful of classes, but the
 * create/destroy churn gets unreliable as the count grows — a full build
 * has shown `PSQLException: The connection attempt failed` from classes
 * that pass in isolation. Tests that only need a plain server share this
 * one instead of adding to the churn.
 *
 * Started once on first access and left running; Testcontainers' reaper
 * removes it when the JVM exits. Safe to share because each participating
 * class owns a distinct set of table names and drops them before use.
 */
internal object SharedPostgres {

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }

    val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            password = container.password
        }
    }
}
