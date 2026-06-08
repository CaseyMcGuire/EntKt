package entkt.schema

enum class FieldType {
    STRING,
    TEXT,
    BOOL,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    TIME,
    UUID,
    BYTES,
    ENUM,

    /**
     * Postgres `pgvector` column (RFC "Native Database Column Types"). The
     * specifics live on the column's [ColumnStorage.Native]; this is just the
     * discriminator codegen and the driver switch on. Declared via the
     * `entkt.postgres.vector.postgresVector(name, dimensions)` extension.
     */
    PGVECTOR,

    /**
     * A typed JSON column (RFC "Typed JSON Fields"). The Kotlin class and its
     * kotlinx serializer live on the column's `JsonColumnMetadata`; this is just
     * the discriminator codegen and the driver switch on. Declared via
     * `json(name, KClass)` / `json<T>(name)`; stored as Postgres `jsonb`.
     */
    JSON,
}