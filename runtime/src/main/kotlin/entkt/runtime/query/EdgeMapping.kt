package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping

/** Generated relationship identity and traversal metadata. */
@EntktInternal
interface EdgeMapping<
    Source : EntEntity<*>,
    Target : EntEntity<*>,
> {
    /** Declaration-derived edge name. */
    val name: String

    /** Entity on which the edge is declared. */
    val source: EntityMapping<Source>

    /** Entity reached through the edge. */
    val target: EntityMapping<Target>

    /** Storage identifier carried by generated edge predicates. */
    val storageName: String

    /** Information required to lower a chained query across this edge. */
    val traversal: EdgeTraversal<Source>?
}

/** Relationship metadata required specifically for selected-edge loading. */
@EntktInternal
interface LoadableEdgeMapping<
    Source : EntEntity<*>,
    Target : EntEntity<*>,
> : EdgeMapping<Source, Target> {
    /** Physical relationship strategy used to correlate source and target entities. */
    val storageStrategy: EdgeStorage<Source, Target>
}

/** Attach one nullable target to an immutable source entity. */
@EntktInternal
interface ToOneEdgeMapping<
    Source : EntEntity<*>,
    Target : EntEntity<*>,
> : LoadableEdgeMapping<Source, Target> {
    fun attach(source: Source, target: Target?): Source
}

/** Attach an ordered target list to an immutable source entity. */
@EntktInternal
interface ToManyEdgeMapping<
    Source : EntEntity<*>,
    Target : EntEntity<*>,
> : LoadableEdgeMapping<Source, Target> {
    fun attach(source: Source, targets: List<Target>): Source
}

/** DatabaseDriver-facing lowering metadata for a chained entity query. */
@EntktInternal
sealed interface EdgeTraversal<Source : EntEntity<*>> {
    /** Column selected from the source query before crossing the relationship. */
    val selectedColumn: String

    /** A direct relationship lowered through the inverse edge on the target entity. */
    data class Direct<Source : EntEntity<*>>(
        val inverseStorageName: String,
        override val selectedColumn: String,
    ) : EdgeTraversal<Source>

    /** A many-to-many relationship lowered through the source entity's junction edge. */
    data class ManyToMany<Source : EntEntity<*>>(
        val sourceStorageName: String,
        override val selectedColumn: String,
    ) : EdgeTraversal<Source>
}
