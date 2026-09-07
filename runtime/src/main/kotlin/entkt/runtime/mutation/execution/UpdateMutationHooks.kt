package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchActionHook
import entkt.runtime.hook.BatchTransformingHook
import entkt.runtime.hook.MutationBatch
import entkt.runtime.hook.runActionHooks
import entkt.runtime.hook.runTransformingHooks
import entkt.runtime.mutation.BeforeSaveHookState
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.privacy.ViewerContext

/** Converts and runs the hooks associated with one update lifecycle. */
@EntktInternal
class UpdateMutationHooks<
    Draft,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeUpdateState : BeforeUpdateHookState<Entity>,
    >(
    private val converter:
        UpdateMutationHookStateConverter<
            Draft,
            Entity,
            PendingEdges,
            BeforeSaveState,
            BeforeUpdateState,
        >,
    private val beforeSave: List<BatchTransformingHook<BeforeSaveState>>,
    private val beforeUpdate: List<BatchTransformingHook<BeforeUpdateState>>,
    private val afterUpdate: List<BatchActionHook<Entity>>,
) {
    /** Run both before phases and return the final immutable update state. */
    fun runBefore(
        entity: EntityMapping<Entity>,
        viewerContext: ViewerContext,
        draft: Draft,
        before: Entity,
        pendingEdges: PendingEdges,
    ): BeforeUpdateState {
        val beforeSaveState = runTransformingHooks(
            lifecycle = "${entity.entityName}.beforeSave",
            states = MutationBatch.from(listOf(converter.toBeforeSaveState(draft))),
            hooks = beforeSave,
        ).single()
        val beforeUpdateState = converter.toBeforeUpdateState(
            viewerContext = viewerContext,
            before = before,
            pendingEdges = pendingEdges,
            beforeSaveState = beforeSaveState,
        )
        return runTransformingHooks(
            lifecycle = "${entity.entityName}.beforeUpdate",
            states = MutationBatch.from(listOf(beforeUpdateState)),
            hooks = beforeUpdate,
        ).single()
    }

    /** Run the after-update hooks for the persisted entity. */
    fun runAfter(entity: Entity) {
        runActionHooks(listOf(entity), afterUpdate)
    }
}
