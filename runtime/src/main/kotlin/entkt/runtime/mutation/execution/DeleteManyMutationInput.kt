package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext

/** Predicates selecting the candidates of one atomic DELETE batch. */
@EntktInternal
class DeleteManyMutationInput<Entity : EntEntity<*>>(
    val viewerContext: ViewerContext,
    predicates: List<Predicate<Entity>>,
) {
    val predicates: List<Predicate<Entity>> = predicates.toList()
}
