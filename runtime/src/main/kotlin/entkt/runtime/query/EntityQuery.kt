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
class EntityQuery<Entity : EntEntity<*>> {
    val entity: EntityMapping<Entity>
    val source: QuerySource<Entity>
    val limit: Int?
    val offset: Int?

    /** Detached caller predicates in declaration order. */
    val predicates: List<Predicate<Entity>>

    /** Detached caller ordering in declaration order. */
    val orderBy: List<OrderField<Entity>>

    /** Framework constraints that interceptors may add to but cannot reinterpret as caller input. */
    val structuralPredicates: List<Predicate<Entity>>

    /** Recursively captured selected edges in schema-declaration order. */
    val edges: List<EdgeSelection<Entity, *>>

    constructor(
        entity: EntityMapping<Entity>,
        source: QuerySource<Entity> = QuerySource.Root(),
        predicates: List<Predicate<Entity>> = emptyList(),
        orderBy: List<OrderField<Entity>> = emptyList(),
        limit: Int? = null,
        offset: Int? = null,
        edges: List<EdgeSelection<Entity, *>> = emptyList(),
        structuralPredicates: List<Predicate<Entity>> = emptyList(),
    ) {
        this.entity = entity
        this.source = source
        this.limit = limit
        this.offset = offset
        this.predicates = Collections.unmodifiableList(predicates.map { it.semanticSnapshot() })
        this.orderBy = Collections.unmodifiableList(orderBy.map { it.semanticSnapshot() })
        this.structuralPredicates = Collections.unmodifiableList(
            structuralPredicates.map { it.semanticSnapshot() },
        )
        this.edges = Collections.unmodifiableList(ArrayList(edges))
        validate()
    }

    /** Derive a value using lists already owned by this class, without re-copying their operands. */
    private constructor(
        original: EntityQuery<Entity>,
        predicates: List<Predicate<Entity>> = original.predicates,
        orderBy: List<OrderField<Entity>> = original.orderBy,
        limit: Int? = original.limit,
        offset: Int? = original.offset,
        edges: List<EdgeSelection<Entity, *>> = original.edges,
        structuralPredicates: List<Predicate<Entity>> = original.structuralPredicates,
    ) {
        entity = original.entity
        source = original.source
        this.predicates = predicates
        this.orderBy = orderBy
        this.limit = limit
        this.offset = offset
        this.edges = edges
        this.structuralPredicates = structuralPredicates
        validate()
    }

    internal fun configured(
        addedPredicates: List<Predicate<Entity>> = emptyList(),
        addedOrder: List<OrderField<Entity>> = emptyList(),
        limit: Int? = this.limit,
        offset: Int? = this.offset,
        edges: List<EdgeSelection<Entity, *>> = this.edges,
    ): EntityQuery<Entity> = EntityQuery(
        original = this,
        predicates = if (addedPredicates.isEmpty()) predicates else Collections.unmodifiableList(
            predicates + addedPredicates.map { it.semanticSnapshot() },
        ),
        orderBy = if (addedOrder.isEmpty()) orderBy else Collections.unmodifiableList(
            orderBy + addedOrder.map { it.semanticSnapshot() },
        ),
        limit = limit,
        offset = offset,
        edges = if (edges === this.edges) edges else Collections.unmodifiableList(ArrayList(edges)),
    )

    internal fun withStructuralPredicates(predicates: List<Predicate<Entity>>): EntityQuery<Entity> {
        if (predicates.isEmpty()) return this
        return EntityQuery(
            original = this,
            structuralPredicates = Collections.unmodifiableList(
                structuralPredicates + predicates.map { it.semanticSnapshot() },
            ),
        )
    }

    private fun validate() {
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
