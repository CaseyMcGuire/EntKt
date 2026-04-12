package entkt.postgres

import entkt.migrations.MigrationExecutor
import javax.sql.DataSource

/**
 * Executes migration SQL against PostgreSQL and manages the
 * `schema_migrations` table for tracking applied versions.
 */
class PostgresMigrationExecutor(
    private val dataSource: DataSource,
) : MigrationExecutor {

    init {
        ensureMigrationsTable()
    }

    override fun execute(statements: List<String>) {
        if (statements.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt ->
                    for (sql in statements) {
                        stmt.execute(sql)
                    }
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun executeAndRecord(statements: List<String>, version: String, checksum: String) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt ->
                    for (sql in statements) {
                        stmt.execute(sql)
                    }
                }
                conn.prepareStatement(
                    """INSERT INTO "schema_migrations" ("version", "checksum") VALUES (?, ?)""",
                ).use { stmt ->
                    stmt.setString(1, version)
                    stmt.setString(2, checksum)
                    stmt.executeUpdate()
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun executeScriptAndRecord(script: String, version: String, checksum: String) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute(script)
                }
                conn.prepareStatement(
                    """INSERT INTO "schema_migrations" ("version", "checksum") VALUES (?, ?)""",
                ).use { stmt ->
                    stmt.setString(1, version)
                    stmt.setString(2, checksum)
                    stmt.executeUpdate()
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun appliedVersions(): Map<String, String> {
        val versions = mutableMapOf<String, String>()
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("""SELECT "version", "checksum" FROM "schema_migrations" ORDER BY "version"""")
                    .use { rs ->
                        while (rs.next()) {
                            versions[rs.getString("version")] = rs.getString("checksum")
                        }
                    }
            }
        }
        return versions
    }

    private fun ensureMigrationsTable() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS "schema_migrations" (
                        "version"    text PRIMARY KEY,
                        "checksum"   text NOT NULL,
                        "applied_at" timestamptz NOT NULL DEFAULT now()
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
