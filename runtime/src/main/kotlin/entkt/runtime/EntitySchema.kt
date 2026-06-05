package entkt.runtime

import entkt.schema.ColumnStorage
import entkt.schema.FieldType
import entkt.schema.OnDelete

/**
 * Runtime metadata describing one entity to a [Driver]: enough to look
 * up rows by id, evaluate predicates, and resolve edges into joins.
 *
 * The codegen emits one of these per generated entity (e.g.
 * `UserSchema`) and the generated repo registers it with the driver in
 * its `init` block. Drivers MUST NOT depend on the schema DSL itself —
 * everything they need at runtime lives here.
 */
data class EntitySchema(
    /** SQL-style table name (e.g. `"users"`). */
    val table: String,
    /** Column name of the primary key (almost always `"id"`). */
    val idColumn: String,
    /**
     * Strategy used to generate ids on insert when the caller doesn't
     * supply one. The driver consults this when building a row.
     */
    val idStrategy: IdStrategy,
    /**
     * Every column the entity carries, in declaration order: id first,
     * then declared/mixin fields, then any synthesized edge FKs. Carries
     * enough type information for SQL drivers to emit DDL without
     * reflecting on the generated entity classes.
     */
    val columns: List<ColumnMetadata>,
    /**
     * Outgoing edges keyed by the *local* edge name as declared on this
     * entity (so `User.edges["posts"]` describes how to walk to Post
     * rows). Each entry is the join recipe — see [EdgeMetadata].
     */
    val edges: Map<String, EdgeMetadata>,
    /**
     * Composite indexes declared on the entity. Single-column unique
     * constraints live on [ColumnMetadata.unique] instead.
     */
    val indexes: List<IndexMetadata> = emptyList(),
)

/**
 * One column on a registered entity. Drivers that need to materialize
 * a table (Postgres, SQLite, etc.) read [type] and [nullable] to pick
 * the right SQL type and constraints.
 */
data class ColumnMetadata(
    /** snake_case column name as it appears in `Driver` row maps. */
    val name: String,
    /** Logical type, mapped to a SQL type by each driver. */
    val type: FieldType,
    /** Whether the column accepts NULL values. */
    val nullable: Boolean,
    /** True iff this column is the entity's primary key. */
    val primaryKey: Boolean = false,
    /** True if this column carries a single-column UNIQUE constraint. */
    val unique: Boolean = false,
    /** Foreign key reference, if this column points at another table's id. */
    val references: ForeignKeyRef? = null,
    /** Documentation comment from the schema DSL, if any. */
    val comment: String? = null,
    /**
     * Default value declared on the schema DSL (`.default(...)` /
     * `.defaultNow()`), or null when none. Holds the raw Kotlin value —
     * a primitive, an enum constant, or the sentinel `"now"` for a
     * `TIME` `defaultNow()` — which each
     * [entkt.migrations.TypeMapper] converts into a SQL `DEFAULT`
     * expression for its dialect.
     *
     * Consumed only on the migration path: [Driver]s never apply column
     * defaults at runtime (the generated `create()` bakes them in from
     * `Field.default` directly), so the generated `SCHEMA` literal leaves
     * this null. The migration builder
     * (`entkt.codegen.buildEntitySchemas`) populates it so
     * `NormalizedSchema.fromEntitySchemas` can emit `DEFAULT` clauses.
     */
    val default: Any? = null,
    /**
     * Native storage metadata (Postgres `pgvector`, etc.) when this column is
     * more than a plain [type] → SQL-type mapping. Null for ordinary columns.
     * Drivers dispatch bind/decode on `ColumnStorage.Native.codec`; migrations
     * render its `sqlType` and required extension. RFC "Native Database Column
     * Types", §5.
     */
    val storage: ColumnStorage? = null,
)

/**
 * Describes a foreign key reference from one column to another table's
 * column. SQL drivers use this to emit `REFERENCES` and `ON DELETE`
 * clauses.
 */
data class ForeignKeyRef(
    /** Target table name. */
    val table: String,
    /** Target column name (usually `"id"`). */
    val column: String,
    /**
     * Referential action when the referenced row is deleted.
     * `null` means the driver infers from column nullability
     * (SET NULL for nullable, RESTRICT for required).
     */
    val onDelete: OnDelete? = null,
)

/**
 * A composite index on one or more columns. Single-column unique
 * constraints are expressed directly on [ColumnMetadata.unique]; this
 * type covers multi-column and non-unique indexes declared via the
 * schema DSL's `index(...)` method.
 */
data class IndexMetadata(
    /** Column names in index order. */
    val columns: List<String>,
    /** Whether this is a UNIQUE index. */
    val unique: Boolean = false,
    /** The index name. */
    val name: String,
    /** Optional SQL WHERE clause for partial indexes (e.g. `"active = true"`). */
    val where: String? = null,
    /**
     * Index access method, e.g. `"hnsw"` / `"ivfflat"` for `pgvector`. Null =
     * the dialect default (btree). RFC "Native Database Column Types", §6.
     */
    val using: String? = null,
    /**
     * Per-column operator class, e.g. `["vector_cosine_ops"]`. Null for btree.
     */
    val opclasses: List<String>? = null,
    /**
     * Index storage parameters rendered as `WITH (k = v, ...)`, e.g. IVFFlat
     * `{"lists": "100"}` or HNSW `{"m": "16"}`. Null/empty = no `WITH` clause.
     */
    val with: Map<String, String>? = null,
)

/**
 * How a single edge resolves into a join between two tables.
 *
 * Both directions of an edge use the same shape — given a row from
 * the *source* table, find target rows where
 * `target[targetColumn] == source[sourceColumn]`.
 *
 * Examples for `User`/`Post` (Post owns the FK as `author_id`):
 * - `User.posts`: `targetTable=posts, sourceColumn=id, targetColumn=author_id`
 * - `Post.author`: `targetTable=users, sourceColumn=author_id, targetColumn=id`
 *
 * Many-to-many edges set the junction fields. For `Group → users`
 * through `user_groups`:
 * - `targetTable=users, sourceColumn=id, targetColumn=id`
 * - `junctionTable=user_groups, junctionSourceColumn=group_id,
 *    junctionTargetColumn=user_id`
 */
data class EdgeMetadata(
    val targetTable: String,
    val sourceColumn: String,
    val targetColumn: String,
    /** Junction table for M2M edges; null for direct edges. */
    val junctionTable: String? = null,
    /** Column on the junction table that references the source. */
    val junctionSourceColumn: String? = null,
    /** Column on the junction table that references the target. */
    val junctionTargetColumn: String? = null,
    /** Documentation comment from the schema DSL, if any. */
    val comment: String? = null,
)

/**
 * How a [Driver] should populate the id column when [Driver.insert] is
 * called without one. UUIDs are minted by the generated `save()` (so
 * the caller can see the id without a round trip), but numeric auto-ids
 * are assigned by the driver itself — only the driver knows which ids
 * are already taken.
 */
enum class IdStrategy {
    /** Driver assigns the next available `Int` per table. */
    AUTO_INT,
    /** Driver assigns the next available `Long` per table. */
    AUTO_LONG,
    /** Caller (generated `save()`) provides a `UUID`. */
    CLIENT_UUID,
    /** Caller provides any id of any type — the driver just stores it. */
    EXPLICIT,
}
