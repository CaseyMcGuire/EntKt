@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationAlreadyConsumedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult

/** A configurable update operation that can be consumed by exactly one save terminal. */
class UpdateMutation<Draft : Any, Entity : EntEntity<*>> @EntktInternal constructor(
    private val request: UpdateMutationRequest<Draft>,
    private val repository: UpdateMutationRepository<Draft, Entity>,
) {
    private var consumed = false

    /** Apply additional changes before this mutation is consumed. */
    fun configure(block: Draft.() -> Unit): UpdateMutation<Draft, Entity> {
        requireAvailable("configure")
        request.draft.block()
        return this
    }

    /** Persist the configured update without disclosing the updated entity. */
    fun save(viewerContext: ViewerContext): MutationResult<Unit> {
        consume("save")
        return when (
            val result = repository.executeUpdate(
                viewerContext = viewerContext,
                request = request,
                applyLoadPrivacy = false,
            )
        ) {
            is MutationResult.Success -> MutationResult.Success(Unit)
            is MutationResult.Failed -> result
        }
    }

    /** Persist the configured update and return the refreshed entity under LOAD privacy. */
    fun saveAndLoad(viewerContext: ViewerContext): MutationResult<Entity> {
        consume("saveAndLoad")
        return repository.executeUpdate(
            viewerContext = viewerContext,
            request = request,
            applyLoadPrivacy = true,
        )
    }

    private fun consume(action: String) {
        requireAvailable(action)
        consumed = true
    }

    private fun requireAvailable(action: String) {
        if (consumed) {
            throw EntMutationAlreadyConsumedException(EntOperation.UPDATE, action)
        }
    }
}
