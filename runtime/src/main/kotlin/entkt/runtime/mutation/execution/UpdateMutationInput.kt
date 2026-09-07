package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRequest
import entkt.runtime.privacy.ViewerContext

/** Typed invocation data consumed by the reusable update operation. */
@EntktInternal
data class UpdateMutationInput<Draft : UpdateMutationDraft<*>>(
    val viewerContext: ViewerContext,
    val request: UpdateMutationRequest<Draft>,
    val applyLoadPrivacy: Boolean,
)
