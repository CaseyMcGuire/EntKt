package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.privacy.ViewerContext

/** The identity of one row to reload and delete. */
@EntktInternal
class DeleteMutationInput(
    val viewerContext: ViewerContext,
    val id: Any,
)
