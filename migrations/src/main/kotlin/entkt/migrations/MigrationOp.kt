package entkt.migrations

/**
 * A single schema change detected by [SchemaDiffer]. Additive ops
 * (v1: [CreateTable], [AddColumn], [AddIndex], [AddForeignKey]) can be
 * auto-generated into migration files. Destructive or complex ops are
 * detected but classified as manual — the user must write the DDL
 * themselves.
 */
sealed interface MigrationOp {

    // ---- Auto-generated in v1 ----

    /**
     * `CREATE EXTENSION IF NOT EXISTS <name>`. Sorted before [CreateTable] so a
     * column whose type the extension provides (e.g. pgvector's `vector`) can be
     * created. Emitted when a new table/column needs the extension.
     */
    data class CreateExtension(val name: String) : MigrationOp

    /** Emits CREATE TABLE with columns + PK only. Indexes and FKs are separate ops. */
    data class CreateTable(val table: NormalizedTable) : MigrationOp

    data class AddColumn(val table: String, val column: NormalizedColumn) : MigrationOp

    data class AddIndex(val table: String, val index: NormalizedIndex) : MigrationOp

    /**
     * Can fail if existing rows violate the constraint. The renderer
     * should emit a comment warning about this for existing tables.
     */
    data class AddForeignKey(val table: String, val fk: NormalizedForeignKey) : MigrationOp

    /**
     * Set or change a column's `DEFAULT`. Safe metadata-only change in
     * PostgreSQL — it does not rewrite existing rows. [default] is the
     * rendered SQL default expression (e.g. `'ACTIVE'`, `42`, `now()`).
     */
    data class SetColumnDefault(val table: String, val columnName: String, val default: String) : MigrationOp

    /** Drop a column's `DEFAULT`. Safe metadata-only change. */
    data class DropColumnDefault(val table: String, val columnName: String) : MigrationOp

    // ---- Detected but not auto-generated in v1 ----

    data class DropTable(val tableName: String) : MigrationOp

    data class DropColumn(val table: String, val columnName: String) : MigrationOp

    data class AlterColumnType(
        val table: String,
        val columnName: String,
        val oldType: String,
        val newType: String,
    ) : MigrationOp

    data class SetColumnNotNull(val table: String, val columnName: String) : MigrationOp

    data class DropColumnNotNull(val table: String, val columnName: String) : MigrationOp

    data class DropIndex(val table: String, val columns: List<String>, val unique: Boolean, val name: String?, val where: String? = null) : MigrationOp

    data class AlterPrimaryKey(val table: String, val columnName: String, val added: Boolean) : MigrationOp

    data class DropForeignKey(val table: String, val columns: List<String>, val constraintName: String?) : MigrationOp
}

/**
 * Result of [SchemaDiffer.diff]: additive ops safe to generate, plus
 * destructive ops that require manual migration.
 */
data class DiffResult(
    /** Additive ops safe to generate, in dependency order. */
    val ops: List<MigrationOp>,
    /** Destructive ops requiring manual migration. */
    val manual: List<MigrationOp>,
)
