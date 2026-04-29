package entkt.migrations

import entkt.runtime.EntitySchema
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Marker embedded in migration files generated with
 * [ManualMode.ACKNOWLEDGE_AND_ADVANCE]. It is intended for human review
 * and for external migration tooling to notice as needed.
 */
const val MANUAL_STEPS_MARKER = "!! MANUAL STEPS REQUIRED !!"

/**
 * Plans versioned schema migrations by diffing desired schemas against
 * the latest committed snapshot, optionally using live DB introspection
 * as a bootstrap baseline when no snapshot exists yet.
 */
class Migrator(
    private val differ: SchemaDiffer,
    private val renderer: MigrationSqlRenderer,
    private val typeMapper: TypeMapper,
    private val introspector: DatabaseIntrospector? = null,
) {

    /**
     * Diff desired schemas against the latest committed snapshot in
     * [outputDir]. In the normal case this is snapshot-only planning
     * and no live database connection is needed.
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
            val statements = renderer.render(op)
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
     * produces `name = null` and `constraintName = null` for most
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
                if (idx.name != null) idx
                else {
                    val currentIdx = currentIndexByShape[IndexShape(idx.columns, idx.unique, normalizeWhere(idx.where))]
                    if (currentIdx?.name != null) idx.copy(name = currentIdx.name)
                    else idx
                }
            }

            // Include the effective ON DELETE action in the key so that
            // a nullability flip or onDelete change (which triggers drop+
            // recreate of the FK under a new name) doesn't carry the old
            // constraint name into the snapshot. Use the effective action
            // (not raw) so that null matches its inferred default.
            data class FkKey(
                val column: String,
                val targetTable: String,
                val targetColumn: String,
                val effectiveOnDelete: entkt.schema.OnDelete,
            )
            fun effectiveOnDelete(fk: NormalizedForeignKey): entkt.schema.OnDelete =
                fk.onDelete ?: if (fk.columnNullable) entkt.schema.OnDelete.SET_NULL else entkt.schema.OnDelete.RESTRICT
            val currentFkByKey = currentTable.foreignKeys.associateBy {
                FkKey(it.column, it.targetTable, it.targetColumn, effectiveOnDelete(it))
            }
            val mergedFks = desiredTable.foreignKeys.map { fk ->
                if (fk.constraintName != null) fk
                else {
                    val currentFk = currentFkByKey[FkKey(fk.column, fk.targetTable, fk.targetColumn, effectiveOnDelete(fk))]
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
        val name = if (op.name != null) " [${op.name}]" else ""
        "DropIndex: ${op.table} ($cols)$u$name"
    }
    is MigrationOp.DropForeignKey -> {
        val name = if (op.constraintName != null) " [${op.constraintName}]" else ""
        "DropForeignKey: ${op.table}.${op.column}$name"
    }
}

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
        "${expectedChecksum.take(12)}.... Re-run generateMigrationFile to regenerate " +
        "from the correct baseline.",
)

class ManualMigrationRequiredException(
    val ops: List<MigrationOp>,
) : RuntimeException(
    buildString {
        appendLine("Manual migration required. The following operations cannot be auto-generated:")
        for (op in ops) {
            appendLine("  - ${describeOp(op)}")
        }
        appendLine("Write a manual migration to resolve these, then re-run with ACKNOWLEDGE_AND_ADVANCE if needed.")
    },
)
