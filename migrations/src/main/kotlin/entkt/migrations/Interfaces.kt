package entkt.migrations

import entkt.runtime.IdStrategy
import entkt.schema.FieldType

/**
 * Maps [FieldType] values to driver-specific SQL type strings, and
 * canonicalizes introspected type names for comparison.
 */
interface TypeMapper {
    /** Map a field type to its canonical SQL type string. */
    fun sqlTypeFor(fieldType: FieldType, isPrimaryKey: Boolean, idStrategy: IdStrategy): String

    /** Canonicalize an introspected type for comparison (e.g. "int4" → "integer"). */
    fun canonicalize(rawSqlType: String): String

    /**
     * Normalize an identifier to the database's limits (e.g. Postgres
     * truncates to 63 bytes). Called on name/constraintName values
     * during schema normalization so the desired schema matches what the
     * database actually stores. Default: identity (no limit).
     */
    fun normalizeIdentifier(name: String): String = name
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
 * operations during [Migrator.plan].
 */
enum class ManualMode {
    /** Fail if manual ops are detected. Safe default. */
    FAIL,
    /**
     * Acknowledge manual ops and advance the snapshot anyway.
     * The migration file includes a `-- !! MANUAL STEPS REQUIRED !!`
     * header with a structured checklist of unresolved ops.
     */
    ACKNOWLEDGE_AND_ADVANCE,
}
