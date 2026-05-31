package entkt.schema

data class Edge(
    val name: String,
    val target: EntSchema,
    val kind: EdgeKind,
    val ref: String? = null,
    val comment: String? = null,
)

/**
 * Many-to-many "through" metadata as a sealed type so codegen branches
 * on the write model (link table vs through-entity) at the call site
 * with exhaustive `when`. A future variant drops in without retrofitting
 * an enum or adding nullable disambiguators.
 */
sealed interface ManyToManyThrough {
    /**
     * The junction `EntSchema` (e.g. `PostTag` for `Post.tags`). Named
     * `junction` rather than `target` because in M2M edge metadata
     * "target" already means the edge's *target schema* (the type
     * parameter of `manyToMany<Target>`, e.g. `Tag` for `Post.tags`).
     * Codegen reads the M2M edge target from `Edge.target`; this
     * carries the junction class separately.
     */
    val junction: EntSchema

    /**
     * Junction edge name pointing at the source schema (the schema
     * declaring the `manyToMany`).
     */
    val sourceEdge: String

    /**
     * Junction edge name pointing at the M2M target schema (the type
     * parameter of `manyToMany<Target>`).
     */
    val targetEdge: String

    data class LinkTable(
        override val junction: EntSchema,
        override val sourceEdge: String,
        override val targetEdge: String,
        /**
         * When true, this declaration of the link table is read-only:
         * the generated entity gets read traversal but no `add` / `remove`
         * / `set` write helpers. Set via `manyToMany(...).throughLink(...)
         * .readOnly()`. See RFC 10 (Symmetric Link-Table Edges). Link-table
         * only — `ThroughEntity` has no equivalent.
         */
        val readOnly: Boolean = false,
    ) : ManyToManyThrough

    data class ThroughEntity(
        override val junction: EntSchema,
        override val sourceEdge: String,
        override val targetEdge: String,
    ) : ManyToManyThrough
}
