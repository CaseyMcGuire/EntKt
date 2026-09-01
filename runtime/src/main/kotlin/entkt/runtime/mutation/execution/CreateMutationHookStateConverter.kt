package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.privacy.ViewerContext

/** Schema-specific conversions between a create draft and immutable hook states. */
@EntktInternal
interface CreateMutationHookStateConverter<Draft, BeforeSaveState, BeforeCreateState> {
    fun toBeforeSaveState(draft: Draft): BeforeSaveState

    fun toBeforeCreateState(
        viewerContext: ViewerContext,
        draft: Draft,
        beforeSaveState: BeforeSaveState,
    ): BeforeCreateState

    /** Lower the final hook state into the draft shape consumed by preparation. */
    fun toPreparationDraft(
        originalDraft: Draft,
        state: BeforeCreateState,
    ): Draft
}
