@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult

/** A pending mutation that updates one [Entity]. */
class PendingUpdateMutation<Viewer : Any, Draft : UpdateMutationDraft<Entity>, Entity : EntEntity<*>> @EntktInternal constructor(
    private val request: UpdateMutationRequest<Draft>,
    private val repository: UpdateMutationRepository<Viewer, Draft, Entity>,
) : PendingMutation<Viewer, Draft, Entity>(request.draft, EntOperation.UPDATE) {
    override fun executeSave(viewerContext: ViewerContext<Viewer>): MutationResult<Unit> =
        repository.executeUpdate(
            viewerContext = viewerContext,
            request = request,
            applyLoadPrivacy = false,
        ).withoutValue()

    override fun executeSaveAndLoad(viewerContext: ViewerContext<Viewer>): MutationResult<Entity> =
        repository.executeUpdate(
            viewerContext = viewerContext,
            request = request,
            applyLoadPrivacy = true,
        )
}
