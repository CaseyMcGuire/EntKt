package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.MutationResult

/** Repository operations used by the generic [PendingCreateMutation] wrapper. */
@EntktInternal
interface CreateMutationRepository<Viewer : Any, Draft : CreateMutationDraft<Entity>, Entity : EntEntity<*>> {
    /** Persist a draft without disclosing the created entity. */
    fun saveCreation(viewerContext: ViewerContext<Viewer>, draft: Draft): MutationResult<Unit>

    /** Persist a draft and return the created entity under LOAD privacy. */
    fun saveAndLoadCreation(viewerContext: ViewerContext<Viewer>, draft: Draft): MutationResult<Entity>
}
