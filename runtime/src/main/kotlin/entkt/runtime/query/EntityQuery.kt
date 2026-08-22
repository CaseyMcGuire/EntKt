package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import java.util.Collections

/**
 * Immutable recursive description of the entity graph requested by one terminal.
 *
 * This object contains caller intent and explicit generated adapters only. It
 * contains no driver, interceptor evaluator, privacy evaluator, or loading
 * algorithm.
 */
@EntktInternal
class EntityQuery<Entity : EntEntity<*>>(
    val entity: EntityMapping<Entity>,
    val source: QuerySource<Entity>,
    predicates: List<Predicate<Entity>>,
    orderBy: List<OrderField<Entity>>,
    val limit: Int?,
    val offset: Int?,
    edges: List<EdgeSelection<Entity, *>>,
    structuralPredicates: List<Predicate<Entity>> = emptyList(),
) {
    /** Detached caller predicates in declaration order. */
    val predicates: List<Predicate<Entity>> = Collections.unmodifiableList(
        predicates.map { it.semanticSnapshot() },
    )

    /** Detached caller ordering in declaration order. */
    val orderBy: List<OrderField<Entity>> = Collections.unmodifiableList(
        orderBy.map { it.semanticSnapshot() },
    )

    /** Framework constraints that interceptors may add to but cannot reinterpret as caller input. */
    val structuralPredicates: List<Predicate<Entity>> = Collections.unmodifiableList(
        structuralPredicates.map { it.semanticSnapshot() },
    )

    /** Recursively captured selected edges in schema-declaration order. */
    val edges: List<EdgeSelection<Entity, *>> = Collections.unmodifiableList(
        ArrayList(edges),
    )

    init {
        require(limit == null || limit >= 0) { "Query limit must be non-negative" }
        require(offset == null || offset >= 0) { "Query offset must be non-negative" }

        for (selection in this.edges) {
            require(selection.edge.source === entity) {
                "Edge '${selection.edge.name}' is declared on " +
                    "'${selection.edge.source.entityName}', not '${entity.entityName}'"
            }
        }

        if (source is QuerySource.Traversal<*, *>) {
            require(source.edge.target === entity) {
                "Traversal edge '${source.edge.name}' targets " +
                    "'${source.edge.target.entityName}', not '${entity.entityName}'"
            }
            require(source.edge.source === source.source.entity) {
                "Traversal edge '${source.edge.name}' is declared on " +
                    "'${source.edge.source.entityName}', not " +
                    "'${source.source.entity.entityName}'"
            }
        }
    }
}

/** Relational origin from which an entity query was reached. */
@EntktInternal
sealed interface QuerySource<Target : EntEntity<*>> {
    /** A query entered directly from its generated repository. */
    class Root<Target : EntEntity<*>> : QuerySource<Target> {
        override fun equals(other: Any?): Boolean = other is Root<*>

        override fun hashCode(): Int = Root::class.hashCode()

        override fun toString(): String = "Root"
    }

    /** A target query relationally constrained by an originating source query. */
    data class Traversal<
        Source : EntEntity<*>,
        Target : EntEntity<*>,
    >(
        val source: EntityQuery<Source>,
        val edge: EdgeMapping<Source, Target>,
    ) : QuerySource<Target>
}
