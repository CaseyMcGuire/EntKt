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

/** Controls how [MigrationSqlRenderer] emits DDL. */
enum class RenderMode {
    /** Dev mode: emit IF NOT EXISTS where supported, idempotent. */
    DEV,
    /** Prod migration file: no IF NOT EXISTS, fail loudly on drift. */
    MIGRATION_FILE,
}

/** Renders [MigrationOp] values to SQL DDL strings. */
interface MigrationSqlRenderer {
    fun render(op: MigrationOp, mode: RenderMode = RenderMode.MIGRATION_FILE): List<String>
}

/** Executes raw SQL statements against a database (for applying migrations). */
interface MigrationExecutor {
    /** Execute a list of SQL statements in a single transaction. */
    fun execute(statements: List<String>)

    /**
     * Execute SQL statements and record the migration version atomically
     * in a single transaction. If the process crashes after DDL succeeds
     * but before the version is recorded, the next run would re-apply the
     * migration — so both must commit together.
     */
    fun executeAndRecord(statements: List<String>, version: String, checksum: String)

    /**
     * Execute a raw SQL script (may contain multiple statements,
     * PL/pgSQL blocks, etc.) and record the migration version
     * atomically. The executor sends the script whole — no splitting.
     */
    fun executeScriptAndRecord(script: String, version: String, checksum: String)

    /** Get the set of already-applied migration versions with their checksums. */
    fun appliedVersions(): Map<String, String>
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
