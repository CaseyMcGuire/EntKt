@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult

/** A pending mutation that creates one [Entity]. */
class PendingCreateMutation<Viewer : Any, Draft : CreateMutationDraft<Entity>, Entity : EntEntity<*>> @EntktInternal constructor(
    draft: Draft,
    private val repository: CreateMutationRepository<Viewer, Draft, Entity>,
) : PendingMutation<Viewer, Draft, Entity>(draft, EntOperation.CREATE) {
    override fun executeSave(viewerContext: ViewerContext<Viewer>): MutationResult<Unit> =
        repository.saveCreation(viewerContext, draft)

    override fun executeSaveAndLoad(viewerContext: ViewerContext<Viewer>): MutationResult<Entity> =
        repository.saveAndLoadCreation(viewerContext, draft)
}
