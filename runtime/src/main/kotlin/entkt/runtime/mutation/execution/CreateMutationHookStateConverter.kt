package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.BeforeCreateHookState
import entkt.runtime.mutation.BeforeSaveHookState
import entkt.runtime.privacy.ViewerContext

/** Schema-specific conversions between a create draft and immutable hook states. */
@EntktInternal
interface CreateMutationHookStateConverter<
    Draft,
    Entity : EntEntity<*>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeCreateState : BeforeCreateHookState<Entity>,
> {
    fun toBeforeSaveState(draft: Draft): BeforeSaveState

    fun toBeforeCreateState(
        viewerContext: ViewerContext,
        draft: Draft,
        beforeSaveState: BeforeSaveState,
    ): BeforeCreateState
}
