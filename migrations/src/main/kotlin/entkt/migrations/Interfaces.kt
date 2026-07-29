@file:JvmName("MigrationsKt")

package entkt.migrations

import entkt.runtime.driver.IdStrategy
import entkt.schema.ColumnStorage
import entkt.schema.FieldType

const val MANUAL_STEPS_MARKER = "!! MANUAL STEPS REQUIRED !!"

/**
 * Maps [FieldType] values to driver-specific SQL type strings, and
 * canonicalizes introspected type names for comparison.
 */
interface TypeMapper {
    /**
     * Map a field type to its canonical SQL type string. [storage] carries
     * native-column metadata (e.g. pgvector's `vector(1536)`) when the type
     * is more than a plain [FieldType] mapping; defaulted for backward
     * compatibility.
     */
    fun sqlTypeFor(
        fieldType: FieldType,
        isPrimaryKey: Boolean,
        idStrategy: IdStrategy,
        storage: ColumnStorage? = null,
    ): String

    /** Canonicalize an introspected type for comparison (e.g. "int4" → "integer"). */
    fun canonicalize(rawSqlType: String): String

    /**
     * Normalize an identifier to the database's limits (e.g. Postgres
     * truncates to 63 bytes). Called on name/constraintName values
     * during schema normalization so the desired schema matches what the
     * database actually stores. Default: identity (no limit).
     */
    fun normalizeIdentifier(name: String): String = name

    /**
     * Render a schema-DSL default [value] into a SQL `DEFAULT` expression
     * for this dialect, or null when [value] is null. The result is baked
     * into [NormalizedColumn.default] and emitted in `CREATE TABLE` /
     * `ADD COLUMN` DDL. Default: [formatSqlDefault] (PostgreSQL form).
     */
    fun formatDefault(fieldType: FieldType, value: Any?): String? = formatSqlDefault(fieldType, value)
}

/**
 * Introspects a live database to build a [NormalizedSchema] for the
 * given managed tables.
 */
interface DatabaseIntrospector {
    /**
     * @param managedTableNames the full managed surface: desired table names
     *   ∪ previously known table names (from snapshot). This ensures tables
     *   that were removed from the schema but still exist in the DB are
     *   visible to the differ as DropTable candidates. Unrelated app tables
     *   in the same database are ignored.
     */
    fun introspect(managedTableNames: Set<String>): NormalizedSchema
}

/** Renders [MigrationOp] values to SQL DDL strings. */
interface MigrationSqlRenderer {
    fun render(op: MigrationOp): List<String>
}

/**
 * Controls behavior when [SchemaDiffer] detects manual (destructive)
 * operations during migration generation.
 */
enum class ManualMode {
    /** Fail if manual ops are detected. Safe default. */
    FAIL,
    /**
     * Acknowledge manual ops and advance anyway. The migration file
     * includes a checklist of unresolved ops and a guard statement
     * that prevents the runner from applying it until manual steps
     * are completed.
     */
    ACKNOWLEDGE_AND_ADVANCE,
}

/** Human-readable description of a migration operation. */
fun describeOp(op: MigrationOp): String = when (op) {
    is MigrationOp.CreateExtension -> "CreateExtension: ${op.name}"
    is MigrationOp.CreateTable -> "CreateTable: ${op.table.name}"
    is MigrationOp.AddColumn -> "AddColumn: ${op.table}.${op.column.name} (${op.column.sqlType}${if (op.column.nullable) "" else " NOT NULL"})"
    is MigrationOp.AddIndex -> {
        val cols = op.index.columns.joinToString(", ")
        val u = if (op.index.unique) " unique" else ""
        val w = if (op.index.where != null) " WHERE ${op.index.where}" else ""
        "AddIndex: ${op.table} ($cols)$u$w"
    }
    is MigrationOp.AddForeignKey ->
        "AddForeignKey: ${op.table}.${op.fk.columns.joinToString(", ")} -> " +
            "${op.fk.targetTable}.${op.fk.targetColumns.joinToString(", ")}"
    is MigrationOp.SetColumnDefault -> "SetColumnDefault: ${op.table}.${op.columnName} DEFAULT ${op.default}"
    is MigrationOp.DropColumnDefault -> "DropColumnDefault: ${op.table}.${op.columnName}"
    is MigrationOp.DropTable -> "DropTable: ${op.tableName}"
    is MigrationOp.DropColumn -> "DropColumn: ${op.table}.${op.columnName}"
    is MigrationOp.AlterColumnType -> "AlterColumnType: ${op.table}.${op.columnName} (${op.oldType} -> ${op.newType})"
    is MigrationOp.SetColumnNotNull -> "SetColumnNotNull: ${op.table}.${op.columnName}"
    is MigrationOp.DropColumnNotNull -> "DropColumnNotNull: ${op.table}.${op.columnName}"
    is MigrationOp.AlterPrimaryKey -> {
        val action = if (op.added) "add to" else "remove from"
        "AlterPrimaryKey: $action ${op.table}.${op.columnName} (requires DROP CONSTRAINT + ADD PRIMARY KEY)"
    }
    is MigrationOp.DropIndex -> {
        val cols = op.columns.joinToString(", ")
        val u = if (op.unique) " unique" else ""
        val name = if (op.name != null) " [${op.name}]" else ""
        "DropIndex: ${op.table} ($cols)$u$name"
    }
    is MigrationOp.DropForeignKey -> {
        val name = if (op.constraintName != null) " [${op.constraintName}]" else ""
        "DropForeignKey: ${op.table}.${op.columns.joinToString(", ")}$name"
    }
}

/**
 * Extract the integer version number from a filename like `V3__foo.sql`.
 * Returns null if the name doesn't match.
 */
fun parseVersionNumber(filename: String): Int? {
    val match = Regex("^V(\\d+)").find(filename) ?: return null
    return match.groupValues[1].toIntOrNull()
}

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
