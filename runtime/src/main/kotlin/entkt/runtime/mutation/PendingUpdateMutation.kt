@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult

/** A pending mutation that updates one [Entity]. */
class PendingUpdateMutation<Draft : UpdateMutationDraft<Entity>, Entity : EntEntity<*>> @EntktInternal constructor(
    private val request: UpdateMutationRequest<Draft>,
    private val repository: UpdateMutationRepository<Draft, Entity>,
) : PendingMutation<Draft, Entity>(request.draft, EntOperation.UPDATE) {
    override fun executeSave(viewerContext: ViewerContext): MutationResult<Unit> =
        when (
            val result = repository.executeUpdate(
                viewerContext = viewerContext,
                request = request,
                applyLoadPrivacy = false,
            )
        ) {
            is MutationResult.Success -> MutationResult.Success(Unit)
            is MutationResult.Failed -> result
        }

    override fun executeSaveAndLoad(viewerContext: ViewerContext): MutationResult<Entity> =
        repository.executeUpdate(
            viewerContext = viewerContext,
            request = request,
            applyLoadPrivacy = true,
        )
}
