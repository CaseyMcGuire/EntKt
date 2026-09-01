package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.hook.HookRunner
import entkt.runtime.hook.MutationHookRunner
import entkt.runtime.privacy.ViewerContext

/** Converts and runs the hooks associated with one update lifecycle. */
@EntktInternal
class UpdateMutationHooks<
    Draft,
    Entity,
    PendingEdges,
    BeforeSaveState,
    BeforeUpdateState,
    >(
    private val converter:
        UpdateMutationHookStateConverter<
            Draft,
            Entity,
            PendingEdges,
            BeforeSaveState,
            BeforeUpdateState,
        >,
    private val beforeSave: MutationHookRunner<BeforeSaveState>,
    private val beforeUpdate: MutationHookRunner<BeforeUpdateState>,
    private val afterUpdate: HookRunner<Entity>,
) {
    /** Run both before phases and return the final immutable update state. */
    fun runBefore(
        viewerContext: ViewerContext,
        draft: Draft,
        before: Entity,
        pendingEdges: PendingEdges,
    ): BeforeUpdateState {
        val beforeSaveState = beforeSave.run(converter.toBeforeSaveState(draft))
        return beforeUpdate.run(
            converter.toBeforeUpdateState(
                viewerContext = viewerContext,
                before = before,
                pendingEdges = pendingEdges,
                beforeSaveState = beforeSaveState,
            ),
        )
    }

    /** Run the after-update hooks for the persisted entity. */
    fun runAfter(entity: Entity) {
        afterUpdate.run(listOf(entity))
    }
}
