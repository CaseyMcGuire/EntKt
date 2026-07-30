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
                // CreateTable emits columns + PK only; indexes and FKs are separate.
                // Indexes dedup by semantic key: a `.unique()` column plus an
                // explicit unique index on the same column are one constraint,
                // and creating both would seed the duplicate-index shape the
                // shared-table diff below has to clean up. associateBy keeps
                // the LAST declaration per key — the same representative
                // [diffIndexes]' desired side keeps — so the index this path
                // creates is the one every later diff expects; disagreeing
                // (e.g. distinctBy's keep-first) would create the unnamed
                // `.unique()` twin here and then demand its named sibling on
                // the very next run.
                autoOps.add(MigrationOp.CreateTable(table))
                for (idx in table.indexes.associateBy { indexKey(table, it) }.values) {
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

    /**
     * Semantic identity of an index: (columns, unique, normalized
     * where) plus the native access method / operator class / storage
     * params, so a btree→hnsw, opclass, or `lists` change is a
     * detected drop+recreate. Predicates are normalized (type-aware)
     * so PostgreSQL's deparsed form matches the user-written form; the
     * name is a rendering detail, not identity.
     */
    private data class IndexKey(
        val columns: List<String>,
        val unique: Boolean,
        val where: String?,
        val using: String?,
        val opclasses: List<String>?,
        val with: Map<String, String>?,
    )

    private fun indexKey(idx: NormalizedIndex, columnTypes: Map<String, String>): IndexKey =
        IndexKey(idx.columns, idx.unique, normalizeWhere(idx.where, columnTypes), idx.using, idx.opclasses, idx.with)

    private fun indexKey(table: NormalizedTable, idx: NormalizedIndex): IndexKey =
        indexKey(idx, table.columns.associate { it.name.lowercase() to it.sqlType })

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
        // Predicate normalization is type-aware: whether a textual cast
        // is a deparser decoration or a semantic change (citext) depends
        // on the column's declared type. One shared map keeps both
        // sides' keys comparable; where the sides disagree on a type,
        // desired wins here and the disagreement itself is already
        // flagged by diffColumns.
        val columnTypes = buildMap {
            for (c in current.columns) put(c.name.lowercase(), c.sqlType)
            for (c in desired.columns) put(c.name.lowercase(), c.sqlType)
        }

        // The current side groups rather than keys uniquely: Postgres
        // allows several identically-defined indexes under different
        // names, and collapsing them would let one hide behind the
        // declared index — never dropped, never flagged — or, in the
        // other introspection order, produce a CREATE for a name that
        // already exists. Same reasoning as [diffForeignKeys]' groupBy.
        // The desired side dedups deliberately: two declarations of the
        // same semantic index need one live index.
        val currentByKey = current.indexes.groupBy { indexKey(it, columnTypes) }
        val desiredByKey = desired.indexes.associateBy { indexKey(it, columnTypes) }

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
            val group = currentByKey[key].orEmpty()
            if (group.isEmpty()) {
                if (isUnsafeNewUniqueIndex(idx)) {
                    manualOps.add(MigrationOp.AddIndex(table, idx))
                } else {
                    autoOps.add(MigrationOp.AddIndex(table, idx))
                }
                continue
            }
            val named = if (idx.name != null) group.find { it.name == idx.name } else null
            if (idx.name != null && named == null) {
                // Desired has an explicit name that none of the live
                // definitions carry — manual drop of every live twin +
                // auto add under the declared name. Dropping the whole
                // group is what keeps the CREATE collision-free.
                for (currentIdx in group) {
                    manualOps.add(MigrationOp.DropIndex(table, key.columns, key.unique, currentIdx.name, currentIdx.where))
                }
                autoOps.add(MigrationOp.AddIndex(table, idx))
            } else {
                // One live index satisfies the declaration; any twins on
                // the same definition are undeclared duplicates. Survivor
                // choice is by name (introspection order is unspecified):
                // the declared name when there is one, else the first by
                // name for determinism.
                val keep = named ?: group.minByOrNull { it.name ?: "" }!!
                for (currentIdx in group) {
                    if (currentIdx !== keep) {
                        manualOps.add(MigrationOp.DropIndex(table, key.columns, key.unique, currentIdx.name, currentIdx.where))
                    }
                }
            }
        }

        // Dropped indexes
        for ((key, group) in currentByKey) {
            if (key !in desiredByKey) {
                for (currentIdx in group) {
                    manualOps.add(MigrationOp.DropIndex(table, key.columns, key.unique, currentIdx.name, currentIdx.where))
                }
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
        //
        // The current side groups rather than keys uniquely: Postgres
        // allows several live constraints on identical endpoints
        // (differing in actions, deferrability, or NOT VALID — all
        // simultaneously enforced), and collapsing them to one would
        // let a correct constraint mask an undeclared twin (e.g. an
        // extra ON DELETE CASCADE) so it never surfaced as drift.
        data class FkKey(val columns: List<String>, val targetTable: String, val targetColumns: List<String>)

        val currentByKey = current.foreignKeys.groupBy { FkKey(it.columns, it.targetTable, it.targetColumns) }
        val desiredByKey = desired.foreignKeys.associateBy { FkKey(it.columns, it.targetTable, it.targetColumns) }

        for ((key, fk) in desiredByKey) {
            val currentFks = currentByKey[key].orEmpty()
            if (currentFks.isEmpty()) {
                autoOps.add(MigrationOp.AddForeignKey(table, fk))
                continue
            }
            val matched = currentFks.firstOrNull { fkSemanticsMatch(fk, it) }
            if (matched == null) {
                // Constraint semantics changed — drop every constraint
                // on these endpoints (manual) and add the new one (auto).
                for (currentFk in currentFks) {
                    manualOps.add(MigrationOp.DropForeignKey(table, currentFk.columns, currentFk.constraintName))
                }
                autoOps.add(MigrationOp.AddForeignKey(table, fk))
            } else {
                // One constraint matches the declaration; any others on
                // the same endpoints enforce rules nobody declared.
                for (currentFk in currentFks) {
                    if (currentFk !== matched) {
                        manualOps.add(MigrationOp.DropForeignKey(table, currentFk.columns, currentFk.constraintName))
                    }
                }
            }
        }

        for ((key, fks) in currentByKey) {
            if (key !in desiredByKey) {
                for (fk in fks) {
                    manualOps.add(MigrationOp.DropForeignKey(table, fk.columns, fk.constraintName))
                }
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
