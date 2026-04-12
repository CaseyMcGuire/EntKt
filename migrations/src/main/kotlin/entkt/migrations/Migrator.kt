package entkt.migrations

import entkt.runtime.EntitySchema
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
     * a snapshot exists at [snapshotPath]). If no snapshot, managed
     * surface = desired table names only (fresh DB — drops can't be
     * detected, but there's nothing to drop).
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
    fun migrate(schemas: List<EntitySchema>, snapshotPath: Path? = null): MigrationResult {
        val intr = checkNotNull(introspector) { "migrate() requires a DatabaseIntrospector" }
        val exec = checkNotNull(executor) { "migrate() requires a MigrationExecutor" }

        val desired = NormalizedSchema.fromEntitySchemas(schemas, typeMapper)

        val snapshotTableNames = if (snapshotPath != null && snapshotPath.toFile().exists()) {
            NormalizedSchema.fromJson(snapshotPath).tables.keys
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
     * Prod mode: diff desired schemas against a committed snapshot (no
     * live DB needed).
     *
     * Default ([ManualMode.FAIL]): if manual ops exist, throws
     * [ManualMigrationRequiredException] — no migration file emitted,
     * snapshot unchanged.
     *
     * [ManualMode.ACKNOWLEDGE_AND_ADVANCE]: emits auto SQL + a loud
     * structured checklist of manual ops at the top of the migration
     * file, and advances the snapshot.
     *
     * @param snapshotPath path to the JSON schema snapshot (created if
     *   it doesn't exist yet).
     * @param outputDir directory to write the migration SQL file.
     * @param description human-readable label for the migration filename.
     * @return the plan including the file path and ops.
     */
    fun plan(
        schemas: List<EntitySchema>,
        snapshotPath: Path,
        outputDir: Path,
        description: String = "migration",
        manualMode: ManualMode = ManualMode.FAIL,
    ): MigrationPlan {
        val desired = NormalizedSchema.fromEntitySchemas(schemas, typeMapper)

        val current = if (snapshotPath.toFile().exists()) {
            NormalizedSchema.fromJson(snapshotPath)
        } else if (introspector != null) {
            // No snapshot yet but a live DB is available — diff against
            // the actual schema so bootstrapping on an existing DB
            // produces only the delta, not a full CREATE TABLE set.
            introspector.introspect(desired.tables.keys)
        } else {
            NormalizedSchema(emptyMap())
        }
        val result = differ.diff(desired, current)

        if (result.manual.isNotEmpty() && manualMode == ManualMode.FAIL) {
            throw ManualMigrationRequiredException(result.manual)
        }

        if (result.ops.isEmpty() && result.manual.isEmpty()) {
            // No changes, but if no snapshot exists yet this is a
            // bootstrap — write the initial snapshot to establish the
            // baseline (enriched with real names if a DB is available).
            val snapshotAdvanced = !snapshotPath.toFile().exists()
            if (snapshotAdvanced) {
                advanceSnapshot(desired, current, snapshotPath)
            }
            return MigrationPlan(
                filePath = null,
                ops = emptyList(),
                manual = emptyList(),
                snapshotAdvanced = snapshotAdvanced,
            )
        }

        // Build migration file content
        outputDir.toFile().mkdirs()
        val version = nextVersion(outputDir)
        val slug = description.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val filename = "${version}__${slug}.sql"
        val filePath = outputDir.resolve(filename)

        val content = buildMigrationFileContent(version, result, manualMode)
        filePath.toFile().writeText(content)

        advanceSnapshot(desired, current, snapshotPath)

        return MigrationPlan(
            filePath = filePath,
            ops = result.ops,
            manual = result.manual,
            snapshotAdvanced = true,
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
     * Generate a unique version string. Scans the output directory for
     * existing files to avoid collisions when multiple plans run within
     * the same millisecond (scripted/CI workflows).
     */
    private fun nextVersion(outputDir: Path): String {
        val existing = outputDir.toFile()
            .listFiles { f -> f.name.endsWith(".sql") }
            ?.mapTo(mutableSetOf()) { it.name.substringBefore("__") }
            ?: emptySet()

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
        var version = "V$timestamp"
        var seq = 1
        while (version in existing) {
            version = "V${timestamp}_%03d".format(seq)
            seq++
        }
        return version
    }

    /**
     * Write the snapshot, enriched with real storage-level names so
     * future DropIndex/DropForeignKey ops point at actual names.
     * Priority: introspected (ground truth) > previous snapshot > null.
     */
    private fun advanceSnapshot(
        desired: NormalizedSchema,
        current: NormalizedSchema,
        snapshotPath: Path,
    ) {
        val managedTables = desired.tables.keys + current.tables.keys
        val enriched = if (introspector != null) {
            val live = introspector.introspect(managedTables)
            mergeStorageNames(mergeStorageNames(desired, live), current)
        } else {
            mergeStorageNames(desired, current)
        }
        snapshotPath.parent?.toFile()?.mkdirs()
        enriched.toJson(snapshotPath)
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

            val currentIndexByShape = currentTable.indexes.associateBy { it.columns to it.unique }
            val mergedIndexes = desiredTable.indexes.map { idx ->
                if (idx.storageKey != null) idx
                else {
                    val currentIdx = currentIndexByShape[idx.columns to idx.unique]
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
        "AddIndex: ${op.table} ($cols)$u"
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
    val snapshotAdvanced: Boolean,
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
            ?.sortedWith(migrationFileOrder)
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

    companion object {
        /**
         * Parse a version like "V20260411120000123_002" into (timestamp, suffix)
         * for numeric ordering. Falls back to lexicographic on unparseable names.
         */
        private val migrationFileOrder = Comparator<java.io.File> { a, b ->
            fun parseVersion(f: java.io.File): Pair<String, Int> {
                val version = f.nameWithoutExtension.substringBefore("__")
                val underscoreIdx = version.lastIndexOf('_')
                // Only treat as suffixed if the part after _ is all digits
                // and there's a V-prefix timestamp before it
                if (underscoreIdx > 1) {
                    val suffix = version.substring(underscoreIdx + 1)
                    if (suffix.all { it.isDigit() }) {
                        return version.substring(0, underscoreIdx) to suffix.toInt()
                    }
                }
                return version to 0
            }
            val (aBase, aSuffix) = parseVersion(a)
            val (bBase, bSuffix) = parseVersion(b)
            val cmp = aBase.compareTo(bBase)
            if (cmp != 0) cmp else aSuffix.compareTo(bSuffix)
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
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
