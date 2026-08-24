package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.result.MutationResult

/** Repository operations used by the generic [CreateMutation] wrapper. */
@EntktInternal
interface CreateMutationRepository<Draft : Any, Entity : EntEntity<*>> {
    /** Persist a draft without disclosing the created entity. */
    fun saveCreation(draft: Draft): MutationResult<Unit>

    /** Persist a draft and return the created entity under LOAD privacy. */
    fun saveAndLoadCreation(draft: Draft): MutationResult<Entity>
}
