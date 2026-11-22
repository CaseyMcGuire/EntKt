package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRequest
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.mutation.UpdateRuleInput
import entkt.runtime.mutation.WriteCandidate

/** Stateless schema-specific operations required by the reusable UPDATE lifecycle. */
@EntktInternal
interface UpdateMutationAdapter<
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    Candidate : WriteCandidate<Entity>,
    HookState : BeforeUpdateHookState<Entity>,
    RuleInput : UpdateRuleInput<Entity>,
    > {
    fun relationshipRequirements(draft: Draft): UpdateRelationshipRequirements =
        UpdateRelationshipRequirements.None

    fun capturePendingEdges(draft: Draft): PendingEdges

    /** The prepared state is the input that UPDATE privacy and validation rules receive. */
    fun prepare(
        request: UpdateMutationRequest<Draft>,
        before: Entity,
        pendingEdges: PendingEdges,
        hookState: HookState,
        scope: UpdatePreparationScope,
    ): UpdatePreparation<RuleInput, Candidate>

    fun persistRelationships(
        request: UpdateMutationRequest<Draft>,
        state: RuleInput,
        writes: UpdateWriteTracker,
    )
}
