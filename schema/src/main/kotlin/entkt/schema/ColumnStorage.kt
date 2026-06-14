package entkt.schema

/**
 * Describes how a field is stored when the storage is richer than a plain
 * [FieldType] → SQL-type mapping.
 *
 * Defined in the schema module so both the schema-side [Field] and the runtime
 * `ColumnMetadata` (which already depends on `entkt.schema`) can carry one
 * definition. Drivers and migrations read it; codegen threads it through.
 */
sealed interface ColumnStorage {
    /**
     * A dialect-native column (Phase 1: Postgres `pgvector`). The driver owns
     * bind/decode for it, dispatched on [codec]; migrations render [sqlType]
     * verbatim and emit [requiredExtension] if set.
     */
    data class Native(
        /** Dialect that owns this type, e.g. `"postgres"`. */
        val dialect: String,
        /** Bare type name, e.g. `"vector"`. */
        val typeName: String,
        /** Full SQL type rendered into DDL verbatim, e.g. `"vector(1536)"`. */
        val sqlType: String,
        /** Driver bind/decode dispatch key, e.g. `"postgres.vector"`. */
        val codec: String,
        /** Extension this column needs, e.g. `"vector"` → `CREATE EXTENSION`. Null if none. */
        val requiredExtension: String?,
        /** Vector dimensionality — used by the write-time check and the migration diff. */
        val dimensions: Int,
    ) : ColumnStorage
}
