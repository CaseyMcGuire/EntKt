package entkt.migrations

import entkt.runtime.EntitySchema
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Marker embedded in migration files generated with
 * [ManualMode.ACKNOWLEDGE_AND_ADVANCE]. The [MigrationRunner] refuses
 * to apply files containing this marker — the user must complete the
 * manual steps and remove it first.
 */
const val MANUAL_STEPS_MARKER = "!! MANUAL STEPS REQUIRED !!"

/**
 * Orchestrates schema migrations in two modes:
 *
 * - **Dev mode ([migrate]):** introspect live DB, diff against desired
 *   schemas, apply additive ops in a single transaction.
 * - **Prod mode ([plan]):** diff desired schemas against a committed
 *   snapshot, generate a migration SQL file.
 */
class Migrator(
    private val differ: SchemaDiffer,
    private val renderer: MigrationSqlRenderer,
    private val typeMapper: TypeMapper,
    /** Required for [migrate] (dev mode). Null when only [plan] is needed. */
    private val introspector: DatabaseIntrospector? = null,
    /** Required for [migrate] (dev mode). Null when only [plan] is needed. */
    private val executor: MigrationExecutor? = null,
) {

    /**
     * Dev mode: introspect live DB, diff against desired schemas.
     *
     * Managed surface = desired table names ∪ snapshot table names (if
     * a `.schema.json` exists in [migrationsDir]). If no snapshot,
     * managed surface = desired table names only (fresh DB — drops
     * can't be detected, but there's nothing to drop).
     *
     * If [DiffResult.manual] is non-empty, fails BEFORE applying
     * anything (all-or-nothing). If only auto ops, applies them in a
     * single transaction. No schema_migrations tracking.
     *
     * Requires [introspector] and [executor] to be provided at
     * construction time.
     *
     * @return the ops that were applied, or empty if already up to date.
     * @throws ManualMigrationRequiredException if manual ops are detected.
     * @throws IllegalStateException if introspector or executor were not provided.
     */
    fun migrate(schemas: List<EntitySchema>, migrationsDir: Path? = null): MigrationResult {
        val intr = checkNotNull(introspector) { "migrate() requires a DatabaseIntrospector" }
        val exec = checkNotNull(executor) { "migrate() requires a MigrationExecutor" }

        val desired = NormalizedSchema.fromEntitySchemas(schemas, typeMapper)

        val snapshotTableNames = if (migrationsDir != null) {
            latestSnapshot(migrationsDir)?.tables?.keys ?: emptySet()
        } else {
            emptySet()
        }
        val managedTables = desired.tables.keys + snapshotTableNames

        val current = intr.introspect(managedTables)
        val result = differ.diff(desired, current)

        if (result.manual.isNotEmpty()) {
            throw ManualMigrationRequiredException(result.manual)
        }

        if (result.ops.isEmpty()) {
            return MigrationResult(applied = emptyList(), manual = emptyList())
        }

        val statements = result.ops.flatMap { renderer.render(it, RenderMode.DEV) }
        exec.execute(statements)

        return MigrationResult(applied = result.ops, manual = emptyList())
    }

    /**
     * Prod mode: diff desired schemas against the latest committed
     * snapshot in [outputDir] (no live DB needed).
     *
     * Each migration produces a paired SQL file and schema snapshot:
     * `V1__description.sql` + `V1.schema.json`. The planner reads the
     * highest-numbered `.schema.json` as the baseline.
     *
     * Sequential versions (`V1`, `V2`, `V3`) ensure that concurrent
     * branches creating the same version cause a git merge conflict,
     * forcing the team to resolve ordering before merging.
     *
     * Default ([ManualMode.FAIL]): if manual ops exist, throws
     * [ManualMigrationRequiredException] — no files emitted.
     *
     * [ManualMode.ACKNOWLEDGE_AND_ADVANCE]: emits auto SQL + a loud
     * structured checklist of manual ops at the top of the migration
     * file, and writes the snapshot.
     *
     * @param outputDir directory for migration SQL and snapshot files.
     * @param description human-readable label for the migration filename.
     * @return the plan including the file path and ops.
     */
    fun plan(
        schemas: List<EntitySchema>,
        outputDir: Path,
        description: String = "migration",
        manualMode: ManualMode = ManualMode.FAIL,
    ): MigrationPlan {
        verifySnapshotChain(outputDir)

        val desired = NormalizedSchema.fromEntitySchemas(schemas, typeMapper)

        val current = latestSnapshot(outputDir)
            ?: if (introspector != null) {
                introspector.introspect(desired.tables.keys)
            } else {
                NormalizedSchema(emptyMap())
            }
        val result = differ.diff(desired, current)

        if (result.manual.isNotEmpty() && manualMode == ManualMode.FAIL) {
            throw ManualMigrationRequiredException(result.manual)
        }

        if (result.ops.isEmpty() && result.manual.isEmpty()) {
            return MigrationPlan(
                filePath = null,
                ops = emptyList(),
                manual = emptyList(),
            )
        }

        // Build migration file content
        outputDir.toFile().mkdirs()
        val version = nextVersion(outputDir)
        val slug = description.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val sqlFilename = "${version}__${slug}.sql"
        val sqlPath = outputDir.resolve(sqlFilename)

        val content = buildMigrationFileContent(version, result, manualMode)
        sqlPath.toFile().writeText(content)

        // Write paired snapshot with parent checksum
        val parentChecksum = latestSnapshotChecksum(outputDir)
        val snapshotPath = outputDir.resolve("${version}.schema.json")
        writeSnapshot(desired, current, snapshotPath, parentChecksum)

        return MigrationPlan(
            filePath = sqlPath,
            ops = result.ops,
            manual = result.manual,
        )
    }

    private fun buildMigrationFileContent(
        version: String,
        result: DiffResult,
        manualMode: ManualMode,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("-- entkt migration $version")

        // Manual ops header
        if (result.manual.isNotEmpty() && manualMode == ManualMode.ACKNOWLEDGE_AND_ADVANCE) {
            sb.appendLine("--")
            sb.appendLine("-- $MANUAL_STEPS_MARKER")
            sb.appendLine("-- The following operations were detected but NOT included in this file.")
            sb.appendLine("-- You must handle them separately before applying this migration.")
            sb.appendLine("--")
            for (op in result.manual) {
                sb.appendLine("-- [ ] ${describeOp(op)}")
            }
            sb.appendLine("--")
            if (result.ops.isNotEmpty()) {
                sb.appendLine("-- Auto-applied operations follow.")
            }
        }

        // Op summary comments
        for (op in result.ops) {
            sb.appendLine("-- ${describeOp(op)}")
        }
        sb.appendLine()

        // SQL statements
        for (op in result.ops) {
            val statements = renderer.render(op, RenderMode.MIGRATION_FILE)
            for (stmt in statements) {
                sb.appendLine("$stmt;")
            }
        }

        return sb.toString()
    }

    /**
     * Next sequential version: scans for existing `V{N}` prefixes and
     * returns `V{max + 1}`. Starts at `V1` for an empty directory.
     */
    private fun nextVersion(outputDir: Path): String {
        val maxVersion = outputDir.toFile()
            .listFiles()
            ?.mapNotNull { parseVersionNumber(it.name) }
            ?.maxOrNull()
            ?: 0
        return "V${maxVersion + 1}"
    }

    /**
     * Read the latest `.schema.json` from [dir] by version order.
     * Returns null if no snapshots exist.
     */
    private fun latestSnapshot(dir: Path): NormalizedSchema? {
        val latest = dir.toFile()
            .listFiles { f -> f.name.endsWith(".schema.json") }
            ?.maxByOrNull { parseVersionNumber(it.name) ?: 0 }
            ?: return null
        return NormalizedSchema.fromJson(latest.toPath())
    }

    /**
     * Write the snapshot, enriched with real storage-level names so
     * future DropIndex/DropForeignKey ops point at actual names.
     * Priority: introspected (ground truth) > previous snapshot > null.
     */
    private fun writeSnapshot(
        desired: NormalizedSchema,
        current: NormalizedSchema,
        snapshotPath: Path,
        parentChecksum: String?,
    ) {
        val managedTables = desired.tables.keys + current.tables.keys
        val enriched = if (introspector != null) {
            val live = introspector.introspect(managedTables)
            mergeStorageNames(mergeStorageNames(desired, live), current)
        } else {
            mergeStorageNames(desired, current)
        }
        snapshotPath.parent?.toFile()?.mkdirs()
        enriched.toJson(snapshotPath, parentChecksum)
    }

    /**
     * SHA-256 of the latest `.schema.json` file content, or null if
     * no snapshots exist yet.
     */
    private fun latestSnapshotChecksum(dir: Path): String? {
        val latest = dir.toFile()
            .listFiles { f -> f.name.endsWith(".schema.json") }
            ?.maxByOrNull { parseVersionNumber(it.name) ?: 0 }
            ?: return null
        return sha256(latest.readText())
    }

    /**
     * Verify the snapshot chain is intact. Each V{N}.schema.json must
     * have a `parent` checksum matching the SHA-256 of V{N-1}.schema.json.
     *
     * @throws BrokenSnapshotChainException if a mismatch is detected.
     */
    private fun verifySnapshotChain(dir: Path) {
        val snapshots = dir.toFile()
            .listFiles { f -> f.name.endsWith(".schema.json") }
            ?.sortedBy { parseVersionNumber(it.name) ?: 0 }
            ?: return

        if (snapshots.size < 2) return

        for (i in 1 until snapshots.size) {
            val current = snapshots[i]
            val previous = snapshots[i - 1]
            val currentContent = current.readText()
            val expectedParent = sha256(previous.readText())
            val actualParent = JsonCodec.decodeParent(currentContent)

            if (actualParent != null && actualParent != expectedParent) {
                val currentVersion = parseVersionNumber(current.name) ?: 0
                val previousVersion = parseVersionNumber(previous.name) ?: 0
                throw BrokenSnapshotChainException(
                    version = "V$currentVersion",
                    parentVersion = "V$previousVersion",
                    expectedChecksum = expectedParent,
                    actualChecksum = actualParent,
                )
            }
        }
    }

    /**
     * Carry forward storage-level names from the previous snapshot into
     * the new desired schema before saving. [NormalizedSchema.fromEntitySchemas]
     * produces `storageKey = null` and `constraintName = null` for most
     * indexes/FKs, but the previous snapshot may have captured real names
     * from introspection. Without this merge, advancing the snapshot
     * would discard those names, causing future DropIndex/DropForeignKey
     * ops to point at derived names instead of real ones.
     */
    private fun mergeStorageNames(
        desired: NormalizedSchema,
        current: NormalizedSchema,
    ): NormalizedSchema {
        val mergedTables = desired.tables.mapValues { (tableName, desiredTable) ->
            val currentTable = current.tables[tableName] ?: return@mapValues desiredTable

            data class IndexShape(val columns: List<String>, val unique: Boolean, val where: String?)
            val currentIndexByShape = currentTable.indexes.associateBy { IndexShape(it.columns, it.unique, normalizeWhere(it.where)) }
            val mergedIndexes = desiredTable.indexes.map { idx ->
                if (idx.storageKey != null) idx
                else {
                    val currentIdx = currentIndexByShape[IndexShape(idx.columns, idx.unique, normalizeWhere(idx.where))]
                    if (currentIdx?.storageKey != null) idx.copy(storageKey = currentIdx.storageKey)
                    else idx
                }
            }

            // Include columnNullable in the key so a nullability flip
            // (which triggers drop+recreate of the FK under a new name)
            // doesn't carry the old constraint name into the snapshot.
            data class FkKey(val column: String, val targetTable: String, val targetColumn: String, val columnNullable: Boolean)
            val currentFkByKey = currentTable.foreignKeys.associateBy {
                FkKey(it.column, it.targetTable, it.targetColumn, it.columnNullable)
            }
            val mergedFks = desiredTable.foreignKeys.map { fk ->
                if (fk.constraintName != null) fk
                else {
                    val currentFk = currentFkByKey[FkKey(fk.column, fk.targetTable, fk.targetColumn, fk.columnNullable)]
                    if (currentFk?.constraintName != null) fk.copy(constraintName = currentFk.constraintName)
                    else fk
                }
            }

            desiredTable.copy(indexes = mergedIndexes, foreignKeys = mergedFks)
        }
        return NormalizedSchema(mergedTables)
    }
}

/** Describes a [MigrationOp] as a human-readable one-liner. */
internal fun describeOp(op: MigrationOp): String = when (op) {
    is MigrationOp.CreateTable -> "CreateTable: ${op.table.name}"
    is MigrationOp.AddColumn -> "AddColumn: ${op.table}.${op.column.name} (${op.column.sqlType}${if (op.column.nullable) "" else " NOT NULL"})"
    is MigrationOp.AddIndex -> {
        val cols = op.index.columns.joinToString(", ")
        val u = if (op.index.unique) " unique" else ""
        val w = if (op.index.where != null) " WHERE ${op.index.where}" else ""
        "AddIndex: ${op.table} ($cols)$u$w"
    }
    is MigrationOp.AddForeignKey -> "AddForeignKey: ${op.table}.${op.fk.column} -> ${op.fk.targetTable}.${op.fk.targetColumn}"
    is MigrationOp.DropTable -> "DropTable: ${op.tableName}"
    is MigrationOp.DropColumn -> "DropColumn: ${op.table}.${op.columnName}"
    is MigrationOp.AlterColumnType -> "AlterColumnType: ${op.table}.${op.columnName} (${op.oldType} -> ${op.newType})"
    is MigrationOp.SetColumnNotNull -> "SetColumnNotNull: ${op.table}.${op.columnName}"
    is MigrationOp.DropColumnNotNull -> "DropColumnNotNull: ${op.table}.${op.columnName}"
    is MigrationOp.AlterPrimaryKey -> {
        val action = if (op.added) "add to" else "remove from"
        "AlterPrimaryKey: $action ${op.table}.${op.columnName}"
    }
    is MigrationOp.DropIndex -> {
        val cols = op.columns.joinToString(", ")
        val u = if (op.unique) " unique" else ""
        val name = if (op.storageKey != null) " [${op.storageKey}]" else ""
        "DropIndex: ${op.table} ($cols)$u$name"
    }
    is MigrationOp.DropForeignKey -> {
        val name = if (op.constraintName != null) " [${op.constraintName}]" else ""
        "DropForeignKey: ${op.table}.${op.column}$name"
    }
}

data class MigrationResult(
    val applied: List<MigrationOp>,
    val manual: List<MigrationOp>,
)

data class MigrationPlan(
    val filePath: Path?,
    val ops: List<MigrationOp>,
    val manual: List<MigrationOp>,
)

/**
 * Extract the integer version number from a filename like `V3__foo.sql`
 * or `V3.schema.json`. Returns null if the name doesn't match.
 */
internal fun parseVersionNumber(filename: String): Int? {
    val match = Regex("^V(\\d+)").find(filename) ?: return null
    return match.groupValues[1].toIntOrNull()
}

internal fun sha256(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
}

class BrokenSnapshotChainException(
    val version: String,
    val parentVersion: String,
    val expectedChecksum: String,
    val actualChecksum: String,
) : RuntimeException(
    "Snapshot chain broken: $version.schema.json expects parent checksum " +
        "${actualChecksum.take(12)}... but $parentVersion.schema.json has checksum " +
        "${expectedChecksum.take(12)}.... Re-run planMigration to regenerate " +
        "from the correct baseline.",
)

class ManualMigrationRequiredException(
    val ops: List<MigrationOp>,
) : RuntimeException(
    buildString {
        appendLine("Manual migration required. The following operations cannot be auto-applied:")
        for (op in ops) {
            appendLine("  - ${describeOp(op)}")
        }
        appendLine("Write a manual migration to resolve these, then re-run with ACKNOWLEDGE_AND_ADVANCE if needed.")
    },
)

/**
 * Applies versioned migration files and tracks them in
 * `schema_migrations`. Used in prod deployments.
 * Hard-fails on checksum mismatches (edited migration files).
 */
class MigrationRunner(
    private val executor: MigrationExecutor,
) {

    fun applyPending(migrationDir: Path): ApplyResult {
        val applied = executor.appliedVersions()
        val files = migrationDir.toFile().listFiles { f -> f.name.endsWith(".sql") }
            ?.sortedBy { parseVersionNumber(it.name) ?: 0 }
            ?: emptyList()

        val pending = mutableListOf<String>()

        for (file in files) {
            val version = file.nameWithoutExtension.substringBefore("__")
            val rawContent = file.readText()
            // Normalize line endings so new checksums are stable across
            // LF and CRLF checkouts of the same file.
            val content = rawContent.replace("\r\n", "\n")
            val checksum = sha256(content)

            val existingChecksum = applied[version]
            if (existingChecksum != null) {
                // Accept either the normalized or raw checksum so that
                // migrations applied before CRLF normalization was added
                // are not rejected.
                val rawChecksum = if (rawContent !== content) sha256(rawContent) else checksum
                if (existingChecksum != checksum && existingChecksum != rawChecksum) {
                    throw ChecksumMismatchException(version, existingChecksum, checksum)
                }
                // Already applied with matching checksum — skip
                continue
            }

            if (MANUAL_STEPS_MARKER in content) {
                throw UnresolvedManualStepsException(version, file.name)
            }

            executor.executeScriptAndRecord(rawContent, version, checksum)
            pending.add(version)
        }

        return ApplyResult(applied = pending)
    }
}

data class ApplyResult(
    val applied: List<String>,
)

class UnresolvedManualStepsException(
    val version: String,
    val filename: String,
) : RuntimeException(
    "Migration $version ($filename) contains unresolved manual steps. " +
        "Complete the manual operations listed under '$MANUAL_STEPS_MARKER' " +
        "and remove the marker before applying.",
)

class ChecksumMismatchException(
    val version: String,
    val expected: String,
    val actual: String,
) : RuntimeException(
    "Migration $version has been modified after it was applied. " +
        "Expected checksum $expected but found $actual. " +
        "Do not edit migration files after they have been applied.",
)
