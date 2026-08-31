@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.result.EntMutationAlreadyConsumedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult
import entkt.runtime.privacy.ViewerContext

/** A configurable create operation that can be consumed by exactly one save terminal. */
class CreateMutation<Draft : CreateMutationDraft<Entity>, Entity : EntEntity<*>> @EntktInternal constructor(
    private val draft: Draft,
    private val repository: CreateMutationRepository<Draft, Entity>,
) {
    private var consumed = false

    /** Apply additional changes before this mutation is consumed. */
    fun configure(block: Draft.() -> Unit): CreateMutation<Draft, Entity> {
        requireAvailable("configure")
        draft.block()
        return this
    }

    /** Persist the configured draft without disclosing the created entity. */
    fun save(viewerContext: ViewerContext): MutationResult<Unit> {
        consume("save")
        return repository.saveCreation(viewerContext, draft)
    }

    /** Persist the configured draft and return the created entity under LOAD privacy. */
    fun saveAndLoad(viewerContext: ViewerContext): MutationResult<Entity> {
        consume("saveAndLoad")
        return repository.saveAndLoadCreation(viewerContext, draft)
    }

    private fun consume(action: String) {
        requireAvailable(action)
        consumed = true
    }

    private fun requireAvailable(action: String) {
        if (consumed) {
            throw EntMutationAlreadyConsumedException(EntOperation.CREATE, action)
        }
    }
}
