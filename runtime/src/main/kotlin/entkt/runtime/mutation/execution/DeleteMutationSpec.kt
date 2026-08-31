package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.HookRunner
import entkt.runtime.query.EntityQueryBuilder

/** Entity and normalized candidate evaluated together by DELETE rules. */
@EntktInternal
data class DeleteRuleCandidate<Entity, Candidate>(
    val entity: Entity,
    val candidate: Candidate,
)

/** Frozen bulk-delete selection compiled and loaded by the runtime executor. */
@EntktInternal
data class DeleteSelection<Entity : EntEntity<*>>(
    val entities: List<Entity>,
    val effectivePredicates: List<Predicate<Entity>>,
)

/** Immutable entity-specific inputs consumed by [DeleteMutationExecutor]. */
@EntktInternal
class DeleteMutationSpec<
    Entity : EntEntity<*>,
    Candidate,
    >(
    val entity: EntityMapping<Entity>,
    val idColumn: String,
    val newQuery: () -> EntityQueryBuilder<Entity, *>,
    val candidate: (Entity) -> Candidate,
    val beforeDelete: HookRunner<Entity>,
    val afterDelete: HookRunner<Entity>,
) {
}
