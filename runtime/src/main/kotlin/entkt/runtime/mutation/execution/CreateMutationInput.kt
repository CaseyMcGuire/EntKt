package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.privacy.ViewerContext

/** A single, already-constructed draft and its return-privacy policy. */
@EntktInternal
class CreateMutationInput<Viewer : Any, Draft>(
    val viewerContext: ViewerContext<Viewer>,
    val draft: Draft,
    val checkReturnedEntityPrivacy: Boolean,
)
