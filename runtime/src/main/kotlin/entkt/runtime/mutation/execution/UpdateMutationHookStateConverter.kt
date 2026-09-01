package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.BeforeSaveHookState
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.privacy.ViewerContext

/** Schema-specific conversions into immutable update hook states. */
@EntktInternal
interface UpdateMutationHookStateConverter<
    Draft,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeUpdateState : BeforeUpdateHookState<Entity>,
    > {
    fun toBeforeSaveState(draft: Draft): BeforeSaveState

    fun toBeforeUpdateState(
        viewerContext: ViewerContext,
        before: Entity,
        pendingEdges: PendingEdges,
        beforeSaveState: BeforeSaveState,
    ): BeforeUpdateState
}
