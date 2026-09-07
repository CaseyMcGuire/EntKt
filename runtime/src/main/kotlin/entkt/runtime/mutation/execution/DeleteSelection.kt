package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.entity.EntEntity

/** Frozen bulk-delete selection compiled and loaded by the runtime operation. */
@EntktInternal
data class DeleteSelection<Entity : EntEntity<*>>(
    val entities: List<Entity>,
    val effectivePredicates: List<Predicate<Entity>>,
)
