package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.privacy.ViewerContext

/** The identity of one row to reload and delete. */
@EntktInternal
class DeleteMutationInput<Viewer : Any>(
    val viewerContext: ViewerContext<Viewer>,
    val id: Any,
)
