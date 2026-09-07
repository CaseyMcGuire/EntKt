package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.privacy.ViewerContext

/** Capture deferred draft construction so it runs inside the mutation's execution boundary. */
@EntktInternal
class CreateManyMutationInput<Draft>(
    val viewerContext: ViewerContext,
    blocks: List<Draft.() -> Unit>,
    internal val newDraft: () -> Draft,
) {
    val blocks: List<Draft.() -> Unit> = blocks.toList()
}
