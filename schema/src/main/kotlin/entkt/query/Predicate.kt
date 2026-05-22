package entkt.query

/**
 * A predicate in a query's WHERE clause. Compound predicates are built
 * from leaves with [and]/[or]:
 *
 * ```
 * (User.active eq true) and (User.age gte 18)
 * ```
 *
 * The phantom type parameter [E] is the entity scope the predicate
 * filters. It carries no runtime data; it's a compile-time witness so
 * `Predicate<Post>` can't be passed where `Predicate<User>` is expected.
 * `and` / `or` preserve `E`, so combining two predicates from different
 * entity scopes fails to compile (variance is invariant on purpose).
 *
 * Constructors marked `@EntktInternal` ([HasEdge], [HasEdgeWith],
 * [HasM2MEdgeFrom]) participate in the edge-predicate walker's
 * edge-name-validated unchecked-cast soundness story — see
 * `docs/possible-features/query/phantom-typed-query-scopes.md`
 * §"Edge-Predicate Walker" / §"Constructor Visibility". Application
 * code constructs them via the generated `EdgeRef.has { ... }` /
 * `EdgeRef.exists()` surface.
 */
sealed class Predicate<E : Any> {
    infix fun and(other: Predicate<E>): Predicate<E> = And(this, other)
    infix fun or(other: Predicate<E>): Predicate<E> = Or(this, other)

    /** A single field/op/value comparison. */
    data class Leaf<E : Any>(
        val field: String,
        val op: Op,
        val value: Any?,
    ) : Predicate<E>()

    /** Conjunction of two predicates. */
    data class And<E : Any>(
        val left: Predicate<E>,
        val right: Predicate<E>,
    ) : Predicate<E>()

    /** Disjunction of two predicates. */
    data class Or<E : Any>(
        val left: Predicate<E>,
        val right: Predicate<E>,
    ) : Predicate<E>()

    /**
     * "Has any related row across [edge]." For required edges this is
     * trivially true; for optional edges it filters out rows whose FK is
     * null (or whose join-table row is missing).
     *
     * Not a `data class` — Kotlin would auto-generate a public
     * `copy(...)` whose visibility we cannot annotate with
     * `@EntktInternal`, reopening the walker-cast fabrication hole
     * (RFC §"Constructor Visibility"). A caller could legitimately
     * obtain a `HasEdge<User>("posts")` via `User.posts.exists()`,
     * cast back to `Predicate.HasEdge<User>`, and call
     * `copy(edge = "comments")` without opting in. As a regular
     * class with one `@EntktInternal` entry point, that bypass is
     * closed.
     */
    class HasEdge<E : Any> @EntktInternal constructor(
        val edge: String,
    ) : Predicate<E>() {
        override fun toString(): String = "HasEdge(edge=$edge)"
        override fun equals(other: Any?): Boolean =
            other is HasEdge<*> && other.edge == edge
        override fun hashCode(): Int = edge.hashCode()
    }

    /**
     * "Has at least one related row across [edge] that matches [inner]."
     * Lowered by the runtime into an EXISTS subquery (or join, depending
     * on the driver). [inner] is the AND of every where added to the
     * inner block.
     *
     * The [Target] type parameter is the inner predicate's scope: an
     * `EdgeRef<User, Post, …>.has { … }` block produces
     * `HasEdgeWith<User, Post>("posts", inner: Predicate<Post>)`. The
     * generated edge-predicate walker uses the edge name as a runtime
     * witness to recover `Target` for an unchecked cast on the inner
     * predicate.
     *
     * Not a `data class` — same copy-bypass rationale as [HasEdge].
     */
    class HasEdgeWith<E : Any, Target : Any> @EntktInternal constructor(
        val edge: String,
        val inner: Predicate<Target>,
    ) : Predicate<E>() {
        override fun toString(): String = "HasEdgeWith(edge=$edge, inner=$inner)"
        override fun equals(other: Any?): Boolean =
            other is HasEdgeWith<*, *> && other.edge == edge && other.inner == inner
        override fun hashCode(): Int = 31 * edge.hashCode() + inner.hashCode()
    }

    /**
     * "Is the M2M target of at least one row in [sourceTable] (matched
     * by [sourceFilter]) via the forward edge [edgeName] declared on
     * [sourceTable]."
     *
     * Used by generated query traversal so the predicate can compile to
     * an EXISTS subquery in either direction without referencing a
     * synthesized reverse-edge name on the target's `SCHEMA.edges`.
     * Forward query traversal (e.g. `postQuery.queryTags()`) emits this
     * variant against the candidate Tag with `sourceTable = "posts"`,
     * `edgeName = "tags"` — the runtime walks the junction backwards
     * using the source schema's forward-edge metadata.
     *
     * `sourceFilter == null` means "any related row" (mirrors
     * [HasEdge] vs [HasEdgeWith]).
     *
     * [Source] is the source-table entity scope, carried through
     * `sourceFilter: Predicate<Source>?`.
     *
     * Not a `data class` — same copy-bypass rationale as [HasEdge].
     */
    class HasM2MEdgeFrom<E : Any, Source : Any> @EntktInternal constructor(
        val sourceTable: String,
        val edgeName: String,
        val sourceFilter: Predicate<Source>?,
    ) : Predicate<E>() {
        override fun toString(): String =
            "HasM2MEdgeFrom(sourceTable=$sourceTable, edgeName=$edgeName, sourceFilter=$sourceFilter)"
        override fun equals(other: Any?): Boolean =
            other is HasM2MEdgeFrom<*, *> &&
                other.sourceTable == sourceTable &&
                other.edgeName == edgeName &&
                other.sourceFilter == sourceFilter
        override fun hashCode(): Int {
            var h = sourceTable.hashCode()
            h = 31 * h + edgeName.hashCode()
            h = 31 * h + (sourceFilter?.hashCode() ?: 0)
            return h
        }
    }
}

enum class Op {
    EQ,
    NEQ,
    GT,
    GTE,
    LT,
    LTE,
    IN,
    NOT_IN,
    IS_NULL,
    IS_NOT_NULL,
    CONTAINS,
    HAS_PREFIX,
    HAS_SUFFIX,
}
