@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.EdgePredicateScope
import entkt.query.EdgeQuery
import entkt.query.EntktInternal
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.minimumBindParameters
import entkt.runtime.entity.EntEntity
import entkt.runtime.result.EntQueryConfigurationException

/**
 * Temporary, mutable receiver for statement-style query configuration.
 *
 * Generated subclasses add typed edge-selection methods, not execution methods. Building
 * detaches new inputs; retaining a scope or an edge handle cannot change a built query.
 */
abstract class EntityQueryScope<
    Entity : EntEntity<*>,
    Self : EntityQueryScope<Entity, Self>,
> @EntktInternal protected constructor(
    protected val driver: DatabaseDriver,
    private val initialQuery: EntityQuery<Entity>,
    private val edgeOrder: Collection<EdgeMapping<Entity, *>>,
) : EdgePredicateScope<Entity>, EdgeQuery<Entity> {
    protected abstract val self: Self

    private val addedPredicates = mutableListOf<Predicate<Entity>>()
    private val addedOrder = mutableListOf<OrderField<Entity>>()
    private var limit = initialQuery.limit
    private var offset = initialQuery.offset
    private val selections = initialQuery.edges.associateByTo(
        linkedMapOf<EdgeMapping<Entity, *>, EdgeSelection<Entity, *>?>(),
    ) { it.edge }

    final override fun where(predicate: Predicate<Entity>): Self {
        addedPredicates += predicate
        return self
    }

    fun orderBy(field: OrderField<Entity>): Self {
        addedOrder += field
        return self
    }

    fun limit(n: Int): Self {
        require(n >= 0) { "limit must be non-negative; was $n" }
        limit = n
        return self
    }

    fun offset(n: Int): Self {
        require(n >= 0) { "offset must be non-negative; was $n" }
        offset = n
        return self
    }

    final override fun combinedPredicate(): Predicate<Entity>? =
        (initialQuery.predicates.map { it.semanticSnapshot() } + addedPredicates).reduceOrNull { left, right ->
            Predicate.And(left, right)
        }

    protected fun <Target : EntEntity<*>, Scope : EntityQueryScope<Target, Scope>> loadEdge(
        edge: LoadableEdgeMapping<Entity, Target>,
        scope: Scope,
        block: Scope.() -> Unit,
    ): EdgeLoad<Self> {
        require(edge.source === initialQuery.entity && edge in edgeOrder) {
            "Edge '${edge.name}' is not declared on '${initialQuery.entity.entityName}'"
        }
        if (selections.containsKey(edge)) {
            throw EntQueryConfigurationException(
                initialQuery.entity.entityName,
                "${initialQuery.entity.entityName}.${edge.name} is already selected; " +
                    "compose all configuration for the edge in a single load block",
            )
        }
        // Reserve before running user configuration so re-entrant selection is rejected too.
        selections[edge] = null
        val selection = try {
            EdgeSelection(edge, scope.apply(block).buildForInternalUse(), EdgeVisibility.REQUIRE_VISIBLE)
        } catch (failure: Throwable) {
            selections.remove(edge)
            throw failure
        }
        selections[edge] = selection
        return object : EdgeLoad<Self> {
            override fun filterVisible(): Self {
                selections[edge] = EdgeSelection(edge, selection.target, EdgeVisibility.FILTER_INVISIBLE)
                return self
            }
        }
    }

    @EntktInternal
    fun buildForInternalUse(): EntityQuery<Entity> {
        driver.requireBindCapacity(
            minimumBindParameters(initialQuery.predicates) +
                minimumBindParameters(initialQuery.structuralPredicates) +
                minimumBindParameters(addedPredicates),
            initialQuery.entity.table,
        )
        check(selections.values.none { it == null }) { "An edge configuration is still in progress" }
        return initialQuery.configured(
            addedPredicates = addedPredicates,
            addedOrder = addedOrder,
            limit = limit,
            offset = offset,
            edges = edgeOrder.mapNotNull { selections[it] },
        )
    }
}
