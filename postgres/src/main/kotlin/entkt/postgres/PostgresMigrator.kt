package entkt.postgres

import entkt.migrations.MigrationRunner
import entkt.migrations.Migrator
import entkt.migrations.SchemaDiffer
import javax.sql.DataSource

/**
 * Factory for creating a fully wired [Migrator] and [MigrationRunner]
 * for PostgreSQL.
 */
object PostgresMigrator {

    /**
     * Create a [Migrator] wired to a live Postgres database. Supports
     * both dev mode ([Migrator.migrate]) and prod mode ([Migrator.plan]).
     *
     * This eagerly connects to the database to set up the
     * `schema_migrations` table — use [planner] instead if you only
     * need snapshot-based [Migrator.plan] without a live DB.
     */
    fun create(dataSource: DataSource): Migrator {
        val typeMapper = PostgresTypeMapper()
        val introspector = PostgresIntrospector(dataSource, typeMapper)
        val renderer = PostgresSqlRenderer(typeMapper)
        val executor = PostgresMigrationExecutor(dataSource)
        return Migrator(
            differ = SchemaDiffer(),
            renderer = renderer,
            typeMapper = typeMapper,
            introspector = introspector,
            executor = executor,
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

    /**
     * Create a [MigrationRunner] for applying versioned migration files.
     */
    fun runner(dataSource: DataSource): MigrationRunner {
        val executor = PostgresMigrationExecutor(dataSource)
        return MigrationRunner(executor)
    }
}
