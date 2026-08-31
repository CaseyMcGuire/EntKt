@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationAlreadyConsumedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState

/**
 * A configurable mutation awaiting its one permitted save terminal.
 *
 * Pending describes this object's pre-terminal lifecycle; it does not mean
 * [MutationWriteState.TransactionPending], which describes a write inside an unresolved transaction.
 */
abstract class PendingMutation<
    Draft : MutationDraft<Entity>,
    Entity : EntEntity<*>,
> @EntktInternal internal constructor(
    protected val draft: Draft,
    private val operation: EntOperation,
) {
    private var consumed = false

    /** Apply additional changes before this mutation is consumed. */
    fun configure(block: Draft.() -> Unit): PendingMutation<Draft, Entity> {
        throwIfAlreadyConsumed("configure")
        draft.block()
        return this
    }

    /** Persist the configured draft without disclosing the affected entity. */
    fun save(viewerContext: ViewerContext): MutationResult<Unit> {
        consume("save")
        return executeSave(viewerContext)
    }

    /** Persist the configured draft and return the affected entity under LOAD privacy. */
    fun saveAndLoad(viewerContext: ViewerContext): MutationResult<Entity> {
        consume("saveAndLoad")
        return executeSaveAndLoad(viewerContext)
    }

    protected abstract fun executeSave(viewerContext: ViewerContext): MutationResult<Unit>

    protected abstract fun executeSaveAndLoad(viewerContext: ViewerContext): MutationResult<Entity>

    private fun consume(action: String) {
        throwIfAlreadyConsumed(action)
        consumed = true
    }

    private fun throwIfAlreadyConsumed(action: String) {
        if (consumed) {
            throw EntMutationAlreadyConsumedException(operation, action)
        }
    }
}
