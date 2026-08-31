package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.privacy.ViewerContext

/** Converts runtime update state into the schema-specific values received by update hooks. */
@EntktInternal
interface UpdateHookInputConverter<
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges,
    out BeforeSaveHookInput,
    out BeforeUpdateHookInput,
    > {
    fun beforeSaveInput(draft: Draft): BeforeSaveHookInput

    fun beforeUpdateInput(
        viewerContext: ViewerContext,
        draft: Draft,
        before: Entity,
        pendingEdges: PendingEdges,
    ): BeforeUpdateHookInput
}
