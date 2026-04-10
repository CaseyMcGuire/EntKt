package entkt.runtime

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
    /** Every column the entity carries, by snake_case column name. */
    val columns: List<String>,
    /**
     * Outgoing edges keyed by the *local* edge name as declared on this
     * entity (so `User.edges["posts"]` describes how to walk to Post
     * rows). Each entry is the join recipe — see [EdgeMetadata].
     */
    val edges: Map<String, EdgeMetadata>,
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