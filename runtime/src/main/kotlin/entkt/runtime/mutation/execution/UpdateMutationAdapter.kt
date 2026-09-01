package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.PreparedUpdateState
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRequest
import entkt.runtime.mutation.UpdatePendingEdges

/** Stateless schema-specific operations required by the reusable UPDATE lifecycle. */
@EntktInternal
interface UpdateMutationAdapter<
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    State : PreparedUpdateState<Entity>,
    HookState : BeforeUpdateHookState<Entity>,
    > {
    fun relationshipRequirements(draft: Draft): UpdateRelationshipRequirements =
        UpdateRelationshipRequirements.None

    fun capturePendingEdges(draft: Draft): PendingEdges

    fun prepare(
        request: UpdateMutationRequest<Draft>,
        before: Entity,
        pendingEdges: PendingEdges,
        hookState: HookState,
        scope: UpdatePreparationScope,
    ): UpdatePreparation<State>

    fun persistRelationships(
        request: UpdateMutationRequest<Draft>,
        state: State,
        writes: UpdateWriteTracker,
    )
}
