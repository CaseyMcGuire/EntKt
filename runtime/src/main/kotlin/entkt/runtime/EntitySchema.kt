package entkt.runtime

import entkt.schema.FieldType

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
 * the right SQL type and constraints; the in-memory driver ignores
 * everything but [name].
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
)

/**
 * A composite index on one or more columns. Single-column unique
 * constraints are expressed directly on [ColumnMetadata.unique]; this
 * type covers multi-column and non-unique indexes declared via the
 * schema DSL's `indexes { }` block.
 */
data class IndexMetadata(
    /** Column names in index order. */
    val columns: List<String>,
    /** Whether this is a UNIQUE index. */
    val unique: Boolean = false,
    /** Optional explicit name; drivers derive one if null. */
    val storageKey: String? = null,
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
 */
data class EdgeMetadata(
    val targetTable: String,
    val sourceColumn: String,
    val targetColumn: String,
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
