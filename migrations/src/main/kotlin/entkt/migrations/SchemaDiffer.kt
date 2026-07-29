package entkt.migrations

import entkt.schema.OnDelete

/**
 * Pure function that diffs two [NormalizedSchema] values and returns a
 * [DiffResult] containing additive (auto) ops and destructive (manual) ops.
 *
 * The algorithm:
 * 1. Partition tables: desired-only → CreateTable, current-only → DropTable (manual)
 * 2. Per shared table: diff columns, indexes, and FKs
 * 3. Order auto ops: CreateTable first, then AddColumn, then AddForeignKey, then AddIndex
 */
class SchemaDiffer {

    fun diff(desired: NormalizedSchema, current: NormalizedSchema): DiffResult {
        val autoOps = mutableListOf<MigrationOp>()
        val manualOps = mutableListOf<MigrationOp>()

        val desiredTables = desired.tables
        val currentTables = current.tables

        // New tables
        for ((name, table) in desiredTables) {
            if (name !in currentTables) {
                // CreateTable emits columns + PK only; indexes and FKs are separate
                autoOps.add(MigrationOp.CreateTable(table))
                for (idx in table.indexes) {
                    autoOps.add(MigrationOp.AddIndex(name, idx))
                }
                for (fk in table.foreignKeys) {
                    autoOps.add(MigrationOp.AddForeignKey(name, fk))
                }
            }
        }

        // Dropped tables
        for (name in currentTables.keys) {
            if (name !in desiredTables) {
                manualOps.add(MigrationOp.DropTable(name))
            }
        }

        // Shared tables — diff columns, indexes, FKs
        for ((name, desiredTable) in desiredTables) {
            val currentTable = currentTables[name] ?: continue
            diffTable(name, desiredTable, currentTable, autoOps, manualOps)
        }

        // Ensure any extension a new table/column needs is created first. Tied
        // to CreateTable/AddColumn so an unchanged schema emits nothing.
        val neededExtensions = (autoOps + manualOps).flatMap { op ->
            when (op) {
                is MigrationOp.CreateTable -> op.table.columns.mapNotNull { it.requiredExtension }
                is MigrationOp.AddColumn -> listOfNotNull(op.column.requiredExtension)
                else -> emptyList()
            }
        }.distinct()
        for (ext in neededExtensions) autoOps.add(MigrationOp.CreateExtension(ext))

        // Sort auto ops in dependency order
        val sorted = sortOps(autoOps)

        return DiffResult(ops = sorted, manual = manualOps)
    }

    private fun diffTable(
        table: String,
        desired: NormalizedTable,
        current: NormalizedTable,
        autoOps: MutableList<MigrationOp>,
        manualOps: MutableList<MigrationOp>,
    ) {
        diffColumns(table, desired, current, autoOps, manualOps)
        diffIndexes(table, desired, current, autoOps, manualOps)
        diffForeignKeys(table, desired, current, autoOps, manualOps)
    }

    private fun diffColumns(
        table: String,
        desired: NormalizedTable,
        current: NormalizedTable,
        autoOps: MutableList<MigrationOp>,
        manualOps: MutableList<MigrationOp>,
    ) {
        val currentCols = current.columns.associateBy { it.name }
        val desiredCols = desired.columns.associateBy { it.name }

        // New columns
        for ((name, col) in desiredCols) {
            if (name !in currentCols) {
                when {
                    // PK changes can't be done with a simple ADD COLUMN
                    col.primaryKey -> manualOps.add(MigrationOp.AddColumn(table, col))
                    col.nullable -> autoOps.add(MigrationOp.AddColumn(table, col))
                    // A non-null column with a default is safe to add: the
                    // DEFAULT backfills existing rows in the same statement.
                    col.default != null -> autoOps.add(MigrationOp.AddColumn(table, col))
                    // Non-null column without a default requires a backfill.
                    else -> manualOps.add(MigrationOp.AddColumn(table, col))
                }
            }
        }

        // Dropped columns
        for (name in currentCols.keys) {
            if (name !in desiredCols) {
                manualOps.add(MigrationOp.DropColumn(table, name))
            }
        }

        // Changed columns
        for ((name, desiredCol) in desiredCols) {
            val currentCol = currentCols[name] ?: continue

            if (desiredCol.sqlType != currentCol.sqlType) {
                manualOps.add(
                    MigrationOp.AlterColumnType(table, name, currentCol.sqlType, desiredCol.sqlType),
                )
            }

            if (desiredCol.nullable != currentCol.nullable) {
                if (desiredCol.nullable) {
                    manualOps.add(MigrationOp.DropColumnNotNull(table, name))
                } else {
                    manualOps.add(MigrationOp.SetColumnNotNull(table, name))
                }
            }

            if (desiredCol.primaryKey != currentCol.primaryKey) {
                manualOps.add(MigrationOp.AlterPrimaryKey(table, name, added = desiredCol.primaryKey))
            }

            // Column default changes are safe metadata-only ALTERs.
            if (normalizeDefault(desiredCol.default) != normalizeDefault(currentCol.default)) {
                if (desiredCol.default == null) {
                    autoOps.add(MigrationOp.DropColumnDefault(table, name))
                } else {
                    autoOps.add(MigrationOp.SetColumnDefault(table, name, desiredCol.default))
                }
            }
        }
    }

    private fun diffIndexes(
        table: String,
        desired: NormalizedTable,
        current: NormalizedTable,
        autoOps: MutableList<MigrationOp>,
        manualOps: MutableList<MigrationOp>,
    ) {
        // Match indexes by semantic identity: (columns, unique, where) plus the
        // native access method / operator class / storage params, so a
        // btree→hnsw, opclass, or `lists` change is a detected drop+recreate.
        // Predicates are normalized so that PostgreSQL's deparsed form
        // (with outer parens, extra whitespace) matches the user-written form.
        data class IndexKey(
            val columns: List<String>,
            val unique: Boolean,
            val where: String?,
            val using: String?,
            val opclasses: List<String>?,
            val with: Map<String, String>?,
        )

        fun keyOf(it: NormalizedIndex) =
            IndexKey(it.columns, it.unique, normalizeWhere(it.where), it.using, it.opclasses, it.with)

        val currentByKey = current.indexes.associateBy { keyOf(it) }
        val desiredByKey = desired.indexes.associateBy { keyOf(it) }

        // A column added to an existing table with a constant default
        // backfills every existing row to that same value. A new UNIQUE
        // index that includes such a column is therefore unsafe: the
        // constant collapses the tuple's distinctness onto the remaining
        // columns, which carry no uniqueness guarantee (no constraint
        // existed before), so the index can fail to build on real data —
        // and the empty shadow DB never sees it.
        //
        // The one provably-safe shape is an index that also includes a
        // newly-added nullable column with no default: it is NULL for
        // every existing row, and Postgres' NULLS DISTINCT makes all those
        // tuples distinct regardless of the other columns. Such indexes
        // stay auto.
        //
        // Adding a unique index over only pre-existing columns keeps its
        // current behavior (auto) — that failure is data-dependent, not
        // guaranteed, and is the caller's existing responsibility.
        val currentColumnNames = current.columns.mapTo(mutableSetOf()) { it.name }
        val newColumns = desired.columns.filter { it.name !in currentColumnNames }
        val newDefaultedColumns = newColumns.filter { it.default != null }.mapTo(mutableSetOf()) { it.name }
        val newNullableNoDefaultColumns =
            newColumns.filter { it.nullable && it.default == null }.mapTo(mutableSetOf()) { it.name }
        fun isUnsafeNewUniqueIndex(idx: NormalizedIndex): Boolean =
            idx.unique &&
                idx.columns.any { it in newDefaultedColumns } &&
                idx.columns.none { it in newNullableNoDefaultColumns }

        // New indexes
        for ((key, idx) in desiredByKey) {
            val currentIdx = currentByKey[key]
            if (currentIdx == null) {
                if (isUnsafeNewUniqueIndex(idx)) {
                    manualOps.add(MigrationOp.AddIndex(table, idx))
                } else {
                    autoOps.add(MigrationOp.AddIndex(table, idx))
                }
            } else if (idx.name != null && idx.name != currentIdx.name) {
                // Desired has an explicit name that differs from current
                // (which may be null/derived or a different explicit name)
                // — manual drop of the old index + auto add under the new name.
                manualOps.add(MigrationOp.DropIndex(table, key.columns, key.unique, currentIdx.name, currentIdx.where))
                autoOps.add(MigrationOp.AddIndex(table, idx))
            }
        }

        // Dropped indexes
        for ((key, currentIdx) in currentByKey) {
            if (key !in desiredByKey) {
                manualOps.add(MigrationOp.DropIndex(table, key.columns, key.unique, currentIdx.name, currentIdx.where))
            }
        }
    }

    private fun diffForeignKeys(
        table: String,
        desired: NormalizedTable,
        current: NormalizedTable,
        autoOps: MutableList<MigrationOp>,
        manualOps: MutableList<MigrationOp>,
    ) {
        // Match FKs by their ordinally-paired endpoints. A composite FK
        // keys on its full column lists, so it can never silently match
        // a single-column FK that shares its first column.
        data class FkKey(val columns: List<String>, val targetTable: String, val targetColumns: List<String>)

        val currentByKey = current.foreignKeys.associateBy { FkKey(it.columns, it.targetTable, it.targetColumns) }
        val desiredByKey = desired.foreignKeys.associateBy { FkKey(it.columns, it.targetTable, it.targetColumns) }

        for ((key, fk) in desiredByKey) {
            val currentFk = currentByKey[key]
            if (currentFk == null) {
                autoOps.add(MigrationOp.AddForeignKey(table, fk))
            } else if (!fkSemanticsMatch(fk, currentFk)) {
                // Constraint semantics changed — drop old constraint
                // (manual) and add the new one (auto).
                manualOps.add(MigrationOp.DropForeignKey(table, currentFk.columns, currentFk.constraintName))
                autoOps.add(MigrationOp.AddForeignKey(table, fk))
            }
        }

        for ((key, fk) in currentByKey) {
            if (key !in desiredByKey) {
                manualOps.add(MigrationOp.DropForeignKey(table, fk.columns, fk.constraintName))
            }
        }
    }

    /**
     * Whether the desired and current FK agree on every semantic
     * attribute beyond their (already matched) endpoints. ON DELETE and
     * ON UPDATE compare with RESTRICT ≡ NO ACTION — for the immediate,
     * non-deferred constraints entkt creates, the two behave
     * identically. Everything else the catalog reports — MATCH type,
     * deferrability, and `NOT VALID` — must equal what entkt would
     * create: the auto-DDL guard ([entkt.postgres] `ensureForeignKey`)
     * rejects those same twins as constraints "enforcing rules nobody
     * declared", and the migration validator must not be blinder than
     * the driver.
     */
    private fun fkSemanticsMatch(desired: NormalizedForeignKey, current: NormalizedForeignKey): Boolean =
        actionsEquivalent(desired.onDelete, current.onDelete) &&
            actionsEquivalent(desired.onUpdate, current.onUpdate) &&
            desired.matchType == current.matchType &&
            desired.deferrable == current.deferrable &&
            desired.initiallyDeferred == current.initiallyDeferred &&
            desired.validated == current.validated

    private fun actionsEquivalent(a: FkAction, b: FkAction): Boolean =
        a == b || (a in RESTRICT_LIKE && b in RESTRICT_LIKE)

    private companion object {
        /** Immediate RESTRICT and NO ACTION are behaviorally identical. */
        val RESTRICT_LIKE = setOf(FkAction.RESTRICT, FkAction.NO_ACTION)
    }

    /**
     * Sort ops in dependency order:
     * 0. CreateExtension (the type a new column uses must exist first)
     * 1. CreateTable (to satisfy FK targets)
     * 2. AddColumn
     * 3. AddIndex (FK targets may require a unique index)
     * 4. AddForeignKey
     */
    private fun sortOps(ops: List<MigrationOp>): List<MigrationOp> {
        fun priority(op: MigrationOp): Int = when (op) {
            is MigrationOp.CreateExtension -> -1
            is MigrationOp.CreateTable -> 0
            is MigrationOp.AddColumn -> 1
            is MigrationOp.AddIndex -> 2
            is MigrationOp.AddForeignKey -> 3
            else -> 4
        }
        return ops.sortedBy { priority(it) }
    }
}
