package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchHook
import entkt.runtime.privacy.ViewerContext

/** Entity and normalized candidate evaluated together by DELETE rules. */
@EntktInternal
data class DeleteRuleCandidate<Entity, Candidate>(
    val entity: Entity,
    val candidate: Candidate,
)

/** Frozen bulk-delete selection returned by one generated query adapter. */
@EntktInternal
data class DeleteSelection<Entity : EntEntity<*>>(
    val entities: List<Entity>,
    val effectivePredicates: List<Predicate<Entity>>,
)

/** Schema-specific candidate selection without generated lifecycle control flow. */
@EntktInternal
fun interface DeleteSelectionPhase<Entity : EntEntity<*>> {
    fun select(
        viewerContext: ViewerContext,
        predicates: List<Predicate<Entity>>,
    ): DeleteSelection<Entity>
}

/** Immutable entity-specific inputs consumed by [DeleteMutationExecutor]. */
@EntktInternal
class DeleteMutationSpec<
    Entity : EntEntity<*>,
    Candidate,
    RuleClient,
    >(
    val entity: EntityMapping<Entity>,
    val idColumn: String,
    val selectMany: DeleteSelectionPhase<Entity>,
    val candidate: (Entity) -> Candidate,
    val privacy: MutationPrivacyPhase<RuleClient, DeleteRuleCandidate<Entity, Candidate>>,
    val validation: MutationValidationPhase<RuleClient, DeleteRuleCandidate<Entity, Candidate>>,
    beforeDelete: List<BatchHook<Entity>>,
    afterDelete: List<BatchHook<Entity>>,
) {
    val beforeDelete: List<BatchHook<Entity>> = beforeDelete.toList()
    val afterDelete: List<BatchHook<Entity>> = afterDelete.toList()
}
