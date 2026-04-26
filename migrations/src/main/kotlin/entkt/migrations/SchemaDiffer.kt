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
                if (col.primaryKey) {
                    // PK changes can't be done with a simple ADD COLUMN
                    manualOps.add(MigrationOp.AddColumn(table, col))
                } else if (col.nullable) {
                    autoOps.add(MigrationOp.AddColumn(table, col))
                } else {
                    // Non-null column on existing table requires a default or backfill
                    manualOps.add(MigrationOp.AddColumn(table, col))
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
        }
    }

    private fun diffIndexes(
        table: String,
        desired: NormalizedTable,
        current: NormalizedTable,
        autoOps: MutableList<MigrationOp>,
        manualOps: MutableList<MigrationOp>,
    ) {
        // Match indexes by semantic identity: (columns, unique, where)
        // Predicates are normalized so that PostgreSQL's deparsed form
        // (with outer parens, extra whitespace) matches the user-written form.
        data class IndexKey(val columns: List<String>, val unique: Boolean, val where: String?)

        val currentByKey = current.indexes.associateBy { IndexKey(it.columns, it.unique, normalizeWhere(it.where)) }
        val desiredByKey = desired.indexes.associateBy { IndexKey(it.columns, it.unique, normalizeWhere(it.where)) }

        // New indexes
        for ((key, idx) in desiredByKey) {
            val currentIdx = currentByKey[key]
            if (currentIdx == null) {
                autoOps.add(MigrationOp.AddIndex(table, idx))
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
        // Match FKs by (column, targetTable, targetColumn)
        data class FkKey(val column: String, val targetTable: String, val targetColumn: String)

        val currentByKey = current.foreignKeys.associateBy { FkKey(it.column, it.targetTable, it.targetColumn) }
        val desiredByKey = desired.foreignKeys.associateBy { FkKey(it.column, it.targetTable, it.targetColumn) }

        for ((key, fk) in desiredByKey) {
            val currentFk = currentByKey[key]
            if (currentFk == null) {
                autoOps.add(MigrationOp.AddForeignKey(table, fk))
            } else if (effectiveOnDelete(fk) != effectiveOnDelete(currentFk)) {
                // ON DELETE behavior changed — drop old constraint (manual)
                // and add the new one (auto).
                manualOps.add(MigrationOp.DropForeignKey(table, currentFk.column, currentFk.constraintName))
                autoOps.add(MigrationOp.AddForeignKey(table, fk))
            }
        }

        for ((key, fk) in currentByKey) {
            if (key !in desiredByKey) {
                manualOps.add(MigrationOp.DropForeignKey(table, fk.column, fk.constraintName))
            }
        }
    }

    /**
     * Resolve the effective ON DELETE action for a FK.
     * When [NormalizedForeignKey.onDelete] is null, the default is
     * inferred from column nullability (SET_NULL for nullable, RESTRICT
     * for required). This ensures a null `onDelete` compares equal to
     * its inferred equivalent from introspection.
     */
    private fun effectiveOnDelete(fk: NormalizedForeignKey): OnDelete =
        fk.onDelete ?: if (fk.columnNullable) OnDelete.SET_NULL else OnDelete.RESTRICT

    /**
     * Sort ops in dependency order:
     * 1. CreateTable (to satisfy FK targets)
     * 2. AddColumn
     * 3. AddIndex (FK targets may require a unique index)
     * 4. AddForeignKey
     */
    private fun sortOps(ops: List<MigrationOp>): List<MigrationOp> {
        fun priority(op: MigrationOp): Int = when (op) {
            is MigrationOp.CreateTable -> 0
            is MigrationOp.AddColumn -> 1
            is MigrationOp.AddIndex -> 2
            is MigrationOp.AddForeignKey -> 3
            else -> 4
        }
        return ops.sortedBy { priority(it) }
    }
}
