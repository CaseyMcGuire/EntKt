package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRequest

/** Stateless schema-specific operations required by the reusable UPDATE lifecycle. */
@EntktInternal
interface UpdateMutationAdapter<
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges,
    State,
    > {
    fun relationshipRequirements(draft: Draft): UpdateRelationshipRequirements =
        UpdateRelationshipRequirements.None

    fun capturePendingEdges(draft: Draft): PendingEdges

    fun prepare(
        request: UpdateMutationRequest<Draft>,
        before: Entity,
        pendingEdges: PendingEdges,
        scope: UpdatePreparationScope,
    ): UpdatePreparation<State>

    fun persistRelationships(
        request: UpdateMutationRequest<Draft>,
        state: State,
        writes: UpdateWriteTracker,
    )
}
