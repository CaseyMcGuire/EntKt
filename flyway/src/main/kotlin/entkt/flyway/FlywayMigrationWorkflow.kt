package entkt.flyway

import entkt.migrations.MANUAL_STEPS_MARKER
import entkt.migrations.ManualMigrationRequiredException
import entkt.migrations.ManualMode
import entkt.migrations.NormalizedSchema
import entkt.migrations.SchemaDiffer
import entkt.migrations.describeOp
import entkt.migrations.parseVersionNumber
import entkt.postgres.PostgresIntrospector
import entkt.postgres.PostgresSqlRenderer
import entkt.postgres.PostgresTypeMapper
import entkt.runtime.EntitySchema
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Generates Flyway migration files by diffing a disposable
 * Docker-backed shadow database against the desired entkt schemas.
 *
 * A fresh Postgres container is started for each invocation, existing
 * Flyway migrations are replayed into it, and the result is
 * introspected and diffed. The container is destroyed when the
 * workflow completes.
 */
class FlywayMigrationWorkflow(
    private val config: ShadowDockerConfig = ShadowDockerConfig(),
    /**
     * Table names to exclude from the managed set. Excluded tables
     * are filtered from both the desired schemas and the shadow DB
     * introspection, so they are invisible to the differ. The Flyway
     * history table is always excluded regardless of this setting.
     */
    private val excludeTables: Set<String> = emptySet(),
) {

    /**
     * Check for schema drift without writing any files.
     *
     * Starts a fresh shadow container, applies existing Flyway
     * migrations, introspects the result, and diffs against the
     * desired schemas.
     */
    fun validate(
        schemas: List<EntitySchema>,
        migrationsDir: Path,
    ): DriftResult {
        rejectUnsupportedMigrations(migrationsDir)
        return withShadowDatabase { dataSource ->
            val diff = diffAgainstShadow(dataSource, schemas, migrationsDir)
            DriftResult(ops = diff.ops, manual = diff.manual)
        }
    }

    /**
     * Generate the next Flyway migration file.
     *
     * Starts a fresh shadow container, applies existing Flyway
     * migrations, introspects the result, diffs against the desired
     * schemas, and writes the next versioned SQL file.
     *
     * @param verify if true, re-applies Flyway migrations (including
     *   the newly generated file) and asserts no drift remains. If
     *   verification fails, the generated file is deleted.
     */
    fun generate(
        schemas: List<EntitySchema>,
        migrationsDir: Path,
        description: String = "migration",
        manualMode: ManualMode = ManualMode.FAIL,
        verify: Boolean = false,
    ): GenerateResult {
        rejectUnsupportedMigrations(migrationsDir)
        return withShadowDatabase { dataSource ->
            val typeMapper = PostgresTypeMapper()
            val diff = diffAgainstShadow(dataSource, schemas, migrationsDir)

            if (diff.manual.isNotEmpty() && manualMode == ManualMode.FAIL) {
                throw ManualMigrationRequiredException(diff.manual)
            }

            if (diff.ops.isEmpty() && diff.manual.isEmpty()) {
                return@withShadowDatabase GenerateResult.NoChanges
            }

            // Write the next migration file
            migrationsDir.toFile().mkdirs()
            val version = nextVersion(migrationsDir)
            val slug = description.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val filename = "${version}__${slug}.sql"
            val filePath = migrationsDir.resolve(filename)

            val renderer = PostgresSqlRenderer(typeMapper)
            val content = buildMigrationContent(version, diff, renderer, manualMode)
            filePath.toFile().writeText(content)

            // Optional verification — skip when manual ops produced a
            // RAISE EXCEPTION guard that would always fail Flyway.
            val verification = when {
                !verify -> VerificationOutcome.NotRequested
                diff.manual.isNotEmpty() -> VerificationOutcome.SkippedManualOps
                else -> {
                    try {
                        val postDiff = diffAgainstShadow(dataSource, schemas, migrationsDir)
                        if (postDiff.ops.isNotEmpty() || postDiff.manual.isNotEmpty()) {
                            filePath.toFile().delete()
                            throw VerificationFailedException(postDiff.ops, postDiff.manual)
                        }
                        VerificationOutcome.Verified
                    } catch (e: VerificationFailedException) {
                        throw e
                    } catch (e: Exception) {
                        filePath.toFile().delete()
                        throw e
                    }
                }
            }

            GenerateResult.Written(filePath = filePath, ops = diff.ops, manual = diff.manual, verification = verification)
        }
    }

    /**
     * Run [block] against a fresh Docker-backed Postgres container.
     * The container is started before the block runs and destroyed
     * when it completes (normally or exceptionally).
     */
    private fun <T> withShadowDatabase(block: (DataSource) -> T): T {
        val imageName = DockerImageName.parse(config.image)
            .asCompatibleSubstituteFor("postgres")
        val container = PostgreSQLContainer(imageName)
            .withDatabaseName(config.databaseName)
            .withUsername(config.user)
            .withPassword(config.password)
        container.start()
        try {
            val dataSource = PGSimpleDataSource().apply {
                setURL(container.jdbcUrl)
                user = container.username
                password = container.password
            }
            return block(dataSource)
        } finally {
            container.stop()
        }
    }

    /**
     * Core diff pipeline: apply Flyway migrations → introspect →
     * diff against desired schemas.
     *
     * The container is always fresh, so no clean step is needed.
     */
    private fun diffAgainstShadow(
        dataSource: DataSource,
        schemas: List<EntitySchema>,
        migrationsDir: Path,
    ): entkt.migrations.DiffResult {
        val flyway = configureFlyway(dataSource, migrationsDir)
        flyway.migrate()

        val typeMapper = PostgresTypeMapper()
        val fullDesired = NormalizedSchema.fromEntitySchemas(schemas, typeMapper)
        val desired = filterManagedSchema(fullDesired)
        val shadowTables = listManagedTables(dataSource)
        val introspectionSet = desired.tables.keys + shadowTables
        val introspector = PostgresIntrospector(dataSource, typeMapper)
        val current = introspector.introspect(introspectionSet)

        return SchemaDiffer().diff(desired, current)
    }

    /**
     * Apply [excludeTables] filter to the desired schema so that
     * excluded tables are invisible to the differ on both sides.
     */
    private fun filterManagedSchema(schema: NormalizedSchema): NormalizedSchema {
        if (excludeTables.isEmpty()) return schema
        return NormalizedSchema(schema.tables.filterKeys { it !in excludeTables })
    }

    /**
     * Build the managed table set from the shadow database:
     *
     * 1. Query all base tables in the `public` schema
     * 2. Remove the Flyway history table
     * 3. Remove any [excludeTables]
     */
    private fun listManagedTables(dataSource: DataSource): Set<String> {
        val allTables = mutableSetOf<String>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name != 'flyway_schema_history'
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) allTables.add(rs.getString("table_name"))
                }
            }
        }
        return allTables - excludeTables
    }

    /**
     * Reject Flyway features that V1 does not support before
     * starting the shadow database:
     * - Repeatable migrations (`R__*.sql`)
     * - SQL callbacks (`beforeMigrate.sql`, `afterMigrate.sql`, etc.)
     */
    private fun rejectUnsupportedMigrations(migrationsDir: Path) {
        val dir = migrationsDir.toFile()
        if (!dir.isDirectory) return

        val sqlFiles = dir.walk().filter { it.isFile && it.name.endsWith(".sql") }.toList()

        val repeatables = sqlFiles.filter { it.name.startsWith("R__") }
        if (repeatables.isNotEmpty()) {
            val names = repeatables.map { it.relativeTo(dir).path }.sorted()
            throw IllegalArgumentException(
                "Repeatable Flyway migrations are not supported in V1: $names",
            )
        }

        val callbacks = sqlFiles.filter { f ->
            val base = f.nameWithoutExtension.lowercase()
            FLYWAY_CALLBACK_NAMES.any { base == it || base.startsWith("${it}__") }
        }
        if (callbacks.isNotEmpty()) {
            val names = callbacks.map { it.relativeTo(dir).path }.sorted()
            throw IllegalArgumentException(
                "Flyway SQL callbacks are not supported in V1: $names",
            )
        }
    }

    companion object {
        /**
         * Flyway SQL callback filenames (case-insensitive, without extension).
         * Derived from [org.flywaydb.core.api.callback.Event] enum values.
         */
        private val FLYWAY_CALLBACK_NAMES = setOf(
            // clean
            "beforeclean", "afterclean", "aftercleanerror",
            // migrate
            "beforemigrate", "beforeeachmigrate", "beforeeachmigratestatement",
            "aftereachmigratestatement", "aftereachmigratestatementerror",
            "aftereachmigrate", "aftereachmigrateerror",
            "beforerepeatables", "afterversioned",
            "aftermigrateapplied", "aftermigrate", "aftermigrateerror",
            // undo
            "beforeundo", "beforeeachundo", "beforeeachundostatement",
            "aftereachundostatement", "aftereachundostatementerror",
            "aftereachundo", "aftereachundoerror",
            "afterundo", "afterundoerror",
            // validate
            "beforevalidate", "aftervalidate", "aftervalidateerror",
            // baseline
            "beforebaseline", "afterbaseline", "afterbaselineerror",
            // repair
            "beforerepair", "afterrepair", "afterrepairerror",
            // info
            "beforeinfo", "afterinfo", "afterinfoerror",
            // operation finish
            "aftermigrateoperationfinish", "afterinfooperationfinish",
            // schema / connect
            "createschema", "beforecreateschema",
            "beforeconnect", "afterconnect",
        )

        internal fun nextVersion(dir: Path): String {
            val root = dir.toFile()
            val maxVersion = if (root.isDirectory) {
                root.walk()
                    .filter { it.isFile && it.name.endsWith(".sql") }
                    .mapNotNull { parseVersionNumber(it.name) }
                    .maxOrNull()
                    ?: 0
            } else {
                0
            }
            return "V${maxVersion + 1}"
        }
    }

    private fun configureFlyway(dataSource: DataSource, migrationsDir: Path): Flyway {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:${migrationsDir.toAbsolutePath()}")
            .load()
    }

    private fun buildMigrationContent(
        version: String,
        result: entkt.migrations.DiffResult,
        renderer: PostgresSqlRenderer,
        manualMode: ManualMode,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("-- entkt migration $version")

        if (result.manual.isNotEmpty() && manualMode == ManualMode.ACKNOWLEDGE_AND_ADVANCE) {
            sb.appendLine("--")
            sb.appendLine("-- $MANUAL_STEPS_MARKER")
            sb.appendLine("-- The operations below require hand-written SQL. Add it to this file in")
            sb.appendLine("-- the correct position relative to the auto-generated statements that")
            sb.appendLine("-- follow the guard — ordering matters: some manual steps depend on the")
            sb.appendLine("-- auto ops (e.g. a unique index over a newly added column must run AFTER")
            sb.appendLine("-- that column is added), while an auto op may depend on a manual one")
            sb.appendLine("-- (e.g. a foreign key referencing a column you add by hand). Do not")
            sb.appendLine("-- assume the SQL goes where this guard sits.")
            sb.appendLine("--")
            for (op in result.manual) {
                sb.appendLine("-- [ ] ${describeOp(op)}")
            }
            sb.appendLine("--")
            sb.appendLine()
            sb.appendLine("-- Delete this guard once the manual steps above are written into the file.")
            sb.appendLine("DO $\$BEGIN RAISE EXCEPTION 'entkt: manual migration steps have not been completed — see comments above'; END$\$;")
            sb.appendLine()
            if (result.ops.isNotEmpty()) {
                sb.appendLine("-- Auto-generated operations follow.")
            }
        }

        for (op in result.ops) {
            sb.appendLine("-- ${describeOp(op)}")
        }
        sb.appendLine()

        for (op in result.ops) {
            val statements = renderer.render(op)
            for (stmt in statements) {
                sb.appendLine("$stmt;")
            }
        }

        return sb.toString()
    }
}
