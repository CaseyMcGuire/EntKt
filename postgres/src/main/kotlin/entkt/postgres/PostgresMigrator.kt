package entkt.postgres

import entkt.migrations.Migrator
import entkt.migrations.SchemaDiffer
import javax.sql.DataSource

/**
 * Factory for creating a fully wired [Migrator] for PostgreSQL.
 */
object PostgresMigrator {

    /**
     * Create a [Migrator] wired to a live Postgres database for
     * planning with optional bootstrap introspection when no snapshot
     * exists yet.
     */
    fun plannerWithIntrospection(dataSource: DataSource): Migrator {
        val typeMapper = PostgresTypeMapper()
        val introspector = PostgresIntrospector(dataSource, typeMapper)
        val renderer = PostgresSqlRenderer(typeMapper)
        return Migrator(
            differ = SchemaDiffer(),
            renderer = renderer,
            typeMapper = typeMapper,
            introspector = introspector,
        )
    }

    /**
     * Create a [Migrator] that only supports snapshot-based
     * [Migrator.plan] — no live database connection required.
     * Suitable for CI/codegen workflows that generate migration files
     * without touching a real database.
     */
    fun planner(): Migrator {
        val typeMapper = PostgresTypeMapper()
        val renderer = PostgresSqlRenderer(typeMapper)
        return Migrator(
            differ = SchemaDiffer(),
            renderer = renderer,
            typeMapper = typeMapper,
        )
    }
}
