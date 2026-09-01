package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.privacy.ViewerContext

/** Schema-specific conversions into immutable update hook states. */
@EntktInternal
interface UpdateMutationHookStateConverter<
    Draft,
    Entity,
    PendingEdges,
    BeforeSaveState,
    BeforeUpdateState,
    > {
    fun toBeforeSaveState(draft: Draft): BeforeSaveState

    fun toBeforeUpdateState(
        viewerContext: ViewerContext,
        before: Entity,
        pendingEdges: PendingEdges,
        beforeSaveState: BeforeSaveState,
    ): BeforeUpdateState
}
